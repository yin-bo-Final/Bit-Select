package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bitselect.contracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiRequestCancellationTest {
  final ObjectMapper json = new ObjectMapper();
  final ConcurrentHashMap<String, String> locks = new ConcurrentHashMap<>();
  final CountDownLatch retrieveStarted = new CountDownLatch(1);
  final CountDownLatch generationInterrupted = new CountDownLatch(1);
  final CountDownLatch releaseGeneration = new CountDownLatch(1);
  final CountDownLatch releaseCleanup = new CountDownLatch(1);
  final AtomicBoolean blockCleanup = new AtomicBoolean();
  final AtomicBoolean blockPersistence = new AtomicBoolean();
  final AtomicReference<SiliconFlowClient.RequestControl> requestControl = new AtomicReference<>();
  final AtomicInteger runs = new AtomicInteger();
  AiController controller;
  ConversationStore conversations;
  SessionService sessions;
  OpsTraceStore traces;
  MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    sessions = mock(SessionService.class);
    when(sessions.requireUserId(any()))
        .thenAnswer(
            call -> {
              var request = (jakarta.servlet.http.HttpServletRequest) call.getArgument(0);
              if (request.getHeader("X-Test-User") == null)
                throw new ApiException(401, "UNAUTHORIZED", "请先登录");
              return Long.valueOf(request.getHeader("X-Test-User"));
            });
    conversations = mock(ConversationStore.class);
    when(conversations.ensure(anyLong(), any(), anyString())).thenReturn("same-conversation");
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenAnswer(call -> locks.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenAnswer(
            call -> {
              if (blockCleanup.get()) awaitUninterruptibly(releaseCleanup);
              var keys = (List<?>) call.getArgument(1);
              var arguments = (Object[]) call.getRawArguments()[2];
              return locks.remove(keys.getFirst().toString(), arguments[0].toString()) ? 1L : 0L;
            });
    var model =
        new SiliconFlowClient(
            json, "http://127.0.0.1:1", "synthetic-only", "chat", "embed", "rerank", 20);
    var workflow = mock(AiWorkflow.class);
    when(workflow.run(anyLong(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              BiConsumer<String, Object> send = call.getArgument(3);
              AiWorkflow.PersistenceBoundary persistence = call.getArgument(4);
              requestControl.set(model.currentRequestControl());
              if (runs.incrementAndGet() == 1) {
                if (blockPersistence.get()) {
                  send.accept("delta", Map.of("content", "complete retry"));
                  persistence.save(
                      () -> {
                        retrieveStarted.countDown();
                        awaitUninterruptibly(releaseGeneration);
                        conversations.save(
                            7L, "same-conversation", "manual", "complete retry", List.of());
                      });
                  return "complete retry";
                }
                send.accept(
                    "phase", Map.of("code", "retrieve", "status", "running", "label", "查找说明书与偏好"));
                retrieveStarted.countDown();
                try {
                  releaseGeneration.await();
                } catch (InterruptedException cancelled) {
                  generationInterrupted.countDown();
                  throw cancelled;
                }
                model.checkRequest();
              }
              send.accept("delta", Map.of("content", "complete retry"));
              persistence.save(
                  () ->
                      conversations.save(
                          call.getArgument(0),
                          call.getArgument(1),
                          call.getArgument(2),
                          "complete retry",
                          List.of()));
              return "complete retry";
            });
    traces = mock(OpsTraceStore.class);
    when(traces.begin(anyLong(), anyString(), anyString())).thenReturn(OpsTraceStore.NOOP);
    controller =
        new AiController(
            sessions,
            conversations,
            mock(MemoryService.class),
            mock(KnowledgeService.class),
            workflow,
            redis,
            model,
            traces);
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrors()).build();
  }

  @AfterEach
  void cleanup() {
    releaseGeneration.countDown();
    releaseCleanup.countDown();
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private MvcResult chat() throws Exception {
    return mvc.perform(
            post("/api/ai/chat")
                .header("X-Test-User", "7")
                .contentType("application/json")
                .accept("text/event-stream")
                .content("{\"conversationId\":\"same-conversation\",\"message\":\"manual\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private String requestId(MvcResult result) throws Exception {
    assertTrue(retrieveStarted.await(3, TimeUnit.SECONDS));
    for (String line : result.getResponse().getContentAsString().split("\\R"))
      if (line.startsWith("data:") && line.contains("requestId"))
        return json.readTree(line.substring(5)).path("requestId").asText();
    throw new AssertionError("No meta requestId");
  }

  private ResultActions cancel(String id, long user) throws Exception {
    return mvc.perform(
        post("/api/ai/requests/{id}/cancel", id)
            .header("X-Test-User", Long.toString(user))
            .accept("application/json"));
  }

  private int permits() {
    return ((Semaphore) ReflectionTestUtils.getField(controller, "capacity")).availablePermits();
  }

  private void retryAfterCleanup() throws Exception {
    var retry = chat();
    retry.getAsyncResult(3000);
    mvc.perform(asyncDispatch(retry)).andExpect(status().isOk());
    assertTrue(retry.getResponse().getContentAsString().contains("event:done"));
    verify(conversations, timeout(3000).times(1))
        .save(eq(7L), eq("same-conversation"), eq("manual"), eq("complete retry"), eq(List.of()));
  }

  @Test
  void ownedCancelAcknowledgesActualCleanupAndImmediateSameConversationRetrySucceeds()
      throws Exception {
    var first = chat();
    String id = requestId(first);
    assertDoesNotThrow(
        () -> UUID.fromString(id), "NOOP monitoring must still provide a usable control ID");
    cancel(id, 7)
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.finished").value(true));
    assertEquals(0, generationInterrupted.getCount());
    assertTrue(locks.isEmpty());
    assertEquals(3, permits());
    cancel(id, 7).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));
    retryAfterCleanup();
  }

  @Test
  void acknowledgementWaitsForCleanupAndReturnsBoundedPendingInsteadOfReleasingResourcesEarly()
      throws Exception {
    blockCleanup.set(true);
    String id = requestId(chat());
    long started = System.nanoTime();
    cancel(id, 7).andExpect(status().isAccepted()).andExpect(jsonPath("$.finished").value(false));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4000);
    assertEquals(0, generationInterrupted.getCount());
    assertFalse(
        locks.isEmpty(), "Owner lock must remain until the worker has actually cleaned it up");
    assertEquals(2, permits(), "Permit cannot be returned by the cancel endpoint");
    releaseCleanup.countDown();
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          while (permits() != 3) Thread.sleep(10);
        });
    cancel(id, 7).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));
    retryAfterCleanup();
  }

  @Test
  void anotherUserCannotCancelActiveRequestAndMonitoringIdIsPreserved() throws Exception {
    var trace = mock(OpsTraceStore.Trace.class);
    String expectedId = UUID.randomUUID().toString();
    when(trace.id()).thenReturn(expectedId);
    when(traces.begin(anyLong(), anyString(), anyString())).thenReturn(trace);
    String id = requestId(chat());
    assertEquals(expectedId, id);
    cancel(id, 9).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertEquals(1, generationInterrupted.getCount());
    assertFalse(locks.isEmpty());
    assertEquals(2, permits());
    cancel(id, 7).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));
    verify(trace).finish("cancelled", "CLIENT_CANCELLED");
    verify(conversations, never()).save(anyLong(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void unknownOrCompletedCancelIsIdempotentButStillRequiresAuthentication() throws Exception {
    String id = UUID.randomUUID().toString();
    cancel(id, 7).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));
    cancel(id, 7).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));
    mvc.perform(post("/api/ai/requests/{id}/cancel", id).accept("application/json"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void cancelDuringAcceptedPersistenceWaitsForItsOutcomeWithoutInterruptingCommit()
      throws Exception {
    blockPersistence.set(true);
    var trace = mock(OpsTraceStore.Trace.class);
    when(trace.id()).thenReturn(UUID.randomUUID().toString());
    when(traces.begin(anyLong(), anyString(), anyString())).thenReturn(trace);
    var first = chat();
    String id = requestId(first);
    cancel(id, 7).andExpect(status().isAccepted()).andExpect(jsonPath("$.finished").value(false));
    assertDoesNotThrow(() -> requestControl.get().check());
    assertEquals(2, permits());
    assertFalse(locks.isEmpty());
    verify(trace, never()).finish(eq("cancelled"), any());
    releaseGeneration.countDown();
    first.getAsyncResult(3000);
    mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
    cancel(id, 7).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));
    assertTrue(first.getResponse().getContentAsString().contains("event:done"));
    verify(trace, times(1)).finish("completed", null);
    verify(trace, never()).finish(eq("cancelled"), any());
    verify(conversations).save(7L, "same-conversation", "manual", "complete retry", List.of());
  }
}
