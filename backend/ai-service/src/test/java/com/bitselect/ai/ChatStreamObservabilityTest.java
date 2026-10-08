package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatStreamObservabilityTest {
  @Test
  void phaseAndTokenReachTraceAndOnlyTheFirstTerminalStateWins() {
    var trace = mock(OpsTraceStore.Trace.class);
    var control = new SiliconFlowClient.RequestControl();
    var stream =
        new ChatStream(
            control, Duration.ofSeconds(30), Duration.ofSeconds(30), mock(SseEmitter.class), trace);
    var phase = Map.of("code", "rewrite", "status", "running");
    var delta = Map.of("content", "你好");
    stream.send("phase", phase);
    stream.send("delta", delta);
    stream.fail("AI_TIMEOUT", "timeout");
    stream.cancel();
    stream.succeed("conversation");
    verify(trace).event("phase", phase);
    verify(trace).event("delta", delta);
    verify(trace).finish("failed", "AI_TIMEOUT");
    verifyNoMoreInteractions(trace);
    assertThrows(CancellationException.class, control::check);
  }

  @Test
  void monitoringFailuresCannotBreakTheResponseOrPreventCancellation() {
    var trace = mock(OpsTraceStore.Trace.class);
    doThrow(new IllegalStateException("monitor unavailable")).when(trace).event(anyString(), any());
    doThrow(new IllegalStateException("monitor unavailable"))
        .when(trace)
        .finish(anyString(), any());
    var control = new SiliconFlowClient.RequestControl();
    var emitter = mock(SseEmitter.class);
    var stream =
        new ChatStream(control, Duration.ofSeconds(30), Duration.ofSeconds(30), emitter, trace);
    assertDoesNotThrow(() -> stream.send("delta", Map.of("content", "still streaming")));
    assertDoesNotThrow(stream::cancel);
    assertThrows(CancellationException.class, control::check);
  }
}
