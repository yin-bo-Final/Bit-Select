package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class MemoryRecallCancellationTest {
  private static final long USER = 7L;
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final MilvusStore vectors = mock(MilvusStore.class);
  private final Driver graph = mock(Driver.class);
  private final Session session = mock(Session.class);
  private final Result records = mock(Result.class);
  private final SiliconFlowClient model =
      spy(
          new SiliconFlowClient(
              new ObjectMapper(),
              "http://127.0.0.1:1",
              "synthetic-test-key",
              "chat",
              "embed",
              "rerank",
              20));
  private SiliconFlowClient.RequestControl control;
  private MemoryService service;
  private List<String> latest = List.of("one");

  @BeforeEach
  void setUp() throws Exception {
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.contains("ai_memory_preference")) return List.of(Map.of("enabled", true));
              if (sql.startsWith("SELECT id FROM ai_memory"))
                return latest.stream().map(id -> Map.<String, Object>of("id", id)).toList();
              if (sql.contains("ai_memory m WHERE")) {
                String id = call.getArgument(1);
                return List.of(
                    Map.<String, Object>of(
                        "id",
                        id,
                        "entity",
                        id,
                        "attribute_name",
                        "preference",
                        "content",
                        "A remembered preference",
                        "sourceOrder",
                        1L));
              }
              throw new AssertionError("Unexpected SQL in recall test");
            });
    doReturn(List.of(List.of(0.5f, 0.5f))).when(model).embed(anyList());
    doReturn(List.of()).when(vectors).search(anyString(), anyList(), anyString(), anyInt());
    when(graph.session()).thenReturn(session);
    when(session.run(anyString(), anyMap())).thenReturn(records);
    service =
        new MemoryService(
            db,
            new TransactionTemplate(),
            model,
            vectors,
            "127.0.0.1:1",
            "bolt://127.0.0.1:1",
            "test",
            "test",
            true);
    ((Driver) Objects.requireNonNull(ReflectionTestUtils.getField(service, "graph"))).close();
    ReflectionTestUtils.setField(service, "graph", graph);
    control = model.newRequestControl();
    model.bindRequest(control);
  }

  @AfterEach
  void tearDown() {
    model.clearRequest();
    Thread.interrupted();
    if (service != null) service.shutdown();
  }

  private void cancelAndClearInterruptedFlag() {
    control.cancel();
    // HttpClient.send throws InterruptedException after clearing the interrupt flag.
    assertTrue(Thread.interrupted());
    assertFalse(Thread.currentThread().isInterrupted());
  }

  private void assertNoMemorySql() {
    verify(db, never()).queryForList(startsWith("SELECT id FROM ai_memory"), eq(USER));
    verify(db, never()).queryForList(contains("ai_memory m WHERE"), anyString(), eq(USER));
  }

  @Test
  void alreadyCancelledRequestDoesNotEvenReadPreferences() {
    cancelAndClearInterruptedFlag();
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verifyNoInteractions(db, vectors, graph);
  }

  @Test
  void interruptedEmbeddingDoesNotContinueIntoProjectionOrSqlFallback() throws Exception {
    doAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              throw new InterruptedException("cancelled embedding HTTP call");
            })
        .when(model)
        .embed(anyList());
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verifyNoInteractions(vectors, graph);
    assertNoMemorySql();
  }

  @Test
  void cancellationAfterEmbeddingReturnsStopsBeforeVectorSearch() throws Exception {
    doAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              return List.of(List.of(0.5f, 0.5f));
            })
        .when(model)
        .embed(anyList());
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verifyNoInteractions(vectors, graph);
    assertNoMemorySql();
  }

  @Test
  void interruptedVectorSearchStopsFallbackAndNewRequestControlCanRecallNormally()
      throws Exception {
    when(vectors.search(anyString(), anyList(), anyString(), anyInt()))
        .thenAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              throw new InterruptedException("cancelled vector HTTP call");
            });
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verifyNoInteractions(graph);
    assertNoMemorySql();
    model.clearRequest();
    Thread.interrupted();
    model.bindRequest(model.newRequestControl());
    doReturn(List.of()).when(vectors).search(anyString(), anyList(), anyString(), anyInt());
    assertEquals("one", service.recall(USER, "headphones").getFirst().get("id"));
    verify(graph).session();
  }

  @Test
  void cancellationAfterVectorSearchReturnsDoesNotResolveHitsInSql() throws Exception {
    when(vectors.search(anyString(), anyList(), anyString(), anyInt()))
        .thenAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              return List.of(Map.of("memoryId", "one"));
            });
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verifyNoInteractions(graph);
    assertNoMemorySql();
  }

  @Test
  void cancelledGraphFailureDoesNotContinueToSqlFallback() {
    when(session.run(anyString(), anyMap()))
        .thenAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              throw new IllegalStateException("graph call cancelled", new InterruptedException());
            });
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verify(session).close();
    assertNoMemorySql();
  }

  @Test
  void cancellationDuringFallbackQueryDoesNotResolveItsRows() {
    doAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              return List.of(Map.of("id", "one"));
            })
        .when(db)
        .queryForList(startsWith("SELECT id FROM ai_memory"), eq(USER));
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verify(db, never()).queryForList(contains("ai_memory m WHERE"), anyString(), eq(USER));
  }

  @Test
  void cancellationDuringCandidateSqlDoesNotContinueWithGraphOrRecentFallback() throws Exception {
    doReturn(List.of(Map.of("memoryId", "one")))
        .when(vectors)
        .search(anyString(), anyList(), anyString(), anyInt());
    doAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              return List.of(Map.of("id", "one"));
            })
        .when(db)
        .queryForList(contains("ai_memory m WHERE"), eq("one"), eq(USER));
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    verifyNoInteractions(graph);
    verify(db, never()).queryForList(startsWith("SELECT id FROM ai_memory"), eq(USER));
  }

  @Test
  void cancellationDuringGraphCursorEvenWithNoRowsStopsSqlFallback() {
    when(records.hasNext())
        .thenAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              return false;
            });
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
    assertNoMemorySql();
  }

  @Test
  void explicitProjectionCancellationCannotBeTreatedAsAnOrdinaryFailure() throws Exception {
    var cancelled = new CancellationException("projection request cancelled");
    doThrow(cancelled).when(vectors).search(anyString(), anyList(), anyString(), anyInt());
    assertSame(
        cancelled,
        assertThrows(CancellationException.class, () -> service.recall(USER, "headphones")));
    verifyNoInteractions(graph);
    assertNoMemorySql();
  }

  @Test
  void interruptedConsistencyModelDoesNotReturnAStaleFallbackAnswer() throws Exception {
    latest = List.of("one", "two");
    doAnswer(
            call -> {
              cancelAndClearInterruptedFlag();
              throw new InterruptedException("cancelled consistency model HTTP call");
            })
        .when(model)
        .complete(anyList(), anyInt());
    assertThrows(CancellationException.class, () -> service.recall(USER, "headphones"));
  }

  @Test
  void interruptedCallWithoutCancelledControlStillPropagatesAndRestoresInterrupt()
      throws Exception {
    doThrow(new InterruptedException("interrupted embedding HTTP call"))
        .when(model)
        .embed(anyList());
    assertThrows(InterruptedException.class, () -> service.recall(USER, "headphones"));
    assertTrue(Thread.currentThread().isInterrupted());
    verifyNoInteractions(vectors, graph);
    assertNoMemorySql();
  }

  @Test
  void ordinaryProjectionAndConsistencyFailuresStillUseAuthoritativeSqlFallback() throws Exception {
    latest = List.of("one", "two");
    when(vectors.search(anyString(), anyList(), anyString(), anyInt()))
        .thenThrow(new IllegalStateException("projection unavailable"));
    when(graph.session()).thenThrow(new IllegalStateException("graph unavailable"));
    doThrow(new IllegalStateException("model temporarily unavailable"))
        .when(model)
        .complete(anyList(), anyInt());
    assertEquals(
        List.of("one"), service.recall(USER, "headphones").stream().map(m -> m.get("id")).toList());
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  void ordinaryEmbeddingFailureStillAllowsGraphAndSqlFallback() throws Exception {
    doThrow(new IllegalStateException("embedding service unavailable"))
        .when(model)
        .embed(anyList());
    assertEquals("one", service.recall(USER, "headphones").getFirst().get("id"));
    verifyNoInteractions(vectors);
    verify(graph).session();
  }
}
