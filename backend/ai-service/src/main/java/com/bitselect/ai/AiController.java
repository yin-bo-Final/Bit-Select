package com.bitselect.ai;

import com.bitselect.contracts.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
public class AiController {
  private final SessionService sessions;
  private final ConversationStore conversations;
  private final MemoryService memory;
  private final KnowledgeService knowledge;
  private final AiWorkflow workflow;
  private final StringRedisTemplate redis;
  private final SiliconFlowClient model;
  private final Semaphore capacity = new Semaphore(3);

  public AiController(
      SessionService sessions,
      ConversationStore conversations,
      MemoryService memory,
      KnowledgeService knowledge,
      AiWorkflow workflow,
      StringRedisTemplate redis,
      SiliconFlowClient model) {
    this.sessions = sessions;
    this.conversations = conversations;
    this.memory = memory;
    this.knowledge = knowledge;
    this.workflow = workflow;
    this.redis = redis;
    this.model = model;
  }

  public record ChatRequest(String conversationId, @NotBlank @Size(max = 8000) String message) {}

  @PostMapping(value = "/chat", produces = "text/event-stream")
  public SseEmitter chat(HttpServletRequest request, @Valid @RequestBody ChatRequest body) {
    long user = sessions.requireUserId(request);
    if (!model.configured()) throw new ApiException(503, "MODEL_UNCONFIGURED", "请管理员配置模型服务");
    if (!capacity.tryAcquire()) throw new ApiException(429, "AI_BUSY", "导购繁忙，请稍后重试");
    String conversation;
    try {
      conversation = conversations.ensure(user, body.conversationId(), body.message());
    } catch (RuntimeException e) {
      capacity.release();
      throw e;
    }
    String key = "bit:ai:lock:" + conversation, owner = UUID.randomUUID().toString();
    try {
      if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofMinutes(10))))
        throw new ApiException(409, "CONVERSATION_BUSY", "这段会话仍在生成回答");
    } catch (RuntimeException e) {
      capacity.release();
      throw e;
    }
    SseEmitter emitter = new SseEmitter(180000L);
    AtomicBoolean closed = new AtomicBoolean(false),
        released = new AtomicBoolean(false),
        finished = new AtomicBoolean(false);
    var control = model.newRequestControl();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) capacity.release();
        };
    Runnable abort =
        () -> {
          closed.set(true);
          control.cancel();
          release.run();
        };
    emitter.onTimeout(abort);
    emitter.onCompletion(
        () -> {
          if (!finished.get()) abort.run();
          else closed.set(true);
        });
    emitter.onError(e -> abort.run());
    var deadline =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              if (!finished.get()) {
                abort.run();
                emitter.complete();
              }
            },
            java.util.concurrent.CompletableFuture.delayedExecutor(
                170, java.util.concurrent.TimeUnit.SECONDS));
    Thread.startVirtualThread(
        () -> {
          java.util.function.BiConsumer<String, Object> send =
              (event, data) -> {
                if (closed.get()) throw new IllegalStateException("CLIENT_CLOSED");
                try {
                  emitter.send(SseEmitter.event().name(event).data(data));
                } catch (Exception e) {
                  throw new IllegalStateException("CLIENT_CLOSED");
                }
              };
          try {
            model.bindRequest(control);
            send.accept("meta", Map.of("conversationId", conversation));
            workflow.run(user, conversation, body.message(), send);
            send.accept("done", Map.of("conversationId", conversation));
            finished.set(true);
            emitter.complete();
          } catch (Exception e) {
            if (!closed.get())
              try {
                send.accept("error", Map.of("message", "暂时无法完成回答，请稍后重试；商品交易服务不受影响。"));
                finished.set(true);
                emitter.complete();
              } catch (Exception ignored) {
              }
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn(
                    "AI request failed for conversation {}: {}",
                    conversation,
                    e.getClass().getSimpleName());
          } finally {
            finished.set(true);
            deadline.cancel(false);
            model.clearRequest();
            Thread.interrupted();
            release.run();
            try {
              redis.execute(
                  new DefaultRedisScript<>(
                      "if redis.call('get',KEYS[1]) == ARGV[1] then return"
                          + " redis.call('del',KEYS[1]) else return 0 end",
                      Long.class),
                  List.of(key),
                  owner);
            } catch (Exception e) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn("Conversation lock cleanup deferred to TTL");
            }
          }
        });
    return emitter;
  }

  @GetMapping("/conversations")
  public Object list(HttpServletRequest req) {
    return Map.of("items", conversations.list(sessions.requireUserId(req)));
  }

  @GetMapping("/conversations/{id}")
  public Object conversation(HttpServletRequest req, @PathVariable String id) {
    return conversations.details(sessions.requireUserId(req), id);
  }

  @GetMapping("/memories")
  public Object memories(HttpServletRequest req) {
    long user = sessions.requireUserId(req);
    return Map.of("items", memory.list(user), "enabled", memory.preferenceEnabled(user));
  }

  @DeleteMapping("/memories/{id}")
  public Object delete(HttpServletRequest req, @PathVariable String id) {
    memory.delete(sessions.requireUserId(req), id);
    return Map.of("ok", true);
  }

  @PutMapping("/memory-preference")
  public Object preference(HttpServletRequest req, @RequestBody Map<String, Boolean> body) {
    memory.preference(sessions.requireUserId(req), Boolean.TRUE.equals(body.get("enabled")));
    return Map.of("ok", true);
  }

  @GetMapping("/knowledge")
  public Object knowledge(HttpServletRequest req) throws Exception {
    admin(req);
    return knowledge.status();
  }

  @PostMapping("/knowledge/reindex")
  public Object reindex(HttpServletRequest req) throws Exception {
    admin(req);
    return Map.of("started", knowledge.indexAsync());
  }

  private void admin(HttpServletRequest request) throws Exception {
    if (!workflow.admin(sessions.requireUserId(request)))
      throw new ApiException(403, "FORBIDDEN", "需要管理员权限");
  }
}
