package com.bitselect.commerce;

import com.bitselect.contracts.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RefundController {
  private final SessionService sessions;
  private final Users users;
  private final TradeService trade;

  public RefundController(SessionService s, Users u, TradeService t) {
    sessions = s;
    users = u;
    trade = t;
  }

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  record Review(@NotBlank @Size(max = 1000) String reason, boolean goodsReceived) {}

  @PostMapping("/orders/{order}/refund-request")
  Object request(
      HttpServletRequest r, @PathVariable String order, @Valid @RequestBody Reason body) {
    return trade.requestRefund(sessions.requireUserId(r), order, body.reason());
  }

  @GetMapping("/refund-requests")
  Object mine(HttpServletRequest r) {
    return trade.refunds(sessions.requireUserId(r), false);
  }

  @GetMapping("/admin/refund-requests")
  Object all(HttpServletRequest r) {
    long id = sessions.requireUserId(r);
    users.admin(id);
    return trade.refunds(id, true);
  }

  @PostMapping("/admin/refund-requests/{id}/approve")
  Object approve(HttpServletRequest r, @PathVariable String id, @Valid @RequestBody Review body) {
    long admin = sessions.requireUserId(r);
    users.admin(admin);
    if (!body.goodsReceived()) throw ApiException.bad("必须确认退货已验收入库才能退款");
    return trade.reviewRefund(admin, id, true, body.reason());
  }

  @PostMapping("/admin/refund-requests/{id}/reject")
  Object reject(HttpServletRequest r, @PathVariable String id, @Valid @RequestBody Reason body) {
    long admin = sessions.requireUserId(r);
    users.admin(admin);
    return trade.reviewRefund(admin, id, false, body.reason());
  }
}
