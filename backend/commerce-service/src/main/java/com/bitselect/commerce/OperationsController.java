package com.bitselect.commerce;

import com.bitselect.contracts.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ops")
public class OperationsController {
  private final SessionService sessions;
  private final Users users;
  private final OperationsService operations;

  public OperationsController(SessionService sessions, Users users, OperationsService operations) {
    this.sessions = sessions;
    this.users = users;
    this.operations = operations;
  }

  private void requireAdmin(HttpServletRequest request) {
    users.admin(sessions.requireUserId(request));
  }

  @GetMapping("/overview")
  public ResponseEntity<?> overview(HttpServletRequest request) {
    requireAdmin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.overview());
  }

  @GetMapping("/events")
  public ResponseEntity<?> events(
      HttpServletRequest request,
      @RequestParam(defaultValue = "commerce") String kind,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    requireAdmin(request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(operations.events(kind, status, page, pageSize));
  }

  @GetMapping("/events/{kind}/{id}")
  public ResponseEntity<?> event(
      HttpServletRequest request, @PathVariable String kind, @PathVariable String id) {
    requireAdmin(request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(operations.event(kind, id));
  }
}
