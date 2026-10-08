package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GraphRequestCancellationTest {
  @Test
  void graphNodeOnAnotherThreadUsesSameCancellationControlAndClearsThreadContext()
      throws Exception {
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var accepted = new CountDownLatch(1);
    var releaseServer = new CountDownLatch(1);
    try (var serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        var graphThread = Executors.newSingleThreadExecutor()) {
      upstream.setExecutor(serverThreads);
      upstream.createContext(
          "/chat/completions",
          exchange -> {
            try {
              exchange.getRequestBody().readAllBytes();
              accepted.countDown();
              releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      upstream.start();
      var mapper = new ObjectMapper();
      var model =
          new SiliconFlowClient(
              mapper,
              "http://127.0.0.1:" + upstream.getAddress().getPort(),
              "fake-test-key",
              "chat",
              "embed",
              "rerank",
              20);
      var workflow =
          new AiWorkflow(
              model,
              mock(ConversationStore.class),
              mock(MemoryService.class),
              mock(RetrievalService.class),
              mapper);
      var control = model.newRequestControl();
      NodeAction action =
          state ->
              Map.of(
                  "answer",
                  model.complete(List.of(Map.of("role", "user", "content", "hello")), 20));
      AsyncNodeAction node =
          ReflectionTestUtils.invokeMethod(workflow, "requestNode", control, action);
      Future<?> pending = graphThread.submit(() -> node.apply(new OverAllState()).join());
      assertTrue(accepted.await(3, TimeUnit.SECONDS));
      control.cancel();
      assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
      assertTrue(
          graphThread
              .submit(
                  () ->
                      model.currentRequestControl() == null
                          && !Thread.currentThread().isInterrupted())
              .get(3, TimeUnit.SECONDS));
      releaseServer.countDown();
    } finally {
      releaseServer.countDown();
      upstream.stop(0);
    }
  }
}
