package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatStreamConcurrencyTest {
  @Test
  void timeoutCancelsImmediatelyButItsTerminalFrameWaitsForTheInflightDelta() throws Exception {
    var emitter = new ControlledEmitter();
    var control = new SiliconFlowClient.RequestControl();
    var stream = new ChatStream(control, Duration.ofSeconds(30), Duration.ofSeconds(30), emitter);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var delta = threads.submit(() -> stream.send("delta", Map.of("content", "first")));
      assertTrue(emitter.deltaEntered.await(3, TimeUnit.SECONDS));
      var failure = threads.submit(() -> stream.fail("AI_TIMEOUT", "Timed out"));
      assertTimeoutPreemptively(
          Duration.ofSeconds(3),
          () -> {
            while (true) {
              try {
                control.check();
              } catch (CancellationException cancelled) {
                break;
              }
              Thread.sleep(5);
            }
          });
      // Cancellation must not wait for output, but terminal output must wait for the current frame.
      assertThrows(TimeoutException.class, () -> failure.get(250, TimeUnit.MILLISECONDS));
      assertTrue(emitter.frames.isEmpty());
      emitter.allowDelta.countDown();
      delta.get(3, TimeUnit.SECONDS);
      failure.get(3, TimeUnit.SECONDS);
      assertEquals(2, emitter.frames.size());
      assertTrue(emitter.frames.get(0).contains("event:delta"));
      assertTrue(emitter.frames.get(1).contains("event:error"));
      assertTrue(emitter.frames.get(1).contains("partial=true"));
      assertTrue(emitter.completed);
      assertThrows(
          CancellationException.class, () -> stream.send("delta", Map.of("content", "late")));
      stream.succeed("conversation");
      stream.fail("AI_FAILED", "duplicate terminal");
      assertEquals(2, emitter.frames.size(), "No event may follow the terminal frame");
    } finally {
      emitter.allowDelta.countDown();
      stream.cancel();
    }
  }

  static final class ControlledEmitter extends SseEmitter {
    final CountDownLatch deltaEntered = new CountDownLatch(1);
    final CountDownLatch allowDelta = new CountDownLatch(1);
    final List<String> frames = new CopyOnWriteArrayList<>();
    volatile boolean completed;

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      String frame =
          builder.build().stream()
              .map(part -> String.valueOf(part.getData()))
              .collect(Collectors.joining());
      if (frame.contains("event:delta")) {
        deltaEntered.countDown();
        try {
          if (!allowDelta.await(5, TimeUnit.SECONDS))
            throw new IOException("Test writer was not released");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
      frames.add(frame);
    }

    @Override
    public void complete() {
      completed = true;
    }
  }
}
