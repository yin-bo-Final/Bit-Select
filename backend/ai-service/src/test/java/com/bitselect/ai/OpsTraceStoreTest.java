package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.bitselect.contracts.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class OpsTraceStoreTest {
  JdbcTemplate db;
  OpsTraceStore traces;
  MutableClock clock;

  @BeforeEach
  void setup() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__ai_request_traces.sql"))
        .execute(source);
    db = new JdbcTemplate(source);
    db.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,role VARCHAR(16))");
    db.update("INSERT INTO users VALUES(1,'ADMIN'),(2,'USER')");
    clock = new MutableClock();
    traces =
        new OpsTraceStore(
            db, new DataSourceTransactionManager(source), clock, Duration.ofSeconds(30));
  }

  @AfterEach
  void cleanup() {
    traces.close();
  }

  void flushed() throws Exception {
    assertTrue(traces.awaitWrites(Duration.ofSeconds(3)));
  }

  void phase(OpsTraceStore.Trace trace, String code, String status) {
    trace.event(
        "phase", Map.of("code", code, "status", status, "label", "untrusted label ignored"));
  }

  @Test
  void actualPhasesAndFirstTokenPersistWithoutPerTokenWritesOrInventedHistory() throws Exception {
    assertEquals(0L, traces.summary().get("total"));
    var trace = traces.begin(2, UUID.randomUUID().toString(), "hello");
    phase(trace, "rewrite", "running");
    clock.advance(100);
    phase(trace, "rewrite", "completed");
    phase(trace, "compose", "running");
    clock.advance(200);
    trace.event("delta", Map.of("content", "😀你"));
    flushed();
    assertEquals(
        300L,
        db.queryForObject(
            "SELECT first_token_ms FROM ai_request_trace WHERE id=?", Long.class, trace.id()));
    assertEquals(
        0L,
        db.queryForObject(
            "SELECT output_chars FROM ai_request_trace WHERE id=?", Long.class, trace.id()));
    trace.event("delta", Map.of("content", "好"));
    clock.advance(400);
    phase(trace, "compose", "completed");
    trace.finish("completed", null);
    trace.finish("failed", "AI_FAILED");
    flushed();
    var detail = traces.detail(trace.id());
    assertEquals("completed", detail.get("status"));
    assertEquals(700L, detail.get("totalMs"));
    assertEquals(300L, detail.get("firstTokenMs"));
    assertEquals(3L, detail.get("outputChars"));
    assertEquals(true, detail.get("outputCharsFinal"));
    assertNull(detail.get("errorCode"));
    @SuppressWarnings("unchecked")
    var steps = (List<OpsTraceStore.StepView>) detail.get("steps");
    assertEquals(
        List.of("rewrite", "compose"), steps.stream().map(OpsTraceStore.StepView::code).toList());
    assertEquals(100L, steps.getFirst().durationMs());
    assertEquals(600L, steps.getLast().durationMs());
    assertEquals(1L, traces.list(1, 10, "completed", 2L).total());
    assertEquals(0L, traces.list(1, 10, "completed", 1L).total());
    assertEquals(1L, traces.summary().get("completed"));
  }

  @Test
  void failuresAndCancellationsCloseOnlyThePhaseThatActuallyStarted() throws Exception {
    for (String state : List.of("failed", "cancelled")) {
      var trace = traces.begin(2, UUID.randomUUID().toString(), "question");
      phase(trace, "rewrite", "running");
      trace.event("phase", Map.of("code", "invented", "status", "running"));
      clock.advance(250);
      trace.finish(state, state.equals("failed") ? "AI_TIMEOUT" : "CLIENT_CANCELLED");
      trace.event("delta", Map.of("content", "late"));
      flushed();
      var detail = traces.detail(trace.id());
      assertEquals(state, detail.get("status"));
      assertNull(detail.get("firstTokenMs"));
      assertEquals(0L, detail.get("outputChars"));
      @SuppressWarnings("unchecked")
      var steps = (List<OpsTraceStore.StepView>) detail.get("steps");
      assertEquals(1, steps.size());
      assertEquals(state, steps.getFirst().status());
      assertEquals(250L, steps.getFirst().durationMs());
    }
    assertEquals(2L, traces.summary().get("total"));
    assertEquals(1L, traces.summary().get("failed"));
    assertEquals(1L, traces.summary().get("cancelled"));
  }

  @Test
  void abandonedRunningRemainsRunningAndIsMarkedStaleWithoutFakeCompletion() throws Exception {
    var trace = traces.begin(2, UUID.randomUUID().toString(), "question");
    flushed();
    clock.advance(31000);
    var detail = traces.detail(trace.id());
    assertEquals("running", detail.get("status"));
    assertEquals(true, detail.get("stale"));
    assertEquals(false, detail.get("outputCharsFinal"));
    assertNull(detail.get("finishedAt"));
    assertNull(detail.get("firstTokenMs"));
    assertEquals(List.of(), detail.get("steps"));
    assertEquals(1L, traces.summary().get("stale"));
  }

  @Test
  void readLimitsAndSafePreviewAreEnforced() throws Exception {
    var preview =
        OpsTraceStore.preview(
            "api_key=synthetic-value password=example-value\n" + "😀".repeat(150));
    assertFalse(preview.contains("synthetic-value"));
    assertFalse(preview.contains("example-value"));
    assertEquals(120, preview.codePointCount(0, preview.length()));
    assertTrue(traces.isAdmin(1));
    assertFalse(traces.isAdmin(2));
    assertThrows(ApiException.class, () -> traces.list(0, 10, "all", null));
    assertThrows(ApiException.class, () -> traces.list(1, 51, "all", null));
    assertThrows(ApiException.class, () -> traces.list(1, 10, "invalid", null));
    assertThrows(ApiException.class, () -> traces.detail(UUID.randomUUID().toString()));
    var trace = traces.begin(2, UUID.randomUUID().toString(), preview);
    phase(trace, "rewrite", "running");
    trace.finish("failed", "sensitive internal error");
    flushed();
    assertEquals("AI_FAILED", traces.detail(trace.id()).get("errorCode"));
  }

  @Test
  void brokenMonitoringDatabaseDoesNotThrowIntoGeneration() throws Exception {
    db.execute("DROP TABLE ai_request_phase");
    db.execute("DROP TABLE ai_request_trace");
    assertDoesNotThrow(
        () -> {
          var trace = traces.begin(2, UUID.randomUUID().toString(), "question");
          phase(trace, "rewrite", "running");
          trace.event("delta", Map.of("content", "hello"));
          trace.finish("completed", null);
        });
    flushed();
  }

  static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(long millis) {
      now = now.plusMillis(millis);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
