package com.bitselect.ai;

import com.bitselect.contracts.ApiException;
import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Records actual events only. A bounded asynchronous writer never waits on the generation path. */
@Service
public class OpsTraceStore {
  private static final Map<String, String> PHASES =
      Map.of(
          "rewrite",
          "理解问题",
          "intent",
          "识别需求",
          "retrieve",
          "检索资料与业务数据",
          "compose",
          "生成回答",
          "save",
          "保存会话");
  private static final Set<String> STATUSES = Set.of("running", "completed", "failed", "cancelled");
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)\\b(?:sk-[a-z0-9_-]+|gh[pousr]_[a-z0-9]+|github_pat_[a-z0-9_]+)"
              + "|(?i)(?:api[_ -]?key|authorization|password|密钥|密码)\\s*[:=：]\\s*[^\\s,，;；]+"
              + "|(?i)\\bBearer\\s+[^\\s,，;；]+");
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Duration staleAfter;
  private final ThreadPoolExecutor writer;
  private final ConcurrentHashMap<String, RecordingTrace> active = new ConcurrentHashMap<>();

  @Autowired
  public OpsTraceStore(
      JdbcTemplate db,
      PlatformTransactionManager transactions,
      @Value("${ai.request-timeout-seconds:170}") long deadlineSeconds) {
    this(
        db, transactions, Clock.systemUTC(), Duration.ofSeconds(Math.max(1, deadlineSeconds) + 30));
  }

  OpsTraceStore(
      JdbcTemplate db, PlatformTransactionManager transactions, Clock clock, Duration staleAfter) {
    this.db = new JdbcTemplate(Objects.requireNonNull(db.getDataSource()));
    this.db.setQueryTimeout(3);
    this.tx = new TransactionTemplate(transactions);
    this.tx.setTimeout(3);
    this.clock = clock;
    this.staleAfter = staleAfter;
    writer =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
              Thread thread = new Thread(r, "ai-ops-writer");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  interface Trace {
    String id();

    void event(String name, Object payload);

    void finish(String status, String errorCode);
  }

  static final Trace NOOP =
      new Trace() {
        public String id() {
          return null;
        }

        public void event(String name, Object payload) {}

        public void finish(String status, String errorCode) {}
      };

  public Trace begin(long user, String conversation, String question) {
    var trace =
        new RecordingTrace(UUID.randomUUID().toString(), user, conversation, preview(question));
    active.put(trace.id, trace);
    if (!enqueue(
        () ->
            db.update(
                "INSERT INTO"
                    + " ai_request_trace(id,conversation_id,user_id,question_preview,status,started_at,last_activity_at)"
                    + " VALUES(?,?,?,?,'running',?,?)",
                trace.id,
                conversation,
                user,
                trace.question,
                timestamp(trace.started),
                timestamp(trace.started)))) {
      active.remove(trace.id);
      return NOOP;
    }
    return trace;
  }

  private boolean enqueue(Runnable action) {
    try {
      writer.execute(
          () -> {
            try {
              action.run();
            } catch (RuntimeException error) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn(
                      "AI monitoring persistence unavailable ({})",
                      error.getClass().getSimpleName());
            }
          });
      return true;
    } catch (RejectedExecutionException full) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("AI monitoring queue full; event omitted");
      return false;
    }
  }

  private final class RecordingTrace implements Trace {
    private final String id, conversation, question;
    private final long user;
    private final Instant started = clock.instant();
    private Instant activity = started, finished;
    private String status = "running", error;
    private Long firstToken;
    private long output;
    private final LinkedHashMap<String, Phase> phases = new LinkedHashMap<>();

    RecordingTrace(String id, long user, String conversation, String question) {
      this.id = id;
      this.user = user;
      this.conversation = conversation;
      this.question = question;
    }

    public String id() {
      return id;
    }

    public synchronized void event(String name, Object payload) {
      if (!status.equals("running") || !(payload instanceof Map<?, ?> data)) return;
      Instant now = clock.instant();
      if (name.equals("delta")
          && data.get("content") instanceof String content
          && !content.isEmpty()) {
        if (firstToken == null) {
          firstToken = elapsed(started, now);
          Long first = firstToken;
          enqueue(
              () ->
                  db.update(
                      "UPDATE ai_request_trace SET first_token_ms=?,last_activity_at=? WHERE id=?"
                          + " AND status='running'",
                      first,
                      timestamp(now),
                      id));
        }
        output += content.codePointCount(0, content.length());
        activity = now;
        return; // Only the first token is persisted; subsequent tokens remain in memory.
      }
      if (!name.equals("phase")) return;
      String code = Objects.toString(data.get("code"), ""),
          state = Objects.toString(data.get("status"), "");
      if (!PHASES.containsKey(code)) return;
      Phase phase;
      if (state.equals("running") && !phases.containsKey(code)) {
        phase = new Phase(code, phases.size(), "running", now, null);
      } else if (state.equals("completed")
          && phases.containsKey(code)
          && phases.get(code).status.equals("running")) {
        phase =
            new Phase(code, phases.get(code).sequence, "completed", phases.get(code).started, now);
      } else return;
      phases.put(code, phase);
      activity = now;
      Long tokens = firstToken;
      long chars = output;
      enqueue(
          () ->
              tx.executeWithoutResult(
                  ignored -> {
                    if (phase.status.equals("running"))
                      db.update(
                          "INSERT INTO"
                              + " ai_request_phase(request_id,phase_code,sequence_no,status,started_at)"
                              + " VALUES(?,?,?,'running',?)",
                          id,
                          code,
                          phase.sequence,
                          timestamp(phase.started));
                    else persistPhase(id, phase);
                    db.update(
                        "UPDATE ai_request_trace SET"
                            + " last_activity_at=?,first_token_ms=?,output_chars=? WHERE id=? AND"
                            + " status='running'",
                        timestamp(now),
                        tokens,
                        chars,
                        id);
                  }));
    }

    public synchronized void finish(String finalStatus, String code) {
      if (!status.equals("running")
          || !Set.of("completed", "failed", "cancelled").contains(finalStatus)) return;
      status = finalStatus;
      finished = activity = clock.instant();
      error =
          code == null
              ? null
              : Set.of("AI_TIMEOUT", "AI_FAILED", "CLIENT_CANCELLED").contains(code)
                  ? code
                  : "AI_FAILED";
      // An interrupted phase is marked interrupted, never invented as successfully completed.
      List<Phase> interrupted = new ArrayList<>();
      for (var entry : phases.entrySet()) {
        Phase phase = entry.getValue();
        if (phase.status.equals("running") && !status.equals("completed")) {
          phase = new Phase(phase.code, phase.sequence, status, phase.started, finished);
          entry.setValue(phase);
          interrupted.add(phase);
        }
      }
      RequestView snapshot = view();
      boolean submitted =
          enqueue(
              () -> {
                try {
                  tx.executeWithoutResult(
                      ignored -> {
                        db.update(
                            "UPDATE ai_request_trace SET"
                                + " status=?,finished_at=?,last_activity_at=?,first_token_ms=?,duration_ms=?,output_chars=?,error_code=?"
                                + " WHERE id=? AND status='running'",
                            snapshot.status,
                            timestamp(finished),
                            timestamp(activity),
                            snapshot.firstTokenMs,
                            snapshot.totalMs,
                            snapshot.outputChars,
                            snapshot.errorCode,
                            id);
                        for (Phase phase : interrupted) persistPhase(id, phase);
                      });
                } finally {
                  active.remove(id, this);
                }
              });
      if (!submitted) active.remove(id, this);
    }

    synchronized RequestView view() {
      Instant now = clock.instant();
      return new RequestView(
          id,
          conversation,
          user,
          question,
          status,
          started.toString(),
          iso(finished),
          activity.toString(),
          firstToken,
          elapsed(started, finished == null ? now : finished),
          output,
          error,
          finished == null && Duration.between(started, now).compareTo(staleAfter) > 0,
          finished != null);
    }
  }

  private record Phase(
      String code, int sequence, String status, Instant started, Instant finished) {}

  private void persistPhase(String id, Phase phase) {
    db.update(
        "UPDATE ai_request_phase SET status=?,finished_at=?,duration_ms=? WHERE request_id=? AND"
            + " phase_code=?",
        phase.status,
        timestamp(phase.finished),
        elapsed(phase.started, phase.finished),
        id,
        phase.code);
  }

  public record RequestView(
      String id,
      String conversationId,
      long userId,
      String questionPreview,
      String status,
      String startedAt,
      String finishedAt,
      String lastActivityAt,
      Long firstTokenMs,
      long totalMs,
      long outputChars,
      String errorCode,
      boolean stale,
      boolean outputCharsFinal) {}

  public record StepView(
      String code,
      String label,
      String status,
      String startedAt,
      String finishedAt,
      long durationMs) {}

  public record Page(List<RequestView> items, long total, int page, int pageSize) {}

  public boolean isAdmin(long user) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id=? AND role='ADMIN'", Long.class, user)
        > 0;
  }

  public Page list(int page, int pageSize, String status, Long userId) {
    if (page < 1
        || page > 100000
        || pageSize < 1
        || pageSize > 50
        || !(status.equals("all") || STATUSES.contains(status))
        || userId != null && userId < 1) throw ApiException.bad("请检查页码、筛选状态与用户编号");
    List<Object> arguments = new ArrayList<>();
    String where = " WHERE 1=1";
    if (!status.equals("all")) {
      where += " AND status=?";
      arguments.add(status);
    }
    if (userId != null) {
      where += " AND user_id=?";
      arguments.add(userId);
    }
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM ai_request_trace" + where, Long.class, arguments.toArray());
    arguments.add(pageSize);
    arguments.add((page - 1) * pageSize);
    var rows =
        db.query(
            "SELECT * FROM ai_request_trace"
                + where
                + " ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",
            this::requestRow,
            arguments.toArray());
    return new Page(rows, total, page, pageSize);
  }

  public Map<String, Object> detail(String id) {
    if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw ApiException.missing();
    var rows = db.query("SELECT * FROM ai_request_trace WHERE id=?", this::requestRow, id);
    if (rows.isEmpty()) throw ApiException.missing();
    RequestView row = rows.getFirst();
    var result = new LinkedHashMap<String, Object>();
    result.put("id", row.id);
    result.put("conversationId", row.conversationId);
    result.put("userId", row.userId);
    result.put("questionPreview", row.questionPreview);
    result.put("status", row.status);
    result.put("startedAt", row.startedAt);
    result.put("finishedAt", row.finishedAt);
    result.put("lastActivityAt", row.lastActivityAt);
    result.put("firstTokenMs", row.firstTokenMs);
    result.put("totalMs", row.totalMs);
    result.put("outputChars", row.outputChars);
    result.put("errorCode", row.errorCode);
    result.put("stale", row.stale);
    result.put("outputCharsFinal", row.outputCharsFinal);
    result.put(
        "steps",
        db.query(
            "SELECT * FROM ai_request_phase WHERE request_id=? ORDER BY sequence_no LIMIT 5",
            (rs, i) -> {
              Instant start = rs.getTimestamp("started_at").toInstant(),
                  end = instant(rs.getTimestamp("finished_at"));
              String code = rs.getString("phase_code");
              return new StepView(
                  code,
                  PHASES.getOrDefault(code, "未知阶段"),
                  rs.getString("status"),
                  start.toString(),
                  iso(end),
                  elapsed(start, end == null ? clock.instant() : end));
            },
            id));
    return result;
  }

  public Map<String, Object> summary() {
    var result = new LinkedHashMap<String, Object>();
    for (String status : STATUSES) result.put(status, 0L);
    long total = 0;
    for (var row :
        db.queryForList("SELECT status,COUNT(*) AS n FROM ai_request_trace GROUP BY status")) {
      long count = ((Number) row.get("n")).longValue();
      result.put(row.get("status").toString(), count);
      total += count;
    }
    result.put("total", total);
    result.put(
        "stale",
        db.queryForObject(
            "SELECT COUNT(*) FROM ai_request_trace WHERE status='running' AND started_at<?",
            Long.class,
            timestamp(clock.instant().minus(staleAfter))));
    result.put(
        "firstRecordedAt",
        iso(db.queryForObject("SELECT MIN(started_at) FROM ai_request_trace", Timestamp.class)));
    result.put(
        "lastRecordedAt",
        iso(db.queryForObject("SELECT MAX(started_at) FROM ai_request_trace", Timestamp.class)));
    return result;
  }

  private RequestView requestRow(ResultSet rs, int ignored) throws SQLException {
    String id = rs.getString("id");
    RecordingTrace live = active.get(id);
    if (live != null && rs.getString("status").equals("running")) {
      var snapshot = live.view();
      // Expose terminal status only after its transaction (including phase endings) commits.
      if (snapshot.status.equals("running")) return snapshot;
    }
    Instant start = rs.getTimestamp("started_at").toInstant(),
        end = instant(rs.getTimestamp("finished_at"));
    long first = rs.getLong("first_token_ms");
    Long firstToken = rs.wasNull() ? null : first;
    String status = rs.getString("status");
    return new RequestView(
        id,
        rs.getString("conversation_id"),
        rs.getLong("user_id"),
        rs.getString("question_preview"),
        status,
        start.toString(),
        iso(end),
        rs.getTimestamp("last_activity_at").toInstant().toString(),
        firstToken,
        elapsed(start, end == null ? clock.instant() : end),
        rs.getLong("output_chars"),
        rs.getString("error_code"),
        status.equals("running")
            && Duration.between(start, clock.instant()).compareTo(staleAfter) > 0,
        end != null);
  }

  static String preview(String question) {
    String safe =
        SECRET
            .matcher(Objects.toString(question, ""))
            .replaceAll("[已隐藏]")
            .replaceAll("[\\p{Cc}\\p{Cf}]+", " ")
            .strip();
    return safe.substring(
        0, safe.offsetByCodePoints(0, Math.min(120, safe.codePointCount(0, safe.length()))));
  }

  private static long elapsed(Instant start, Instant end) {
    return Math.max(0, Duration.between(start, end).toMillis());
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static String iso(Timestamp timestamp) {
    return iso(instant(timestamp));
  }

  boolean awaitWrites(Duration timeout) throws Exception {
    var complete = new CompletableFuture<Boolean>();
    return enqueue(() -> complete.complete(true))
        && complete.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  public void close() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(2, TimeUnit.SECONDS)) writer.shutdownNow();
    } catch (InterruptedException interrupted) {
      writer.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
