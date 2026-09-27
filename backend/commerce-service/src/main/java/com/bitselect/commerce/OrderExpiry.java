package com.bitselect.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiry {
  private final JdbcTemplate db;
  private final TradeService trade;

  public OrderExpiry(JdbcTemplate d, TradeService t) {
    db = d;
    trade = t;
  }

  @Scheduled(fixedDelay = 30000)
  public void expire() {
    for (var row :
        db.queryForList(
            "SELECT id,user_id FROM orders WHERE status='PENDING_PAYMENT' AND"
                + " expires_at<CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 100"))
      try {
        trade.expire(((Number) row.get("user_id")).longValue(), (String) row.get("id"));
      } catch (org.springframework.dao.TransientDataAccessException e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Order expiry will retry after transient database error");
      }
  }
}
