package com.bitselect.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bitselect.contracts.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsControllerTest {
  SessionService sessions;
  OpsTraceStore traces;
  MockMvc mvc;
  final List<String> paths =
      List.of(
          "/api/ai/admin/ops/requests?page=0",
          "/api/ai/admin/ops/requests/" + UUID.randomUUID(),
          "/api/ai/admin/ops/summary");

  @BeforeEach
  void setup() {
    sessions = mock(SessionService.class);
    traces = mock(OpsTraceStore.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new OpsController(sessions, traces))
            .setControllerAdvice(new ApiErrors())
            .build();
  }

  @Test
  void allEndpointsRejectAnonymousBeforeReadingAnyMonitoringData() throws Exception {
    when(sessions.requireUserId(any())).thenThrow(new ApiException(401, "UNAUTHORIZED", "请先登录"));
    for (String path : paths) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    verifyNoInteractions(traces);
  }

  @Test
  void allEndpointsRejectUserEvenForInvalidPagesAndMissingTraceIds() throws Exception {
    when(sessions.requireUserId(any())).thenReturn(2L);
    when(traces.isAdmin(2L)).thenReturn(false);
    for (String path : paths) mvc.perform(get(path)).andExpect(status().isForbidden());
    verify(traces, times(3)).isAdmin(2L);
    verifyNoMoreInteractions(traces);
  }

  @Test
  void administratorsCanReadButRoleLookupFailureIsClosedAndSanitized() throws Exception {
    when(sessions.requireUserId(any())).thenReturn(1L);
    when(traces.isAdmin(1L)).thenReturn(true);
    when(traces.summary()).thenReturn(Map.of("total", 0L));
    when(traces.list(1, 10, "all", null)).thenReturn(new OpsTraceStore.Page(List.of(), 0, 1, 10));
    when(traces.detail(anyString())).thenReturn(Map.of("status", "completed"));
    mvc.perform(get("/api/ai/admin/ops/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/ai/admin/ops/requests"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    mvc.perform(get(paths.get(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"));
    when(traces.isAdmin(1L))
        .thenThrow(new DataAccessResourceFailureException("private database detail"));
    mvc.perform(get("/api/ai/admin/ops/summary"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MONITORING_UNAVAILABLE"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private database detail"))));
  }
}
