package com.bitselect.commerce;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bitselect.contracts.ApiErrors;
import com.bitselect.contracts.ApiException;
import com.bitselect.contracts.SessionService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperationsControllerTest {
  SessionService sessions = mock(SessionService.class);
  Users users = mock(Users.class);
  OperationsService operations = mock(OperationsService.class);
  MockMvc mvc;
  String[] endpoints = {
    "/api/admin/ops/overview",
    "/api/admin/ops/events",
    "/api/admin/ops/events/memory/00000000-0000-0000-0000-000000000001"
  };

  @BeforeEach
  void setup() {
    mvc =
        MockMvcBuilders.standaloneSetup(new OperationsController(sessions, users, operations))
            .setControllerAdvice(new ApiErrors())
            .build();
  }

  @Test
  void everyEndpointRejectsAnonymousAndIgnoresForgedRoleHeaders() throws Exception {
    when(sessions.requireUserId(any())).thenThrow(new ApiException(401, "UNAUTHORIZED", "请登录"));
    for (String endpoint : endpoints)
      mvc.perform(get(endpoint).header("X-User-Role", "ADMIN").header("X-User-Id", "1"))
          .andExpect(status().isUnauthorized());
    verifyNoInteractions(users, operations);
  }

  @Test
  void everyEndpointChecksCurrentDatabaseRoleBeforeAnyOperationsRead() throws Exception {
    when(sessions.requireUserId(any())).thenReturn(12L);
    doThrow(new ApiException(403, "FORBIDDEN", "需要管理员权限")).when(users).admin(12L);
    for (String endpoint : endpoints)
      mvc.perform(get(endpoint).header("X-User-Role", "ADMIN")).andExpect(status().isForbidden());
    verify(users, times(3)).admin(12L);
    verifyNoInteractions(operations);
  }

  @Test
  void authorizedRequestsPreserveFiltersAndDisableResponseCaching() throws Exception {
    when(sessions.requireUserId(any())).thenReturn(7L);
    when(operations.overview()).thenReturn(Map.of("sampledAt", "2025-01-01T00:00:00Z"));
    when(operations.events("memory", "processing", 2, 50)).thenReturn(Map.of("total", 1));
    mvc.perform(get(endpoints[0]))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get(endpoints[1])
                .param("kind", "memory")
                .param("status", "processing")
                .param("page", "2")
                .param("pageSize", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get(endpoints[2]))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(users, times(3)).admin(7L);
    verify(operations).events("memory", "processing", 2, 50);
    verify(operations).event("memory", "00000000-0000-0000-0000-000000000001");
  }
}
