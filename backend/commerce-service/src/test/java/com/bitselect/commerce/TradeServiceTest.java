package com.bitselect.commerce;

import static org.junit.jupiter.api.Assertions.*;

import com.bitselect.contracts.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(TradeServiceTest.Config.class)
class TradeServiceTest {
  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSource dataSource() {
      DriverManagerDataSource d =
          new DriverManagerDataSource(
              "jdbc:h2:mem:trade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
              "sa",
              "");
      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V1__commerce_schema.sql"),
              new ClassPathResource("db/migration/V3__addresses_and_returns.sql"))
          .execute(d);
      return d;
    }

    @Bean
    JdbcTemplate jdbc(DataSource d) {
      return new JdbcTemplate(d);
    }

    @Bean
    PlatformTransactionManager manager(DataSource d) {
      return new DataSourceTransactionManager(d);
    }

    @Bean
    ProductLookup lookup(JdbcTemplate d) {
      return ids ->
          ids.stream()
              .map(
                  id ->
                      d.queryForObject(
                          "SELECT id,name,image_url,price_cents,enabled FROM products WHERE id=?",
                          (r, n) ->
                              new CatalogRpc.ProductSnapshot(
                                  r.getLong(1),
                                  r.getString(2),
                                  r.getString(3),
                                  r.getLong(4),
                                  r.getBoolean(5)),
                          id))
              .toList();
    }

    @Bean
    TradeService service(JdbcTemplate d, ProductLookup p) {
      return new TradeService(d, p);
    }
  }

  @Autowired TradeService trade;
  @Autowired JdbcTemplate db;

  @BeforeEach
  void setup() {
    for (String table :
        List.of(
            "refund_requests",
            "user_addresses",
            "wallet_ledger",
            "inventory_adjustments",
            "event_outbox",
            "order_items",
            "orders",
            "cart_items",
            "inventory",
            "products",
            "users")) db.update("DELETE FROM " + table);
    db.update(
        "INSERT INTO"
            + " users(id,username,password_hash,nickname,balance_cents)VALUES(1,'one','hash','一',10000),(2,'two','hash','二',10000)");
    db.update(
        "INSERT INTO"
            + " products(id,name,category,category_name,description,price_cents,original_price_cents,image_url,specifications,tags)VALUES(1,'键盘','office','办公','商品',1000,1200,'/one.svg','{}','[]'),(2,'鼠标','office','办公','商品',2000,2400,'/two.svg','{}','[]')");
    db.update("INSERT INTO inventory(product_id,stock,reserved)VALUES(1,10,0),(2,10,0)");
  }

  TradeService.Checkout checkout(int quantity, String key) {
    return new TradeService.Checkout(
        List.of(new TradeService.Item(1, quantity)),
        new TradeService.Address("小明", "13800000000", "上海测试地址"),
        key);
  }

  String create(long user, int quantity, String key) {
    return (String) trade.create(user, checkout(quantity, key)).get("id");
  }

  long value(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  @Test
  void reservePayRefundIsAtomicAndIdempotent() {
    String id = create(1, 2, "first");
    assertEquals(id, create(1, 2, "first"));
    assertEquals(8, value("SELECT stock FROM inventory WHERE product_id=1"));
    assertEquals(2, value("SELECT reserved FROM inventory WHERE product_id=1"));
    trade.pay(1, id);
    trade.pay(1, id);
    assertEquals(8000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertEquals(1, value("SELECT COUNT(*) FROM wallet_ledger WHERE type='PAYMENT'"));
    trade.refund(1, id);
    trade.refund(1, id);
    assertEquals(10000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertEquals(10, value("SELECT stock FROM inventory WHERE product_id=1"));
    assertEquals(0, value("SELECT reserved FROM inventory WHERE product_id=1"));
    assertEquals(1, value("SELECT COUNT(*) FROM wallet_ledger WHERE type='REFUND'"));
  }

  @Test
  void reservationFailureRollsBackEarlierLines() {
    db.update("UPDATE inventory SET stock=0 WHERE product_id=2");
    var c =
        new TradeService.Checkout(
            List.of(new TradeService.Item(1, 2), new TradeService.Item(2, 1)),
            checkout(1, "fail").address(),
            "fail");
    assertThrows(ApiException.class, () -> trade.create(1, c));
    assertEquals(10, value("SELECT stock FROM inventory WHERE product_id=1"));
    assertEquals(0, value("SELECT COUNT(*) FROM orders"));
  }

  @Test
  void userCannotAccessAnotherUsersOrder() {
    String id = create(1, 1, "private");
    assertThrows(ApiException.class, () -> trade.get(2, id, false));
    assertThrows(ApiException.class, () -> trade.pay(2, id));
    assertThrows(ApiException.class, () -> trade.refund(2, id));
    assertEquals(10000, value("SELECT balance_cents FROM users WHERE id=2"));
  }

  @Test
  void insufficientFundsChangesNothing() {
    String id = create(1, 1, "poor");
    db.update("UPDATE users SET balance_cents=0 WHERE id=1");
    assertThrows(ApiException.class, () -> trade.pay(1, id));
    assertEquals("PENDING_PAYMENT", trade.get(1, id, false).get("status"));
    assertEquals(1, value("SELECT reserved FROM inventory WHERE product_id=1"));
    assertEquals(0, value("SELECT COUNT(*) FROM wallet_ledger"));
  }

  @Test
  void expiryReleasesOnceAndBlocksPayment() {
    String id = create(1, 3, "expired");
    db.update(
        "UPDATE orders SET expires_at=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        id);
    assertThrows(ApiException.class, () -> trade.pay(1, id));
    trade.expire(1, id);
    trade.expire(1, id);
    assertEquals("CANCELLED", trade.get(1, id, false).get("status"));
    assertEquals(10, value("SELECT stock FROM inventory WHERE product_id=1"));
  }

  @Test
  void idempotencyKeyRejectsChangedPayload() {
    create(1, 1, "same");
    assertThrows(ApiException.class, () -> create(1, 2, "same"));
    trade.credit(1, 100, "credit", "补贴");
    trade.credit(1, 100, "credit", "补贴");
    assertThrows(ApiException.class, () -> trade.credit(1, 200, "credit", "补贴"));
    assertEquals(10100, value("SELECT balance_cents FROM users WHERE id=1"));
  }

  @Test
  void veryLargePageNumbersDoNotOverflowIntoNegativeSqlOffsets() {
    create(1, 1, "page-boundary");
    assertEquals(List.of(), trade.list(1, false, Integer.MAX_VALUE, 100).get("items"));
    db.update("UPDATE users SET role='ADMIN' WHERE id=1");
    var sessions = org.mockito.Mockito.mock(SessionService.class);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    org.mockito.Mockito.when(sessions.requireUserId(request)).thenReturn(1L);
    var controller = new CommerceController(trade, sessions, new Users(db), db);
    var users = (Map<?, ?>) controller.userList(request, "", Integer.MAX_VALUE, 100);
    assertEquals(List.of(), users.get("items"));
    assertEquals(2L, users.get("total"));
  }

  @Test
  void shippingLocksOutCancellationAndRefund() {
    String id = create(1, 1, "ship");
    trade.pay(1, id);
    trade.ship(id, "SF123456");
    assertThrows(ApiException.class, () -> trade.refund(1, id));
    assertThrows(ApiException.class, () -> trade.cancel(1, id));
    trade.confirm(1, id);
    trade.confirm(1, id);
    assertEquals("COMPLETED", trade.get(1, id, false).get("status"));
  }

  @Test
  void concurrentBuyersCannotOversell() throws Exception {
    db.update("UPDATE inventory SET stock=1 WHERE product_id=1");
    var results =
        parallel(
            () -> {
              try {
                create(1, 1, "race1");
                return true;
              } catch (ApiException e) {
                return false;
              }
            },
            () -> {
              try {
                create(2, 1, "race2");
                return true;
              } catch (ApiException e) {
                return false;
              }
            });
    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    assertEquals(0, value("SELECT stock FROM inventory WHERE product_id=1"));
    assertEquals(1, value("SELECT reserved FROM inventory WHERE product_id=1"));
  }

  @Test
  void concurrentPaymentsNeverDoubleDebit() throws Exception {
    String id = create(1, 2, "payrace");
    parallel(
        () -> {
          trade.pay(1, id);
          return true;
        },
        () -> {
          trade.pay(1, id);
          return true;
        });
    assertEquals(8000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertEquals(1, value("SELECT COUNT(*) FROM wallet_ledger WHERE type='PAYMENT'"));
    assertEquals(0, value("SELECT reserved FROM inventory WHERE product_id=1"));
  }

  @Test
  void concurrentOrdersCannotOverdrawWallet() throws Exception {
    db.update("UPDATE users SET balance_cents=1000 WHERE id=1");
    String a = create(1, 1, "a"), b = create(1, 1, "b");
    var results =
        parallel(
            () -> {
              try {
                trade.pay(1, a);
                return true;
              } catch (ApiException e) {
                return false;
              }
            },
            () -> {
              try {
                trade.pay(1, b);
                return true;
              } catch (ApiException e) {
                return false;
              }
            });
    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    assertEquals(0, value("SELECT balance_cents FROM users WHERE id=1"));
  }

  private List<Boolean> parallel(Callable<Boolean> a, Callable<Boolean> b) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var x =
          pool.submit(
              () -> {
                start.await();
                return a.call();
              });
      var y =
          pool.submit(
              () -> {
                start.await();
                return b.call();
              });
      start.countDown();
      return List.of(x.get(15, TimeUnit.SECONDS), y.get(15, TimeUnit.SECONDS));
    }
  }

  @Test
  void shippedReturnNeedsApprovalAndRefundsOnlyOnce() {
    String id = create(1, 2, "return");
    trade.pay(1, id);
    trade.ship(id, "SFRETURN");
    String request = (String) trade.requestRefund(1, id, "不合适").get("id");
    assertEquals(8000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertThrows(ApiException.class, () -> trade.requestRefund(2, id, "越权"));
    trade.reviewRefund(2, request, false, "需先寄回");
    assertEquals(
        "REJECTED",
        ((Map<?, ?>) ((List<?>) trade.refunds(1, false).get("items")).getFirst()).get("status"));
    request = (String) trade.requestRefund(1, id, "已寄回").get("id");
    trade.reviewRefund(2, request, true, "已验收入库");
    trade.reviewRefund(2, request, true, "重复提交");
    assertEquals(10000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertEquals(10, value("SELECT stock FROM inventory WHERE product_id=1"));
    assertEquals("REFUNDED", trade.get(1, id, false).get("status"));
    assertEquals(1, value("SELECT COUNT(*) FROM wallet_ledger WHERE type='REFUND'"));
  }

  @Test
  void resubmissionInvalidatesOldReviewRequestsWithoutChangingMoneyOrInventory() {
    String order = create(1, 2, "new-return-generation");
    trade.pay(1, order);
    trade.ship(order, "SF-RETURN-NEW");
    String original = (String) trade.requestRefund(1, order, "首次申请").get("id");
    trade.reviewRefund(2, original, false, "请补充退货资料");
    String resubmitted = (String) trade.requestRefund(1, order, "已寄回，请重新验收").get("id");
    assertNotEquals(original, resubmitted);
    assertEquals(resubmitted, trade.requestRefund(1, order, "重复提交").get("id"));
    for (boolean approve : List.of(false, true)) {
      ApiException failure =
          assertThrows(
              ApiException.class, () -> trade.reviewRefund(2, original, approve, "延迟的旧审核请求"));
      assertEquals(404, failure.status);
    }
    var current = (Map<?, ?>) ((List<?>) trade.refunds(1, false).get("items")).getFirst();
    assertEquals("REQUESTED", current.get("status"));
    assertEquals("已寄回，请重新验收", current.get("reason"));
    assertEquals(8000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertEquals(8, value("SELECT stock FROM inventory WHERE product_id=1"));
    assertEquals(2, value("SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundRequested'"));
    trade.reviewRefund(2, resubmitted, true, "新申请已验收");
    assertEquals("REFUNDED", trade.get(1, order, false).get("status"));
    assertEquals(10000, value("SELECT balance_cents FROM users WHERE id=1"));
    assertEquals(1, value("SELECT COUNT(*) FROM wallet_ledger WHERE type='REFUND'"));
  }

  @Test
  void refundListKeepsSingleSnapshotWhenRejectedRequestIsResubmitted() {
    String order = create(1, 2, "return-list-snapshot");
    trade.pay(1, order);
    trade.ship(order, "RETURN-LIST-ONE");
    String original = (String) trade.requestRefund(1, order, "首次退货申请").get("id");
    trade.reviewRefund(2, original, false, "请补充退货资料");
    String otherOrder = create(2, 1, "other-user-return");
    trade.pay(2, otherOrder);
    trade.ship(otherOrder, "RETURN-LIST-TWO");
    trade.requestRefund(2, otherOrder, "其他用户的退货申请");

    var reads = new AtomicInteger();
    String[] replacement = new String[1];
    var interleavingDb =
        new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
          @Override
          public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            var result = super.query(sql, mapper, args);
            if (sql.contains("FROM refund_requests") && reads.incrementAndGet() == 1) {
              // Commit a new application after the first read, before the list can read again.
              replacement[0] = (String) trade.requestRefund(1, order, "已补充资料，重新申请").get("id");
            }
            return result;
          }
        };
    var reader = new TradeService(interleavingDb, ids -> List.of());
    var items = (List<?>) reader.refunds(1, false).get("items");

    assertNotEquals(original, replacement[0]);
    assertEquals(0, value("SELECT COUNT(*) FROM refund_requests WHERE id=?", original));
    assertEquals(1, reads.get(), "Read each refund and its order metadata in one SQL snapshot");
    assertEquals(1, items.size(), "Another user's return must stay private");
    var snapshot = (Map<?, ?>) items.getFirst();
    assertEquals(original, snapshot.get("id"));
    assertEquals("REJECTED", snapshot.get("status"));
    assertEquals("请补充退货资料", snapshot.get("reviewReason"));
    assertEquals(2000L, snapshot.get("totalCents"));
    assertEquals(order, snapshot.get("orderId"));
  }
}
