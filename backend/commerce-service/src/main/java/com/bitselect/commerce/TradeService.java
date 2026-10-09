package com.bitselect.commerce;

import com.bitselect.contracts.*;
import com.bitselect.contracts.CatalogRpc.ProductSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeService {
  private static final String REFUND_SELECT =
      "SELECT r.*,o.order_no,o.total_cents FROM refund_requests r JOIN orders o ON o.id=r.order_id";
  private final JdbcTemplate db;
  private final ProductLookup catalog;

  public TradeService(JdbcTemplate db, ProductLookup catalog) {
    this.db = db;
    this.catalog = catalog;
  }

  public record Item(@Min(1) long productId, @Min(1) @Max(99) int quantity) {}

  public record Address(
      @NotBlank @Size(max = 80) String recipient,
      @NotBlank @Pattern(regexp = "[+0-9 -]{6,24}") String phone,
      @NotBlank @Size(max = 500) String detail) {}

  public record Checkout(
      @NotEmpty @Size(max = 50) List<@Valid Item> items,
      @NotNull @Valid Address address,
      @NotBlank @Size(max = 100) String idempotencyKey) {}

  private void lockUser(long user) {
    if (db.query("SELECT id FROM users WHERE id=? FOR UPDATE", (r, n) -> r.getLong(1), user)
        .isEmpty()) throw ApiException.missing();
  }

  private String fingerprint(Checkout c) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (Json.write(
                                  c.items().stream()
                                      .sorted(Comparator.comparingLong(Item::productId))
                                      .toList())
                              + "|"
                              + Json.write(c.address()))
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Map<String, Object> create(long user, Checkout c) {
    lockUser(user);
    String hash = fingerprint(c);
    var prior =
        db.queryForList(
            "SELECT id,request_hash FROM orders WHERE user_id=? AND request_key=?",
            user,
            c.idempotencyKey());
    if (!prior.isEmpty()) {
      if (!hash.equals(prior.getFirst().get("request_hash")))
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同请求标识不能提交不同订单");
      return get(user, (String) prior.getFirst().get("id"), false);
    }
    var lines = c.items().stream().sorted(Comparator.comparingLong(Item::productId)).toList();
    if (lines.stream().map(Item::productId).distinct().count() != lines.size())
      throw ApiException.bad("商品不能重复提交");
    Map<Long, ProductSnapshot> products =
        catalog.get(lines.stream().map(Item::productId).toList()).stream()
            .collect(Collectors.toMap(ProductSnapshot::id, p -> p));
    long total = 0;
    for (Item i : lines) {
      ProductSnapshot p = products.get(i.productId());
      if (p == null || !p.enabled()) throw ApiException.bad("商品不存在或已下架");
      total = Math.addExact(total, Math.multiplyExact(p.priceCents(), i.quantity()));
      int count =
          db.update(
              "UPDATE inventory SET stock=stock-?,reserved=reserved+? WHERE product_id=? AND"
                  + " stock>=?",
              i.quantity(),
              i.quantity(),
              i.productId(),
              i.quantity());
      if (count != 1) throw new ApiException(409, "INSUFFICIENT_STOCK", p.name() + "库存不足");
    }
    if (total > 1000000000L) throw ApiException.bad("单笔订单金额过大");
    String id = UUID.randomUUID().toString();
    String orderNo = "BS" + System.currentTimeMillis() + id.substring(0, 8);
    Instant now = Instant.now();
    db.update(
        "INSERT INTO"
            + " orders(id,order_no,user_id,status,total_cents,address_json,request_key,request_hash,created_at,expires_at)VALUES(?,?,?,'PENDING_PAYMENT',?,?,?,?,?,?)",
        id,
        orderNo,
        user,
        total,
        Json.write(c.address()),
        c.idempotencyKey(),
        hash,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(900)));
    for (Item i : lines) {
      var p = products.get(i.productId());
      db.update(
          "INSERT INTO"
              + " order_items(order_id,product_id,name,image_url,price_cents,quantity)VALUES(?,?,?,?,?,?)",
          id,
          p.id(),
          p.name(),
          p.imageUrl(),
          p.priceCents(),
          i.quantity());
      db.update("DELETE FROM cart_items WHERE user_id=? AND product_id=?", user, p.id());
    }
    outbox("OrderCreated", id, Map.of("userId", user, "totalCents", total));
    return get(user, id, false);
  }

  private Map<String, Object> locked(long user, String id) {
    lockUser(user);
    var rows =
        db.queryForList("SELECT * FROM orders WHERE id=? AND user_id=? FOR UPDATE", id, user);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  private List<Map<String, Object>> lines(String id) {
    return db.queryForList("SELECT * FROM order_items WHERE order_id=? ORDER BY product_id", id);
  }

  private int qty(Map<String, Object> line) {
    return ((Number) line.get("quantity")).intValue();
  }

  private long balance(long user) {
    return db.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, user);
  }

  private void ledger(long user, String type, long amount, String ref, String reason) {
    db.update(
        "INSERT INTO"
            + " wallet_ledger(user_id,type,amount_cents,balance_after_cents,reference_id,reason)VALUES(?,?,?,?,?,?)",
        user,
        type,
        amount,
        balance(user),
        ref,
        reason);
  }

  @Transactional
  public Map<String, Object> pay(long user, String id) {
    var order = locked(user, id);
    String status = (String) order.get("status");
    if (Set.of("PAID", "SHIPPED", "COMPLETED").contains(status)) return get(user, id, false);
    if (!status.equals("PENDING_PAYMENT"))
      throw new ApiException(409, "INVALID_ORDER_STATE", "订单当前不能支付");
    if (((Timestamp) order.get("expires_at")).toInstant().isBefore(Instant.now()))
      throw new ApiException(409, "ORDER_EXPIRED", "订单已超时，请重新下单");
    long total = ((Number) order.get("total_cents")).longValue();
    if (db.update(
            "UPDATE users SET balance_cents=balance_cents-? WHERE id=? AND balance_cents>=?",
            total,
            user,
            total)
        != 1) throw new ApiException(409, "INSUFFICIENT_BALANCE", "余额不足，请联系管理员充值");
    for (var line : lines(id)) {
      int q = qty(line);
      db.update(
          "UPDATE inventory SET reserved=reserved-? WHERE product_id=?", q, line.get("product_id"));
      db.update("UPDATE products SET sold=sold+? WHERE id=?", q, line.get("product_id"));
    }
    db.update(
        "UPDATE orders SET status='PAID',paid_at=? WHERE id=?", Timestamp.from(Instant.now()), id);
    ledger(user, "PAYMENT", -total, id, "订单支付");
    outbox("OrderPaid", id, Map.of("userId", user, "totalCents", total));
    return get(user, id, false);
  }

  @Transactional
  public Map<String, Object> cancel(long user, String id) {
    var order = locked(user, id);
    if ("CANCELLED".equals(order.get("status"))) return get(user, id, false);
    if (!"PENDING_PAYMENT".equals(order.get("status")))
      throw new ApiException(409, "INVALID_ORDER_STATE", "仅待支付订单可以取消");
    release(id);
    db.update("UPDATE orders SET status='CANCELLED' WHERE id=?", id);
    outbox("OrderCancelled", id, Map.of("userId", user));
    return get(user, id, false);
  }

  private void release(String id) {
    for (var line : lines(id)) {
      int q = qty(line);
      db.update(
          "UPDATE inventory SET stock=stock+?,reserved=reserved-? WHERE product_id=?",
          q,
          q,
          line.get("product_id"));
    }
  }

  @Transactional
  public void expire(long user, String id) {
    var order = locked(user, id);
    if ("PENDING_PAYMENT".equals(order.get("status"))
        && ((Timestamp) order.get("expires_at")).toInstant().isBefore(Instant.now())) {
      release(id);
      db.update("UPDATE orders SET status='CANCELLED' WHERE id=?", id);
      outbox("OrderExpired", id, Map.of("userId", user));
    }
  }

  @Transactional
  public Map<String, Object> refund(long user, String id) {
    var order = locked(user, id);
    if ("REFUNDED".equals(order.get("status"))) return get(user, id, false);
    if (!"PAID".equals(order.get("status")))
      throw new ApiException(409, "INVALID_ORDER_STATE", "仅未发货的已支付订单可以退款");
    long amount = ((Number) order.get("total_cents")).longValue();
    db.update("UPDATE users SET balance_cents=balance_cents+? WHERE id=?", amount, user);
    for (var line : lines(id)) {
      int q = qty(line);
      db.update("UPDATE inventory SET stock=stock+? WHERE product_id=?", q, line.get("product_id"));
      db.update("UPDATE products SET sold=sold-? WHERE id=?", q, line.get("product_id"));
    }
    db.update("UPDATE orders SET status='REFUNDED' WHERE id=?", id);
    ledger(user, "REFUND", amount, id, "未发货订单退款");
    outbox("OrderRefunded", id, Map.of("userId", user, "totalCents", amount));
    return get(user, id, false);
  }

  @Transactional
  public Map<String, Object> confirm(long user, String id) {
    var order = locked(user, id);
    if ("COMPLETED".equals(order.get("status"))) return get(user, id, false);
    if (!"SHIPPED".equals(order.get("status")))
      throw new ApiException(409, "INVALID_ORDER_STATE", "仅已发货订单可以确认收货");
    db.update("UPDATE orders SET status='COMPLETED' WHERE id=?", id);
    outbox("OrderCompleted", id, Map.of("userId", user));
    return get(user, id, false);
  }

  @Transactional
  public Map<String, Object> ship(String id, String tracking) {
    var rows = db.queryForList("SELECT user_id FROM orders WHERE id=?", id);
    if (rows.isEmpty()) throw ApiException.missing();
    long user = ((Number) rows.getFirst().get("user_id")).longValue();
    var order = locked(user, id);
    if ("SHIPPED".equals(order.get("status")) && tracking.equals(order.get("tracking_no")))
      return get(user, id, true);
    if (!"PAID".equals(order.get("status")))
      throw new ApiException(409, "INVALID_ORDER_STATE", "仅已支付订单可以发货");
    db.update("UPDATE orders SET status='SHIPPED',tracking_no=? WHERE id=?", tracking, id);
    outbox("OrderShipped", id, Map.of("userId", user, "trackingNo", tracking));
    return get(user, id, true);
  }

  @Transactional
  public Map<String, Object> credit(long user, long amount, String key, String reason) {
    lockUser(user);
    var old =
        db.queryForList(
            "SELECT amount_cents FROM wallet_ledger WHERE user_id=? AND type='CREDIT' AND"
                + " reference_id=?",
            user,
            key);
    if (!old.isEmpty()) {
      if (((Number) old.getFirst().get("amount_cents")).longValue() != amount)
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "充值请求标识已被不同金额使用");
      return Map.of("balanceCents", balance(user));
    }
    if (amount < 1 || amount > 100000000) throw ApiException.bad("单次充值必须在1分到100万元之间");
    db.update("UPDATE users SET balance_cents=balance_cents+? WHERE id=?", amount, user);
    ledger(user, "CREDIT", amount, key, reason);
    outbox("WalletCredited", String.valueOf(user), Map.of("amountCents", amount));
    return Map.of("balanceCents", balance(user));
  }

  @Transactional
  public Map<String, Object> inventory(
      long admin, long product, int delta, String key, String reason) {
    if (db.query(
            "SELECT product_id FROM inventory WHERE product_id=? FOR UPDATE",
            (r, n) -> r.getLong(1),
            product)
        .isEmpty()) throw ApiException.missing();
    var existing =
        db.queryForList(
            "SELECT product_id,delta FROM inventory_adjustments WHERE request_key=?", key);
    if (!existing.isEmpty()) {
      if (((Number) existing.getFirst().get("product_id")).longValue() != product
          || ((Number) existing.getFirst().get("delta")).intValue() != delta)
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "库存请求标识已被使用");
      return Map.of(
          "stock",
          db.queryForObject(
              "SELECT stock FROM inventory WHERE product_id=?", Integer.class, product));
    }
    if (db.update(
            "UPDATE inventory SET stock=stock+? WHERE product_id=? AND stock+?>=0",
            delta,
            product,
            delta)
        != 1) throw new ApiException(409, "INVALID_INVENTORY", "商品不存在或可用库存不足");
    db.update(
        "INSERT INTO inventory_adjustments(product_id,request_key,delta,reason,admin_id)"
            + " VALUES(?,?,?,?,?)",
        product,
        key,
        delta,
        reason,
        admin);
    return Map.of(
        "stock",
        db.queryForObject(
            "SELECT stock FROM inventory WHERE product_id=?", Integer.class, product));
  }

  public Map<String, Object> get(long user, String id, boolean admin) {
    var rows =
        db.queryForList(
            "SELECT * FROM orders WHERE id=?" + (admin ? "" : " AND user_id=?"),
            admin ? new Object[] {id} : new Object[] {id, user});
    if (rows.isEmpty()) throw ApiException.missing();
    var row = rows.getFirst();
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("id", row.get("id"));
    o.put("orderNo", row.get("order_no"));
    o.put("userId", row.get("user_id"));
    o.put("status", row.get("status"));
    o.put("totalCents", row.get("total_cents"));
    o.put("address", Json.read((String) row.get("address_json")));
    o.put("createdAt", ((Timestamp) row.get("created_at")).toInstant());
    o.put("expiresAt", ((Timestamp) row.get("expires_at")).toInstant());
    o.put(
        "paidAt", row.get("paid_at") == null ? null : ((Timestamp) row.get("paid_at")).toInstant());
    o.put("trackingNo", row.get("tracking_no"));
    o.put(
        "items",
        db.query(
            "SELECT product_id,name,image_url,price_cents,quantity FROM order_items WHERE"
                + " order_id=?",
            (r, n) ->
                Map.of(
                    "productId",
                    r.getLong(1),
                    "name",
                    r.getString(2),
                    "imageUrl",
                    r.getString(3),
                    "priceCents",
                    r.getLong(4),
                    "quantity",
                    r.getInt(5)),
            id));
    return o;
  }

  public Map<String, Object> list(long user, boolean admin, int page, int pageSize) {
    page = Math.max(1, page);
    pageSize = Math.min(100, Math.max(1, pageSize));
    String where = admin ? "" : " WHERE user_id=?";
    Object[] args = admin ? new Object[] {} : new Object[] {user};
    long count = db.queryForObject("SELECT COUNT(*) FROM orders" + where, Long.class, args);
    List<Object> a = new ArrayList<>(Arrays.asList(args));
    a.add(pageSize);
    a.add(((long) page - 1) * pageSize);
    var ids =
        db.query(
            "SELECT id FROM orders" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (r, n) -> r.getString(1),
            a.toArray());
    return Map.of(
        "items",
        ids.stream().map(id -> get(user, id, admin)).toList(),
        "total",
        count,
        "page",
        page,
        "pageSize",
        pageSize);
  }

  private void outbox(String type, String id, Object payload) {
    String eventId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO event_outbox(id,topic,event_type,aggregate_id,payload)VALUES(?,?,?,?,?)",
        eventId,
        "bit-commerce-events",
        type,
        id,
        Json.write(
            Map.of("eventId", eventId, "type", type, "aggregateId", id, "payload", payload)));
  }

  @Transactional
  public Map<String, Object> requestRefund(long user, String orderId, String reason) {
    var order = locked(user, orderId);
    if (!Set.of("SHIPPED", "COMPLETED").contains(order.get("status")))
      throw new ApiException(409, "INVALID_ORDER_STATE", "仅已发货或已完成订单可以申请退货退款");
    var existing =
        db.queryForList("SELECT id,status FROM refund_requests WHERE order_id=?", orderId);
    if (!existing.isEmpty()) {
      String id = (String) existing.getFirst().get("id");
      if ("REJECTED".equals(existing.getFirst().get("status"))) {
        String previousId = id;
        id = UUID.randomUUID().toString();
        db.update(
            "UPDATE refund_requests SET"
                + " id=?,status='REQUESTED',reason=?,created_at=CURRENT_TIMESTAMP,review_reason=NULL,reviewer_id=NULL,reviewed_at=NULL"
                + " WHERE id=?",
            id,
            reason,
            previousId);
        // A new submission has a new identity; delayed reviews of the rejected request must fail.
        outbox(
            "RefundRequested",
            orderId,
            Map.of("userId", user, "refundRequestId", id, "previousRequestId", previousId));
      }
      return refundRecord(id);
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO refund_requests(id,order_id,user_id,reason,status)VALUES(?,?,?,?,'REQUESTED')",
        id,
        orderId,
        user,
        reason);
    outbox("RefundRequested", orderId, Map.of("userId", user, "refundRequestId", id));
    return refundRecord(id);
  }

  public Map<String, Object> refunds(long user, boolean admin) {
    // A resubmission changes the request ID; fetch complete rows in one statement snapshot.
    return Map.of(
        "items",
        db.query(
            REFUND_SELECT
                + (admin ? "" : " WHERE r.user_id=?")
                + " ORDER BY r.created_at DESC,r.id LIMIT 100",
            this::mapRefund,
            admin ? new Object[] {} : new Object[] {user}));
  }

  private Map<String, Object> refundRecord(String id) {
    return refundRecord(id, false);
  }

  private Map<String, Object> refundRecord(String id, boolean locking) {
    var rows =
        db.query(
            REFUND_SELECT + " WHERE r.id=?" + (locking ? " FOR UPDATE" : ""), this::mapRefund, id);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  private Map<String, Object> mapRefund(ResultSet row, int index) throws SQLException {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", row.getString("id"));
    result.put("orderId", row.getString("order_id"));
    result.put("orderNo", row.getString("order_no"));
    result.put("userId", row.getLong("user_id"));
    result.put("totalCents", row.getLong("total_cents"));
    result.put("reason", row.getString("reason"));
    result.put("status", row.getString("status"));
    result.put("reviewReason", row.getString("review_reason"));
    result.put("createdAt", row.getTimestamp("created_at").toInstant());
    return result;
  }

  @Transactional
  public Map<String, Object> reviewRefund(
      long admin, String requestId, boolean approve, String reason) {
    var request = refundRecord(requestId);
    long user = ((Number) request.get("userId")).longValue();
    String orderId = (String) request.get("orderId");
    var order = locked(user, orderId);
    var states =
        db.queryForList("SELECT status FROM refund_requests WHERE id=? FOR UPDATE", requestId);
    // The user may have resubmitted while this reviewer waited for the user/order locks.
    if (states.isEmpty()) throw ApiException.missing();
    var current = states.getFirst();
    String target = approve ? "COMPLETED" : "REJECTED";
    if (target.equals(current.get("status"))) return refundRecord(requestId, true);
    if (!"REQUESTED".equals(current.get("status")))
      throw new ApiException(409, "INVALID_REFUND_STATE", "售后申请已处理");
    if (approve) {
      if (!Set.of("SHIPPED", "COMPLETED").contains(order.get("status")))
        throw new ApiException(409, "INVALID_ORDER_STATE", "订单状态不允许售后退款");
      long amount = ((Number) order.get("total_cents")).longValue();
      db.update("UPDATE users SET balance_cents=balance_cents+? WHERE id=?", amount, user);
      for (var line : lines(orderId)) {
        int quantity = qty(line);
        db.update(
            "UPDATE inventory SET stock=stock+? WHERE product_id=?",
            quantity,
            line.get("product_id"));
        db.update("UPDATE products SET sold=sold-? WHERE id=?", quantity, line.get("product_id"));
      }
      db.update("UPDATE orders SET status='REFUNDED' WHERE id=?", orderId);
      ledger(user, "REFUND", amount, orderId, "退货验收后退款");
      outbox(
          "OrderRefunded",
          orderId,
          Map.of("userId", user, "totalCents", amount, "reviewerId", admin));
    }
    db.update(
        "UPDATE refund_requests SET"
            + " status=?,review_reason=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
        target,
        reason,
        admin,
        requestId);
    return refundRecord(requestId, true);
  }
}
