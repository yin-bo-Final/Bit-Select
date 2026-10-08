package com.bitselect.ai;

import com.bitselect.contracts.ApiException;
import com.bitselect.contracts.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/admin/ops")
public class OpsController {
  private final SessionService sessions;
  private final OpsTraceStore traces;

  public OpsController(SessionService sessions, OpsTraceStore traces) {
    this.sessions = sessions;
    this.traces = traces;
  }

  @GetMapping("/requests")
  public Object requests(
      HttpServletRequest request,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(required = false) Long userId) {
    return admin(request, () -> traces.list(page, pageSize, status, userId));
  }

  @GetMapping("/requests/{id}")
  public Object detail(HttpServletRequest request, @PathVariable String id) {
    return admin(request, () -> traces.detail(id));
  }

  @GetMapping("/summary")
  public Object summary(HttpServletRequest request) {
    return admin(request, traces::summary);
  }

  private Object admin(HttpServletRequest request, Supplier<Object> read) {
    long user = sessions.requireUserId(request);
    try {
      if (!traces.isAdmin(user)) throw new ApiException(403, "FORBIDDEN", "需要管理员权限");
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read.get());
    } catch (DataAccessException unavailable) {
      throw new ApiException(503, "MONITORING_UNAVAILABLE", "监控数据暂不可用，请稍后重试");
    }
  }
}
