package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConversationPersistenceTest {
  private static final class Fixture {
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate db = new JdbcTemplate(source);

    Fixture(boolean rejectAssistant) {
      db.execute(
          "CREATE TABLE ai_conversation(id VARCHAR(36) PRIMARY KEY,user_id BIGINT,title"
              + " VARCHAR(100),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,revision BIGINT"
              + " DEFAULT 0)");
      db.execute(
          "CREATE TABLE ai_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,conversation_id"
              + " VARCHAR(36),user_id BIGINT,turn_id VARCHAR(36),role VARCHAR(20),content"
              + " TEXT,sources_json TEXT"
              + (rejectAssistant ? ",CHECK(role <> 'assistant')" : "")
              + ")");
      db.execute(
          "CREATE TABLE ai_memory_preference(user_id BIGINT PRIMARY KEY,enabled BOOLEAN,generation"
              + " BIGINT)");
      db.execute(
          "CREATE TABLE ai_memory_job(id VARCHAR(36),user_id BIGINT,conversation_id"
              + " VARCHAR(36),content TEXT,generation BIGINT)");
    }

    ConversationStore store(DataSourceTransactionManager transactions) {
      return new ConversationStore(
          db,
          mock(SiliconFlowClient.class),
          new TransactionTemplate(transactions),
          new ObjectMapper(),
          262144);
    }
  }

  @Test
  void cancellationBeforePersistenceAdmissionWritesNothing() throws Exception {
    var fixture = new Fixture(false);
    var store = fixture.store(new DataSourceTransactionManager(fixture.source));
    String id = store.ensure(7, null, "question");
    var trace = mock(OpsTraceStore.Trace.class);
    var stream =
        new ChatStream(
            new SiliconFlowClient.RequestControl(),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            mock(SseEmitter.class),
            trace);
    stream.cancel();
    assertThrows(
        CancellationException.class,
        () -> stream.persist(() -> store.save(7, id, "question", "answer", List.of())));
    assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM ai_message", Integer.class));
    verify(trace).finish("cancelled", "CLIENT_CANCELLED");
    verify(trace, never()).finish(eq("completed"), any());
  }

  @Test
  void cancellationAndTimeoutDuringCommitCannotMislabelAnAcceptedCompleteTurn() throws Exception {
    var fixture = new Fixture(false);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var transactions =
        new DataSourceTransactionManager(fixture.source) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            entered.countDown();
            try {
              if (!release.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("test commit stalled");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            super.doCommit(status);
          }
        };
    var store = fixture.store(transactions);
    String id = store.ensure(7, null, "question");
    var control = new SiliconFlowClient.RequestControl();
    var trace = mock(OpsTraceStore.Trace.class);
    var emitter = mock(SseEmitter.class);
    var stream =
        new ChatStream(control, Duration.ofSeconds(30), Duration.ofSeconds(30), emitter, trace);
    stream.send("phase", Map.of("code", "save", "status", "running"));
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var committing =
          threads.submit(
              () -> {
                stream.persist(() -> store.save(7, id, "question", "answer", List.of()));
                return null;
              });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      stream.cancel();
      stream.fail("AI_TIMEOUT", "deadline");
      assertDoesNotThrow(control::check);
      verify(trace, never()).finish(anyString(), any());
      release.countDown();
      committing.get(3, TimeUnit.SECONDS);
      assertEquals(2, fixture.db.queryForObject("SELECT COUNT(*) FROM ai_message", Integer.class));
      assertEquals(
          1, fixture.db.queryForObject("SELECT COUNT(*) FROM ai_memory_job", Integer.class));
      stream.succeed(id);
      verify(trace, times(1)).finish("completed", null);
      verify(trace, never()).finish(eq("cancelled"), any());
      verify(trace, never()).finish(eq("failed"), any());
      verify(emitter).complete();
    } finally {
      release.countDown();
      stream.cancel();
    }
  }

  @Test
  void failedAssistantInsertRollsBackUserAndMemoryJobAndMarksFailure() throws Exception {
    var fixture = new Fixture(true);
    var store = fixture.store(new DataSourceTransactionManager(fixture.source));
    String id = store.ensure(7, null, "question");
    var trace = mock(OpsTraceStore.Trace.class);
    var control = new SiliconFlowClient.RequestControl();
    var stream =
        new ChatStream(
            control, Duration.ofSeconds(30), Duration.ofSeconds(30), mock(SseEmitter.class), trace);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> stream.persist(() -> store.save(7, id, "question", "answer", List.of())));
    assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM ai_message", Integer.class));
    assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM ai_memory_job", Integer.class));
    verify(trace).finish("failed", "AI_FAILED");
    assertThrows(CancellationException.class, control::check);
  }

  @Test
  void failedDoneDeliveryAfterCommitRemainsCompletedExactlyOnce() throws Exception {
    var trace = mock(OpsTraceStore.Trace.class);
    var emitter = mock(SseEmitter.class);
    doThrow(new IOException("closed socket"))
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    var stream =
        new ChatStream(
            new SiliconFlowClient.RequestControl(),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            emitter,
            trace);
    stream.persist(() -> {});
    assertDoesNotThrow(() -> stream.send("phase", Map.of("code", "save", "status", "completed")));
    stream.succeed("conversation");
    stream.cancel();
    stream.fail("AI_FAILED", "late failure");
    verify(trace, times(1)).finish("completed", null);
    verify(trace, never()).finish(eq("failed"), any());
    verify(trace, never()).finish(eq("cancelled"), any());
  }
}
