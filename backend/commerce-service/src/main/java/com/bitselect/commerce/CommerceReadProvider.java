package com.bitselect.commerce;

import com.bitselect.contracts.*;
import java.util.*;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.jdbc.core.JdbcTemplate;

@DubboService
public class CommerceReadProvider implements CommerceReadRpc {
  private final JdbcTemplate db;
  private final Users users;
  private final TradeService trade;

  public CommerceReadProvider(JdbcTemplate db, Users users, TradeService trade) {
    this.db = db;
    this.users = users;
    this.trade = trade;
  }

  public String userContext(long userId, String intent) {
    var user = users.get(userId);
    return switch (intent) {
      case "profile" -> Json.write(user);
      case "order" -> Json.write(trade.list(userId, false, 1, 10));
      case "wallet" ->
          Json.write(
              Map.of(
                  "balanceCents",
                  user.get("balanceCents"),
                  "ledger",
                  db.query(
                      "SELECT type,amount_cents,balance_after_cents,created_at FROM wallet_ledger"
                          + " WHERE user_id=? ORDER BY id DESC LIMIT 20",
                      (r, n) ->
                          Map.of(
                              "type",
                              r.getString(1),
                              "amountCents",
                              r.getLong(2),
                              "balanceAfterCents",
                              r.getLong(3),
                              "createdAt",
                              r.getTimestamp(4).toInstant()),
                      userId)));
      default -> throw new IllegalArgumentException("Unsupported commerce context intent");
    };
  }
}
