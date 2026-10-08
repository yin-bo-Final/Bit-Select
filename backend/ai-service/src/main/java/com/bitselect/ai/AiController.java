package com.bitselect.ai;

import com.bitselect.contracts.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
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
  private final OpsTraceStore traces;
  private final Semaphore capacity = new Semaphore(3);

  @Value("${ai.request-timeout-seconds:170}")
  private long requestTimeoutSeconds = 170;

  @Value("${ai.heartbeat-seconds:15}")
  private long heartbeatSeconds = 15;

  public AiController(
      SessionService sessions,
      ConversationStore conversations,
      MemoryService memory,
      KnowledgeService knowledge,
      AiWorkflow workflow,
      StringRedisTemplate redis,
      SiliconFlowClient model,
      OpsTraceStore traces) {
    this.sessions = sessions;
    this.conversations = conversations;
    this.memory = memory;
    this.knowledge = knowledge;
    this.workflow = workflow;
    this.redis = redis;
    this.model = model;
    this.traces = traces;
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
    var control = model.newRequestControl();
    OpsTraceStore.Trace trace = OpsTraceStore.NOOP;
    try {
      trace = traces.begin(user, conversation, body.message());
    } catch (RuntimeException ignored) {
      // Observability is never a prerequisite for serving the accepted request.
    }
    final var requestTrace = trace;
    var stream =
        new ChatStream(
            control,
            Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)),
            Duration.ofSeconds(Math.max(1, heartbeatSeconds)), requestTrace);
    Thread.startVirtualThread(
        () -> {
          try {
            model.bindRequest(control);
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("conversationId", conversation);
            metadata.put("requestId", requestTrace.id());
            stream.send("meta", metadata);
            workflow.run(user, conversation, body.message(), stream::send);
            stream.succeed(conversation);
          } catch (Exception e) {
            stream.fail("AI_FAILED", "暂时无法完成回答，请重试；未完成的回答不会写入会话。");
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn(
                    "AI request failed for conversation {}: {}",
                    conversation,
                    e.getClass().getSimpleName());
          } finally {
            model.clearRequest();
            Thread.interrupted();
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
            } finally {
              // Do not admit another generation until the cancelled worker has actually stopped.
              capacity.release();
            }
          }
        });
    return stream.emitter();
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
