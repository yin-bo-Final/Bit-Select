package com.bitselect.ai;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Owns one downstream stream. Worker cleanup, including its concurrency permit, stays with the
 * worker.
 */
final class ChatStream {
  private enum State {
    OPEN,
    COMMITTING,
    PERSISTED,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  private static final ScheduledThreadPoolExecutor CLOCK =
      new ScheduledThreadPoolExecutor(
          1,
          r -> {
            Thread thread = new Thread(r, "ai-stream-clock");
            thread.setDaemon(true);
            return thread;
          });

  static {
    CLOCK.setRemoveOnCancelPolicy(true);
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
  private final AtomicBoolean partial = new AtomicBoolean();
  private final ReentrantLock eventWriteLock = new ReentrantLock();
  private final SiliconFlowClient.RequestControl control;
  private final OpsTraceStore.Trace trace;
  private final SseEmitter emitter;
  private final ScheduledFuture<?> deadline;
  private final Thread heartbeat;

  ChatStream(
      SiliconFlowClient.RequestControl control, Duration timeout, Duration heartbeatInterval) {
    this(control, timeout, heartbeatInterval, emitter(timeout), OpsTraceStore.NOOP);
  }

  ChatStream(
      SiliconFlowClient.RequestControl control,
      Duration timeout,
      Duration heartbeatInterval,
      OpsTraceStore.Trace trace) {
    this(control, timeout, heartbeatInterval, emitter(timeout), trace);
  }

  private static SseEmitter emitter(Duration timeout) {
    return new SseEmitter(timeout.toMillis() + 5000) {
      @Override
      protected void extendResponse(ServerHttpResponse response) {
        super.extendResponse(response);
        response.getHeaders().set("Cache-Control", "no-cache, no-store, no-transform");
        response.getHeaders().set("Pragma", "no-cache");
        response.getHeaders().set("X-Accel-Buffering", "no");
      }
    };
  }

  ChatStream(
      SiliconFlowClient.RequestControl control,
      Duration timeout,
      Duration heartbeatInterval,
      SseEmitter emitter) {
    this(control, timeout, heartbeatInterval, emitter, OpsTraceStore.NOOP);
  }

  ChatStream(
      SiliconFlowClient.RequestControl control,
      Duration timeout,
      Duration heartbeatInterval,
      SseEmitter emitter,
      OpsTraceStore.Trace trace) {
    this.control = control;
    this.emitter = emitter;
    this.trace = trace;
    emitter.onTimeout(() -> fail("AI_TIMEOUT", "回答超时，请重试。未完成的回答不会写入会话。"));
    emitter.onError(error -> cancel());
    emitter.onCompletion(
        () -> {
          if (state.get() == State.OPEN) cancel();
        });
    deadline =
        CLOCK.schedule(
            () -> Thread.startVirtualThread(() -> fail("AI_TIMEOUT", "回答超时，请重试。未完成的回答不会写入会话。")),
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    heartbeat =
        Thread.ofVirtual()
            .name("ai-stream-heartbeat")
            .unstarted(
                () -> {
                  try {
                    while (state.get() == State.OPEN) {
                      Thread.sleep(heartbeatInterval);
                      eventWriteLock.lock();
                      try {
                        if (state.get() == State.OPEN)
                          emitter.send(SseEmitter.event().comment("keep-alive"));
                      } finally {
                        eventWriteLock.unlock();
                      }
                    }
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  } catch (Exception disconnected) {
                    cancel();
                  }
                });
    heartbeat.start();
  }

  SseEmitter emitter() {
    return emitter;
  }

  void send(String event, Object data) {
    eventWriteLock.lock();
    try {
      boolean persistedPhase = state.get() == State.PERSISTED && event.equals("phase");
      if (state.get() != State.OPEN && !persistedPhase)
        throw new CancellationException("CLIENT_CLOSED");
      observe(() -> trace.event(event, data));
      try {
        emitter.send(SseEmitter.event().name(event).data(data));
      } catch (Exception disconnected) {
        if (persistedPhase) return; // A delivery failure cannot undo an already committed turn.
        throw disconnected;
      }
      if (event.equals("delta")) partial.set(true);
    } catch (Exception error) {
      cancel();
      throw new CancellationException("CLIENT_CLOSED");
    } finally {
      eventWriteLock.unlock();
    }
  }

  void succeed(String conversation) {
    boolean persisted = state.compareAndSet(State.PERSISTED, State.SUCCEEDED);
    if (!persisted && !state.compareAndSet(State.OPEN, State.SUCCEEDED)) return;
    if (!persisted) observe(() -> trace.finish("completed", null));
    stopTimers();
    eventWriteLock.lock();
    try {
      // This event confirms the complete answer and its references have committed to the database.
      emitter.send(SseEmitter.event().name("done").data(Map.of("conversationId", conversation)));
      emitter.complete();
    } catch (Exception disconnected) {
      control.cancel();
    } finally {
      eventWriteLock.unlock();
    }
  }

  void fail(String code, String message) {
    failFrom(State.OPEN, code, message);
  }

  /**
   * Cancellation wins before this CAS; after acceptance, the bounded transaction decides outcome.
   */
  void persist(AiWorkflow.PersistenceAction save) throws Exception {
    if (!state.compareAndSet(State.OPEN, State.COMMITTING))
      throw new CancellationException("CLIENT_CLOSED");
    stopTimers();
    try {
      save.run();
      observe(() -> trace.event("phase", Map.of("code", "save", "status", "completed")));
      observe(() -> trace.finish("completed", null));
      state.set(State.PERSISTED);
    } catch (Exception error) {
      failFrom(State.COMMITTING, "AI_FAILED", "会话保存失败，请重试；本轮回答未写入会话。");
      throw error;
    }
  }

  private void failFrom(State expected, String code, String message) {
    if (!state.compareAndSet(expected, State.FAILED)) return;
    observe(() -> trace.finish("failed", code));
    stopTimers();
    // Claim the terminal state and cancel upstream before waiting for a slow downstream write.
    // Writers check OPEN under the same lock, so any in-flight frame precedes this terminal frame.
    control.cancel();
    eventWriteLock.lock();
    try {
      emitter.send(
          SseEmitter.event()
              .name("error")
              .data(
                  Map.of(
                      "code",
                      code,
                      "message",
                      message,
                      "retryable",
                      true,
                      "partial",
                      partial.get())));
      emitter.complete();
    } catch (Exception disconnected) {
      // The container handles a failed write; never attempt another terminal event.
    } finally {
      eventWriteLock.unlock();
    }
  }

  void cancel() {
    if (!state.compareAndSet(State.OPEN, State.CANCELLED)) return;
    observe(() -> trace.finish("cancelled", "CLIENT_CANCELLED"));
    stopTimers();
    control.cancel();
  }

  private void stopTimers() {
    if (deadline != null) deadline.cancel(false);
    if (heartbeat != null) heartbeat.interrupt();
  }

  private void observe(Runnable event) {
    try {
      event.run();
    } catch (RuntimeException ignored) {
      // No monitoring error may change the stream state or block upstream cancellation.
    }
  }
}
