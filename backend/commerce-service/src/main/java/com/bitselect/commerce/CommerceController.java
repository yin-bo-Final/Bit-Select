package com.bitselect.commerce;

import com.bitselect.contracts.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CommerceController {
  private final TradeService trade;
  private final SessionService sessions;
  private final Users users;
  private final JdbcTemplate db;

  public CommerceController(TradeService t, SessionService s, Users u, JdbcTemplate d) {
    trade = t;
    sessions = s;
    users = u;
    db = d;
  }

  private long id(HttpServletRequest r) {
    return sessions.requireUserId(r);
  }

  private long admin(HttpServletRequest r) {
    long id = id(r);
    users.admin(id);
    return id;
  }

  @PostMapping("/orders")
  Object create(HttpServletRequest r, @Valid @RequestBody TradeService.Checkout c) {
    return trade.create(id(r), c);
  }

  @GetMapping("/orders")
  Object list(
      HttpServletRequest r,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return trade.list(id(r), false, page, pageSize);
  }

  @GetMapping("/orders/{order}")
  Object get(HttpServletRequest r, @PathVariable String order) {
    return trade.get(id(r), order, false);
  }

  @PostMapping("/orders/{order}/pay")
  Object pay(HttpServletRequest r, @PathVariable String order) {
    return trade.pay(id(r), order);
  }

  @PostMapping("/orders/{order}/cancel")
  Object cancel(HttpServletRequest r, @PathVariable String order) {
    return trade.cancel(id(r), order);
  }

  @PostMapping("/orders/{order}/refund")
  Object refund(HttpServletRequest r, @PathVariable String order) {
    return trade.refund(id(r), order);
  }

  @PostMapping("/orders/{order}/confirm")
  Object confirm(HttpServletRequest r, @PathVariable String order) {
    return trade.confirm(id(r), order);
  }

  @GetMapping("/wallet")
  Object wallet(HttpServletRequest r) {
    long user = id(r);
    return Map.of(
        "balanceCents",
        users.get(user).get("balanceCents"),
        "ledger",
        db.query(
            "SELECT id,type,amount_cents,balance_after_cents,reference_id,created_at FROM"
                + " wallet_ledger WHERE user_id=? ORDER BY id DESC LIMIT 100",
            (rs, n) ->
                Map.of(
                    "id",
                    rs.getLong(1),
                    "type",
                    rs.getString(2),
                    "amountCents",
                    rs.getLong(3),
                    "balanceAfterCents",
                    rs.getLong(4),
                    "referenceId",
                    rs.getString(5),
                    "createdAt",
                    rs.getTimestamp(6).toInstant()),
            user));
  }

  @GetMapping("/cart")
  Object cart(HttpServletRequest r) {
    long user = id(r);
    var items =
        db.query(
            "SELECT c.product_id,c.quantity,p.name,p.price_cents,p.image_url,p.enabled,i.stock FROM"
                + " cart_items c JOIN products p ON p.id=c.product_id JOIN inventory i ON"
                + " i.product_id=p.id WHERE c.user_id=? ORDER BY c.product_id",
            (rs, n) ->
                Map.<String, Object>of(
                    "productId",
                    rs.getLong(1),
                    "quantity",
                    rs.getInt(2),
                    "product",
                    Map.of(
                        "id",
                        rs.getLong(1),
                        "name",
                        rs.getString(3),
                        "priceCents",
                        rs.getLong(4),
                        "imageUrl",
                        rs.getString(5),
                        "enabled",
                        rs.getBoolean(6),
                        "stock",
                        rs.getInt(7))),
            user);
    long total =
        items.stream()
            .mapToLong(
                i ->
                    ((Number) i.get("quantity")).longValue()
                        * ((Number) ((Map<?, ?>) i.get("product")).get("priceCents")).longValue())
            .sum();
    return Map.of("items", items, "totalCents", total);
  }

  record Quantity(@Min(0) @Max(99) int quantity) {}

  @PutMapping("/cart/items/{product}")
  @Transactional
  Object cartPut(HttpServletRequest r, @PathVariable long product, @Valid @RequestBody Quantity q) {
    long user = id(r);
    if (q.quantity() == 0) {
      db.update("DELETE FROM cart_items WHERE user_id=? AND product_id=?", user, product);
      return Map.of("ok", true);
    }
    if (db.queryForObject(
            "SELECT COUNT(*) FROM products WHERE id=? AND enabled=TRUE", Integer.class, product)
        == 0) throw ApiException.missing();
    db.update(
        "INSERT INTO cart_items(user_id,product_id,quantity)VALUES(?,?,?) ON DUPLICATE KEY UPDATE"
            + " quantity=?",
        user,
        product,
        q.quantity(),
        q.quantity());
    return Map.of("ok", true);
  }

  @DeleteMapping("/cart/items/{product}")
  Object deleteCart(HttpServletRequest r, @PathVariable long product) {
    db.update("DELETE FROM cart_items WHERE user_id=? AND product_id=?", id(r), product);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/users")
  Object userList(
      HttpServletRequest r,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    admin(r);
    page = Math.max(1, page);
    pageSize = Math.max(1, Math.min(100, pageSize));
    var ids =
        db.query(
            "SELECT id FROM users WHERE username LIKE ? OR nickname LIKE ? ORDER BY id LIMIT ?"
                + " OFFSET ?",
            (rs, n) -> rs.getLong(1),
            "%" + q + "%",
            "%" + q + "%",
            pageSize,
            ((long) page - 1) * pageSize);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM users WHERE username LIKE ? OR nickname LIKE ?",
            Long.class,
            "%" + q + "%",
            "%" + q + "%");
    return Map.of(
        "items",
        ids.stream().map(users::get).toList(),
        "total",
        total,
        "page",
        page,
        "pageSize",
        pageSize);
  }

  record Credit(
      @Min(1) @Max(100000000) long amountCents,
      @NotBlank @Size(max = 100) String idempotencyKey,
      @NotBlank @Size(max = 250) String reason) {}

  @PostMapping("/admin/users/{user}/credit")
  Object credit(HttpServletRequest r, @PathVariable long user, @Valid @RequestBody Credit c) {
    admin(r);
    return trade.credit(user, c.amountCents(), c.idempotencyKey(), c.reason());
  }

  record Inventory(
      @Min(-1000000) @Max(1000000) int delta,
      @NotBlank @Size(max = 100) String idempotencyKey,
      @NotBlank @Size(max = 250) String reason) {}

  @PostMapping("/admin/products/{product}/inventory")
  Object inventory(
      HttpServletRequest r, @PathVariable long product, @Valid @RequestBody Inventory i) {
    return trade.inventory(admin(r), product, i.delta(), i.idempotencyKey(), i.reason());
  }

  @GetMapping("/admin/orders")
  Object adminOrders(
      HttpServletRequest r,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return trade.list(admin(r), true, page, pageSize);
  }

  record Shipment(@NotBlank @Size(max = 100) String trackingNo) {}

  @PostMapping("/admin/orders/{order}/ship")
  Object ship(HttpServletRequest r, @PathVariable String order, @Valid @RequestBody Shipment s) {
    admin(r);
    return trade.ship(order, s.trackingNo());
  }

  @GetMapping("/admin/overview")
  Object overview(HttpServletRequest r) {
    admin(r);
    return Map.of(
        "userCount",
        db.queryForObject("SELECT COUNT(*) FROM users WHERE role='USER'", Long.class),
        "productCount",
        db.queryForObject("SELECT COUNT(*) FROM products", Long.class),
        "orderCount",
        db.queryForObject("SELECT COUNT(*) FROM orders", Long.class),
        "paidRevenueCents",
        db.queryForObject(
            "SELECT COALESCE(SUM(total_cents),0) FROM orders WHERE status IN"
                + " ('PAID','SHIPPED','COMPLETED')",
            Long.class),
        "pendingOrderCount",
        db.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE status='PENDING_PAYMENT'", Long.class));
  }
}
