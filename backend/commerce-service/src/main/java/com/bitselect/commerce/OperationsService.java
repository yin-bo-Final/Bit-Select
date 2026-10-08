package com.bitselect.commerce;

import com.bitselect.contracts.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
  private static final List<String> STATES =
      List.of("pending", "retrying", "processing", "completed", "failed");
  private static final List<String> ORDER_STATES =
      List.of("PENDING_PAYMENT", "PAID", "SHIPPED", "COMPLETED", "CANCELLED", "REFUNDED");
  private static final String COMMERCE =
      """
SELECT id,
  CASE WHEN sent_at IS NOT NULL THEN 'completed' WHEN attempts>0 THEN 'retrying' ELSE 'pending' END AS event_state,
  CASE WHEN sent_at IS NOT NULL THEN 'SENT' ELSE 'UNSENT' END AS raw_status,
  event_type,topic,aggregate_id,attempts,created_at,sent_at AS updated_at,
  sent_at AS completed_at,NULL AS error_code FROM event_outbox
""";
  private static final String MEMORY =
      """
SELECT id,
  CASE WHEN status='DONE' THEN 'completed' WHEN status='PROCESSING' THEN 'processing'
    WHEN status='FAILED' AND attempts>=8 THEN 'failed'
    WHEN status='FAILED' OR attempts>0 THEN 'retrying' ELSE 'pending' END AS event_state,
  status AS raw_status,'MEMORY_EXTRACTION' AS event_type,'bit-select-memory' AS topic,
  conversation_id AS aggregate_id,attempts,created_at,updated_at,
  CASE WHEN status='DONE' THEN updated_at ELSE NULL END AS completed_at,error_code FROM ai_memory_job
""";

  public record ServiceHealth(
      String id,
      String name,
      String layer,
      String status,
      String probe,
      Long latencyMs,
      Instant checkedAt,
      String detailCode) {}

  public record EventMetadata(
      String id,
      String kind,
      String status,
      String rawStatus,
      String eventType,
      String topic,
      String aggregateId,
      int attempts,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt,
      String errorCode) {}

  record ProbeTarget(String id, String name, String layer, String probe, String endpoint) {}

  private record ProbeSnapshot(Instant sampledAt, List<ServiceHealth> items) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final List<ProbeTarget> targets;
  private final Duration timeout;
  private final Duration cacheTtl;
  private final Clock clock;
  private final HttpClient http;
  private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
  private volatile ProbeSnapshot cached;

  @Autowired
  public OperationsService(JdbcTemplate db, ObjectMapper json, Environment environment) {
    this(
        db,
        json,
        configuredTargets(environment),
        Duration.ofMillis(900),
        Duration.ofSeconds(10),
        Clock.systemUTC());
  }

  static List<ProbeTarget> configuredTargets(Environment env) {
    return List.of(
        new ProbeTarget(
            "gateway",
            "API 网关",
            "application",
            "http",
            env.getProperty(
                "ops.gateway-url", env.getProperty("GATEWAY_URL", "http://127.0.0.1:8080"))),
        new ProbeTarget(
            "commerce",
            "账号与交易服务",
            "application",
            "http",
            env.getProperty(
                "ops.commerce-url", "http://127.0.0.1:" + env.getProperty("server.port", "8081"))),
        new ProbeTarget(
            "catalog",
            "商品目录服务",
            "application",
            "http",
            env.getProperty(
                "ops.catalog-url", env.getProperty("CATALOG_URL", "http://127.0.0.1:8082"))),
        new ProbeTarget(
            "ai",
            "AI 导购服务",
            "application",
            "http",
            env.getProperty("ops.ai-url", env.getProperty("AI_URL", "http://127.0.0.1:8083"))),
        tcp(
            "rocketmq-nameserver",
            "RocketMQ NameServer",
            env.getProperty(
                    "app.rocketmq.namesrv", env.getProperty("ROCKETMQ_NAMESRV", "127.0.0.1:19876"))
                .split(";", -1)[0],
            19876),
        tcp(
            "rocketmq-broker",
            "RocketMQ Broker",
            env.getProperty("ops.rocketmq-broker", "127.0.0.1:20911"),
            20911),
        tcp(
            "mysql",
            "MySQL",
            env.getProperty("MYSQL_HOST", "127.0.0.1")
                + ":"
                + env.getProperty("MYSQL_PORT", "13306"),
            13306),
        tcp(
            "redis",
            "Redis",
            env.getProperty("spring.data.redis.host", env.getProperty("REDIS_HOST", "127.0.0.1"))
                + ":"
                + env.getProperty("spring.data.redis.port", env.getProperty("REDIS_PORT", "16379")),
            16379),
        tcp("nacos", "Nacos", env.getProperty("NACOS_ADDRESS", "127.0.0.1:18848"), 18848),
        tcp("milvus", "Milvus", env.getProperty("MILVUS_URI", "http://127.0.0.1:19530"), 19530),
        tcp("neo4j", "Neo4j", env.getProperty("NEO4J_URI", "bolt://127.0.0.1:17687"), 17687),
        tcp(
            "rustfs",
            "RustFS",
            env.getProperty(
                "RUSTFS_ENDPOINT", "http://127.0.0.1:" + env.getProperty("RUSTFS_PORT", "19000")),
            19000),
        tcp("tika", "Apache Tika", env.getProperty("TIKA_URL", "http://127.0.0.1:19998"), 19998));
  }

  private static ProbeTarget tcp(String id, String name, String configured, int defaultPort) {
    String endpoint = "";
    try {
      URI uri = URI.create(configured.contains("://") ? configured : "tcp://" + configured);
      if (uri.getHost() != null && uri.getUserInfo() == null) {
        int port =
            uri.getPort() > 0
                ? uri.getPort()
                : "https".equals(uri.getScheme())
                    ? 443
                    : "http".equals(uri.getScheme()) ? 80 : defaultPort;
        endpoint = uri.getHost() + ":" + port;
      }
    } catch (IllegalArgumentException invalid) {
      /* Invalid configuration is reported without its value. */
    }
    return new ProbeTarget(id, name, "middleware", "tcp", endpoint);
  }

  OperationsService(
      JdbcTemplate db,
      ObjectMapper json,
      List<ProbeTarget> targets,
      Duration timeout,
      Duration cacheTtl,
      Clock clock) {
    this.db = db;
    this.json = json;
    this.targets = List.copyOf(targets);
    this.timeout = timeout;
    this.cacheTtl = cacheTtl;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Map<String, Object> overview() {
    Map<String, Long> orderStates = new LinkedHashMap<>();
    ORDER_STATES.forEach(status -> orderStates.put(status, 0L));
    db.query(
        "SELECT status,COUNT(*) AS count FROM orders GROUP BY status",
        row -> {
          orderStates.put(row.getString(1), row.getLong(2));
        });
    return Map.of(
        "sampledAt",
        clock.instant(),
        "serviceCacheSeconds",
        cacheTtl.toSeconds(),
        "services",
        health(),
        "commerce",
        Map.of(
            "users",
            count("SELECT COUNT(*) FROM users"),
            "customers",
            count("SELECT COUNT(*) FROM users WHERE role='USER'"),
            "orders",
            count("SELECT COUNT(*) FROM orders"),
            "ordersByStatus",
            orderStates),
        "queues",
        List.of(queue("commerce"), queue("memory")),
        "knowledge",
        Map.of(
            "documents",
            count("SELECT COUNT(*) FROM knowledge_document"),
            "indexed",
            count("SELECT COUNT(*) FROM knowledge_document WHERE status='READY'"),
            "chunks",
            count("SELECT COUNT(*) FROM knowledge_chunk WHERE active=TRUE")));
  }

  private long count(String query, Object... args) {
    Long value = db.queryForObject(query, Long.class, args);
    return value == null ? 0 : value;
  }

  private Map<String, Object> queue(String kind) {
    String source = source(kind);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind);
    result.put("name", kind.equals("commerce") ? "商城 Outbox 投递" : "长期记忆处理任务");
    STATES.forEach(state -> result.put(state, 0L));
    db.query(
        "SELECT event_state,COUNT(*) FROM (" + source + ") events GROUP BY event_state",
        row -> {
          result.put(row.getString(1), row.getLong(2));
        });
    result.put(
        "total",
        STATES.stream().mapToLong(state -> ((Number) result.get(state)).longValue()).sum());
    result.put(
        "oldestPendingAt",
        db.queryForObject(
            "SELECT MIN(created_at) FROM ("
                + source
                + ") events WHERE event_state IN ('pending','retrying','processing')",
            (row, n) -> instant(row, 1)));
    result.put(
        "scope",
        kind.equals("commerce")
            ? "数据库 Outbox 投递状态；已投递表示 Broker 已确认发送，不代表已被消费，不提供 Broker 消费积压。"
            : "数据库记忆任务状态；处理中包含当前第 8 次尝试，失败表示已耗尽 8 次处理尝试；不提供 Broker 消费积压。");
    return result;
  }

  public Map<String, Object> events(String kind, String status, int page, int pageSize) {
    String source = source(kind);
    if (!status.equals("all") && !STATES.contains(status)) throw ApiException.bad("不支持的事件状态");
    if (page < 1 || pageSize < 1 || pageSize > 50) throw ApiException.bad("页码须大于零，每页最多 50 条");
    String filter = status.equals("all") ? "" : " WHERE event_state=?";
    List<Object> args = new ArrayList<>();
    if (!status.equals("all")) args.add(status);
    long total = count("SELECT COUNT(*) FROM (" + source + ") events" + filter, args.toArray());
    args.add(pageSize);
    args.add(((long) page - 1) * pageSize);
    var items =
        db.query(
            "SELECT * FROM ("
                + source
                + ") events"
                + filter
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (row, n) -> metadata(row, kind),
            args.toArray());
    return Map.of("items", items, "total", total, "page", page, "pageSize", pageSize);
  }

  public EventMetadata event(String kind, String id) {
    String source = source(kind);
    if (id == null
        || !id.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw ApiException.bad("事件标识格式不正确");
    var rows =
        db.query(
            "SELECT * FROM (" + source + ") events WHERE id=?",
            (row, n) -> metadata(row, kind),
            id);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  private static String source(String kind) {
    return switch (kind) {
      case "commerce" -> COMMERCE;
      case "memory" -> MEMORY;
      default -> throw ApiException.bad("不支持的事件类型");
    };
  }

  private static EventMetadata metadata(ResultSet row, String kind) throws SQLException {
    String code = row.getString("error_code");
    if (code != null && !code.matches("[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)"))
      code = "UNCLASSIFIED_ERROR";
    return new EventMetadata(
        row.getString("id"),
        kind,
        row.getString("event_state"),
        row.getString("raw_status"),
        row.getString("event_type"),
        row.getString("topic"),
        row.getString("aggregate_id"),
        row.getInt("attempts"),
        instant(row, "created_at"),
        instant(row, "updated_at"),
        instant(row, "completed_at"),
        code);
  }

  private static Instant instant(ResultSet row, String name) throws SQLException {
    var timestamp = row.getTimestamp(name);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Instant instant(ResultSet row, int index) throws SQLException {
    var timestamp = row.getTimestamp(index);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private List<ServiceHealth> health() {
    var snapshot = cached;
    if (fresh(snapshot)) return snapshot.items();
    synchronized (this) {
      snapshot = cached;
      if (fresh(snapshot)) return snapshot.items();
      // Fixed configured targets only; probes run together rather than adding per-service timeouts.
      var pending = targets.stream().map(target -> probes.submit(() -> probe(target))).toList();
      long deadline = System.nanoTime() + timeout.plusMillis(300).toNanos();
      List<ServiceHealth> values = new ArrayList<>();
      for (int i = 0; i < targets.size(); i++) {
        try {
          values.add(
              pending.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          pending.forEach(task -> task.cancel(true));
          values.add(result(targets.get(i), "unknown", null, "INTERRUPTED"));
        } catch (Exception unavailable) {
          pending.get(i).cancel(true);
          values.add(result(targets.get(i), "down", null, "TIMEOUT"));
        }
      }
      cached = new ProbeSnapshot(clock.instant(), List.copyOf(values));
      return cached.items();
    }
  }

  private boolean fresh(ProbeSnapshot snapshot) {
    return snapshot != null && clock.instant().isBefore(snapshot.sampledAt().plus(cacheTtl));
  }

  private ServiceHealth probe(ProbeTarget target) {
    long started = System.nanoTime();
    try {
      if (target.probe().equals("tcp")) {
        URI endpoint = URI.create("tcp://" + target.endpoint());
        validate(endpoint, true);
        try (var socket = new Socket()) {
          socket.connect(
              new InetSocketAddress(endpoint.getHost(), endpoint.getPort()),
              (int) timeout.toMillis());
        }
        return result(target, "up", elapsed(started), "TCP_REACHABLE");
      }
      URI base = URI.create(target.endpoint());
      validate(base, false);
      URI endpoint = URI.create(target.endpoint().replaceAll("/+$", "") + "/actuator/health");
      var future =
          http.sendAsync(
              HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      try {
        var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean up =
            response.statusCode() == 200
                && "UP".equals(json.readTree(response.body()).path("status").asText());
        return result(
            target, up ? "up" : "down", elapsed(started), up ? "HEALTH_UP" : "HEALTH_NOT_UP");
      } finally {
        if (!future.isDone()) future.cancel(true);
      }
    } catch (IllegalArgumentException invalid) {
      return result(target, "unknown", null, "INVALID_CONFIGURATION");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return result(target, "unknown", elapsed(started), "INTERRUPTED");
    } catch (TimeoutException | SocketTimeoutException timeout) {
      return result(target, "down", elapsed(started), "TIMEOUT");
    } catch (ExecutionException unavailable) {
      return result(
          target,
          "down",
          elapsed(started),
          unavailable.getCause() instanceof HttpTimeoutException ? "TIMEOUT" : "UNREACHABLE");
    } catch (Exception unavailable) {
      return result(target, "down", elapsed(started), "UNREACHABLE");
    }
  }

  private static void validate(URI endpoint, boolean tcp) {
    if (endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || (tcp
            ? endpoint.getPort() < 1
                || endpoint.getPort() > 65535
                || !Objects.toString(endpoint.getPath(), "").isEmpty()
            : !Set.of("http", "https").contains(endpoint.getScheme())))
      throw new IllegalArgumentException("Invalid fixed probe endpoint");
  }

  private ServiceHealth result(ProbeTarget target, String status, Long elapsed, String code) {
    return new ServiceHealth(
        target.id(),
        target.name(),
        target.layer(),
        status,
        target.probe(),
        elapsed,
        clock.instant(),
        code);
  }

  private static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  @PreDestroy
  public void close() {
    probes.shutdownNow();
    http.shutdownNow();
  }
}
