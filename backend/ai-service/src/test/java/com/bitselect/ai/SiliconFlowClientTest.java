package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class SiliconFlowClientTest {
  HttpServer server;
  ExecutorService serverThreads;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverThreads = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(serverThreads);
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
    serverThreads.shutdownNow();
  }

  SiliconFlowClient client(int seconds) {
    return new SiliconFlowClient(
        new ObjectMapper(),
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        "chat",
        "embed",
        "rerank",
        seconds);
  }

  List<Map<String, String>> messages() {
    return List.of(Map.of("role", "user", "content", "hello"));
  }

  void handler(String body, boolean stall) {
    server.createContext(
        "/chat/completions",
        e -> {
          try {
            e.getRequestBody().readAllBytes();
            e.getResponseHeaders().set("Content-Type", "text/event-stream");
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            e.getResponseBody().flush();
            if (stall) Thread.sleep(10000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            e.close();
          }
        });
    server.start();
  }

  String token() {
    return "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";
  }

  @Test
  void completedStreamReturnsAnswer() throws Exception {
    handler(token() + "data: [DONE]\n\n", false);
    assertEquals("hello", client(5).stream(messages(), s -> {}));
  }

  @Test
  void blankCompletionCannotSilentlyReplaceConversationSummary() {
    handler(
        "{\"choices\":[{\"message\":{\"content\":\"   \"},\"finish_reason\":\"stop\"}]}", false);
    var error =
        assertThrows(IllegalStateException.class, () -> client(5).complete(messages(), 1200));
    assertEquals("MODEL_EMPTY_RESPONSE", error.getMessage());
  }

  @Test
  void truncatedCompletionCannotBeAcceptedAsACompleteSummary() {
    handler(
        "{\"choices\":[{\"message\":{\"content\":\"unfinished"
            + " summary\"},\"finish_reason\":\"length\"}]}",
        false);
    var error =
        assertThrows(IllegalStateException.class, () -> client(5).complete(messages(), 1200));
    assertEquals("MODEL_RESPONSE_LIMIT", error.getMessage());
  }

  @Test
  void incompleteStreamIsRejectedInsteadOfPersisted() {
    handler(token(), false);
    assertThrows(IllegalStateException.class, () -> client(5).stream(messages(), s -> {}));
  }

  @Test
  void finishReasonWithoutDoneDoesNotHideTransportTruncation() {
    handler(token() + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n", false);
    assertThrows(IllegalStateException.class, () -> client(5).stream(messages(), s -> {}));
  }

  @Test
  void outputLimitIsNotACompleteAnswerEvenWithDone() {
    handler(
        token()
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
            + "data: [DONE]\n\n",
        false);
    var error =
        assertThrows(IllegalStateException.class, () -> client(5).stream(messages(), s -> {}));
    assertEquals("MODEL_RESPONSE_LIMIT", error.getMessage());
  }

  @Test
  void stalledStreamClosesAndReleasesThreadAtDeadline() throws Exception {
    handler(token(), true);
    var c = client(1);
    long start = System.nanoTime();
    try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> result = worker.submit(() -> c.stream(messages(), s -> {}));
      assertThrows(ExecutionException.class, () -> result.get(4, TimeUnit.SECONDS));
    }
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4000);
  }

  @Test
  void explicitCancellationClosesActiveStream() throws Exception {
    handler(token(), true);
    var c = client(10);
    var control = c.newRequestControl();
    CountDownLatch received = new CountDownLatch(1);
    try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> result =
          worker.submit(
              () -> {
                c.bindRequest(control);
                try {
                  return c.stream(messages(), s -> received.countDown());
                } finally {
                  c.clearRequest();
                }
              });
      assertTrue(received.await(3, TimeUnit.SECONDS));
      control.cancel();
      assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
    }
  }
}
