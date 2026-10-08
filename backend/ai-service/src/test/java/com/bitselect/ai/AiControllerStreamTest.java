package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bitselect.contracts.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.AsyncEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiControllerStreamTest {
  HttpServer upstream;
  ExecutorService upstreamThreads;
  CountDownLatch allowFinish;
  CountDownLatch deltaReceived;
  AiController controller;
  StringRedisTemplate redis;
  MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstreamThreads = Executors.newVirtualThreadPerTaskExecutor();
    upstream.setExecutor(upstreamThreads);
    allowFinish = new CountDownLatch(1);
    deltaReceived = new CountDownLatch(1);
    var model =
        new SiliconFlowClient(
            new ObjectMapper(),
            "http://127.0.0.1:" + upstream.getAddress().getPort(),
            "fake-test-key",
            "chat",
            "embed",
            "rerank",
            20);
    var sessions = mock(SessionService.class);
    when(sessions.requireUserId(any())).thenReturn(7L);
    var store = mock(ConversationStore.class);
    when(store.ensure(eq(7L), any(), anyString())).thenReturn("owned-conversation");
    redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    var workflow = mock(AiWorkflow.class);
    var traces = mock(OpsTraceStore.class);
    when(traces.begin(anyLong(), anyString(), anyString())).thenReturn(OpsTraceStore.NOOP);
    when(workflow.run(eq(7L), eq("owned-conversation"), anyString(), any()))
        .thenAnswer(
            call -> {
              BiConsumer<String, Object> send = call.getArgument(3);
              return model.stream(
                  List.of(Map.of("role", "user", "content", "hello")),
                  text -> {
                    send.accept("delta", Map.of("content", text));
                    deltaReceived.countDown();
                  });
            });
    controller =
        new AiController(
            sessions,
            store,
            mock(MemoryService.class),
            mock(KnowledgeService.class),
            workflow,
            redis,
            model, traces);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @AfterEach
  void cleanup() {
    allowFinish.countDown();
    upstream.stop(0);
    upstreamThreads.shutdownNow();
  }

  void startUpstream(boolean complete) {
    upstream.createContext(
        "/chat/completions",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange
                .getResponseBody()
                .write(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"first-token\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            allowFinish.await(15, TimeUnit.SECONDS);
            if (complete) {
              exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
              exchange.getResponseBody().flush();
            }
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } catch (IOException ignored) {
            // Expected when cancellation closes the upstream socket.
          } finally {
            exchange.close();
          }
        });
    upstream.start();
  }

  MvcResult startChat() throws Exception {
    return mvc.perform(
            post("/api/ai/chat")
                .contentType("application/json")
                .accept("text/event-stream")
                .content("{\"message\":\"hello\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  void assertResourcesReleased() {
    verify(redis, timeout(3000))
        .execute(
            any(RedisScript.class),
            eq(List.of("bit:ai:lock:owned-conversation")),
            any(Object[].class));
    var capacity = (Semaphore) ReflectionTestUtils.getField(controller, "capacity");
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          while (capacity.availablePermits() != 3) Thread.sleep(10);
        });
  }

  @Test
  void firstDeltaIsWrittenBeforeUpstreamFinishesWithUnbufferedHeaders() throws Exception {
    startUpstream(true);
    var result = startChat();
    assertTrue(deltaReceived.await(3, TimeUnit.SECONDS));
    assertTrue(result.getResponse().getContentAsString().contains("first-token"));
    assertFalse(result.getResponse().getContentAsString().contains("event:done"));
    assertEquals(1, allowFinish.getCount());
    assertEquals(
        "no-cache, no-store, no-transform", result.getResponse().getHeader("Cache-Control"));
    assertEquals("no", result.getResponse().getHeader("X-Accel-Buffering"));
    allowFinish.countDown();
    result.getAsyncResult(3000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    assertTrue(result.getResponse().getContentAsString().contains("event:done"));
    assertFalse(result.getResponse().getContentAsString().contains("event:error"));
    assertResourcesReleased();
  }

  @Test
  void truncatedAnswerHasOneErrorAndNoDone() throws Exception {
    startUpstream(false);
    var result = startChat();
    assertTrue(deltaReceived.await(3, TimeUnit.SECONDS));
    allowFinish.countDown();
    result.getAsyncResult(3000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"partial\":true"));
    assertEquals(1, body.split("event:error", -1).length - 1);
    assertFalse(body.contains("event:done"));
    assertResourcesReleased();
  }

  @Test
  void servletDisconnectCancelsBlockedUpstreamAndReleasesLockAndPermit() throws Exception {
    startUpstream(true);
    var result = startChat();
    assertTrue(deltaReceived.await(3, TimeUnit.SECONDS));
    var context = (MockAsyncContext) result.getRequest().getAsyncContext();
    for (var listener : context.getListeners())
      listener.onError(new AsyncEvent(context, new IOException("simulated client disconnect")));
    assertResourcesReleased();
    assertEquals(
        1, allowFinish.getCount(), "The worker must stop without waiting for upstream completion");
    assertFalse(result.getResponse().getContentAsString().contains("event:done"));
  }

  @Test
  void heartbeatKeepsConnectionAliveAndRequestDeadlineEndsWithExplicitError() throws Exception {
    ReflectionTestUtils.setField(controller, "heartbeatSeconds", 1L);
    ReflectionTestUtils.setField(controller, "requestTimeoutSeconds", 2L);
    startUpstream(true);
    var result = startChat();
    assertTrue(deltaReceived.await(3, TimeUnit.SECONDS));
    result.getAsyncResult(4000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains(":keep-alive"));
    assertTrue(body.contains("\"code\":\"AI_TIMEOUT\""));
    assertFalse(body.contains("event:done"));
    assertResourcesReleased();
  }
}
