package com.bitselect.ai;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemoryService {
  static final String COLLECTION = "bit_memory_qwen4b_v1", TOPIC = "bit-select-memory";
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final SiliconFlowClient model;
  private final MilvusStore vectors;
  private final String namesrv;
  private final Driver graph;
  private final boolean enabled;
  private DefaultMQProducer producer;
  private DefaultMQPushConsumer consumer;

  public MemoryService(
      JdbcTemplate db,
      TransactionTemplate tx,
      SiliconFlowClient model,
      MilvusStore vectors,
      @Value("${ai.rocketmq-address}") String namesrv,
      @Value("${ai.neo4j-uri}") String uri,
      @Value("${ai.neo4j-user}") String user,
      @Value("${ai.neo4j-password}") String pass,
      @Value("${ai.memory-enabled}") boolean enabled) {
    this.db = db;
    this.tx = tx;
    this.model = model;
    this.vectors = vectors;
    this.namesrv = namesrv;
    this.enabled = enabled;
    this.graph = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
  }

  public List<Map<String, Object>> list(long user) {
    return db.queryForList(
        "SELECT id,content,source,confidence,updated_at AS updatedAt FROM ai_memory WHERE user_id=?"
            + " AND deleted=FALSE ORDER BY updated_at DESC LIMIT 100",
        user);
  }

  public boolean preferenceEnabled(long user) {
    if (!enabled) return false;
    var rows = db.queryForList("SELECT enabled FROM ai_memory_preference WHERE user_id=?", user);
    return rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().get("enabled"));
  }

  public void preference(long user, boolean remember) {
    tx.executeWithoutResult(
        s -> {
          db.update("INSERT IGNORE INTO ai_memory_preference(user_id) VALUES(?)", user);
          db.update(
              "UPDATE ai_memory_preference SET enabled=?,generation=generation+1 WHERE user_id=?",
              remember,
              user);
        });
  }

  public void delete(long user, String id) {
    tx.executeWithoutResult(
        s -> {
          db.update("INSERT IGNORE INTO ai_memory_preference(user_id) VALUES(?)", user);
          db.update(
              "UPDATE ai_memory_preference SET generation=generation+1 WHERE user_id=?", user);
          db.update(
              "UPDATE ai_memory SET"
                  + " deleted=TRUE,projected=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=? AND user_id=?",
              id,
              user);
        });
  }

  public List<Map<String, Object>> recall(long user, String query) throws Exception {
    if (!preferenceEnabled(user)) return List.of();
    Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
    try {
      var embedding = model.embed(List.of(query));
      for (var hit : vectors.search(COLLECTION, embedding.getFirst(), "userId == " + user, 10))
        addCandidate(candidates, user, Objects.toString(hit.get("memoryId"), ""));
    } catch (Exception ignored) {
      /* SQL remains authoritative when a projection is unavailable. */
    }
    try (var session = graph.session()) {
      var records =
          session.run(
              "MATCH (f:BitMemory {userId:$user}) WHERE f.deleted=false AND (toLower(f.entity)"
                  + " CONTAINS toLower($query) OR toLower($query) CONTAINS toLower(f.entity))"
                  + " RETURN f.memoryId AS id LIMIT 10",
              Map.of("user", user, "query", query));
      while (records.hasNext()) addCandidate(candidates, user, records.next().get("id").asString());
    } catch (Exception ignored) {
      /* Graph failure must not invent relationships. */
    }
    for (var row :
        db.queryForList(
            "SELECT id FROM ai_memory WHERE user_id=? AND deleted=FALSE ORDER BY updated_at DESC"
                + " LIMIT 12",
            user)) addCandidate(candidates, user, row.get("id").toString());
    var ordered = MemoryPolicy.newestFirst(candidates.values());
    if (ordered.size() < 2) return ordered;
    try {
      var decision =
          model.json(
              model.complete(
                  List.of(
                      Map.of("role", "system", "content", Prompts.read("memory/select-consistent")),
                      Map.of(
                          "role", "user", "content", com.bitselect.contracts.Json.write(ordered))),
                  800));
      if (!decision.path("groups").isArray() || !decision.path("uncertainIds").isArray())
        return ordered.subList(0, 1);
      List<List<String>> groups = new ArrayList<>();
      for (var group : decision.path("groups")) {
        if (!group.isArray()) return ordered.subList(0, 1);
        List<String> ids = new ArrayList<>();
        group.forEach(n -> ids.add(n.asText()));
        groups.add(ids);
      }
      Set<String> uncertain = new HashSet<>();
      decision.path("uncertainIds").forEach(n -> uncertain.add(n.asText()));
      return MemoryPolicy.consistent(ordered, groups, uncertain);
    } catch (Exception ignored) {
      return ordered.subList(0, 1);
    }
  }

  private void addCandidate(Map<String, Map<String, Object>> candidates, long user, String id) {
    var rows =
        db.queryForList(
            "SELECT m.id,m.entity,m.attribute_name,m.content,m.confidence,m.updated_at AS"
                + " updatedAt,COALESCE((SELECT MAX(msg.id) FROM ai_message msg WHERE"
                + " msg.user_id=m.user_id AND msg.turn_id=m.source),0) AS sourceOrder FROM"
                + " ai_memory m WHERE m.id=? AND m.user_id=? AND m.deleted=FALSE",
            id,
            user);
    if (!rows.isEmpty()) candidates.put(id, rows.getFirst());
  }

  private long sourceOrder(long user, String jobId) {
    Long order =
        db.queryForObject(
            "SELECT COALESCE(MAX(id),0) FROM ai_message WHERE user_id=? AND turn_id=?",
            Long.class,
            user,
            jobId);
    return order == null ? 0 : order;
  }

  private synchronized void connect() throws Exception {
    if (producer != null) return;
    var p = new DefaultMQProducer("bit-select-ai-memory-publisher");
    p.setNamesrvAddr(namesrv);
    p.setSendMsgTimeout(5000);
    p.start();
    var c = new DefaultMQPushConsumer("bit-select-ai-memory-worker");
    c.setNamesrvAddr(namesrv);
    c.setConsumeThreadMin(1);
    c.setConsumeThreadMax(1);
    c.subscribe(TOPIC, "*");
    c.registerMessageListener(
        (MessageListenerConcurrently)
            (messages, ctx) -> {
              for (var m : messages)
                try {
                  process(new String(m.getBody(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                  return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
              return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
    try {
      c.start();
      producer = p;
      consumer = c;
    } catch (Exception e) {
      p.shutdown();
      throw e;
    }
  }

  @Scheduled(initialDelay = 30000, fixedDelay = 15000)
  public void publish() {
    if (!enabled) return;
    try {
      connect();
      var jobs =
          db.queryForList(
              "SELECT id FROM ai_memory_job WHERE attempts<8 AND (status='PENDING' OR (status IN"
                  + " ('SENT','FAILED','PROCESSING') AND updated_at <"
                  + " DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5 MINUTE))) ORDER BY created_at LIMIT 5");
      for (var j : jobs) {
        String id = j.get("id").toString();
        producer.send(new Message(TOPIC, id.getBytes(StandardCharsets.UTF_8)));
        db.update(
            "UPDATE ai_memory_job SET status='SENT',updated_at=CURRENT_TIMESTAMP WHERE id=? AND"
                + " status IN ('PENDING','SENT','FAILED')",
            id);
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .debug("Memory queue unavailable: {}", e.getClass().getSimpleName());
    }
  }

  public void process(String jobId) throws Exception {
    var jobs = db.queryForList("SELECT * FROM ai_memory_job WHERE id=?", jobId);
    if (jobs.isEmpty() || "DONE".equals(jobs.getFirst().get("status"))) return;
    var job = jobs.getFirst();
    long user = ((Number) job.get("user_id")).longValue();
    long generation = ((Number) job.get("generation")).longValue();
    if (db.update(
            "UPDATE ai_memory_job SET"
                + " status='PROCESSING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                + " AND attempts<8 AND (status IN ('PENDING','SENT','FAILED') OR"
                + " (status='PROCESSING' AND updated_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5"
                + " MINUTE)))",
            jobId)
        != 1) return;
    long incomingOrder = sourceOrder(user, jobId);
    var preferences =
        db.queryForList(
            "SELECT enabled,generation FROM ai_memory_preference WHERE user_id=?", user);
    if (!enabled
        || (!preferences.isEmpty()
            && (!Boolean.TRUE.equals(preferences.getFirst().get("enabled"))
                || ((Number) preferences.getFirst().get("generation")).longValue()
                    != generation))) {
      db.update(
          "UPDATE ai_memory_job SET status='DONE',updated_at=CURRENT_TIMESTAMP WHERE id=?", jobId);
      return;
    }

    try {
      JsonNode facts =
          model.json(
              model.complete(
                  List.of(
                      Map.of("role", "system", "content", Prompts.read("memory/extract-facts")),
                      Map.of("role", "user", "content", job.get("content").toString())),
                  1000));
      if (!facts.isArray() || facts.size() > 5)
        throw new IllegalStateException("INVALID_MEMORY_FACTS");
      for (JsonNode fact : facts) {
        String entity = fact.path("entity").asText("").strip(),
            attribute = fact.path("attribute").asText("").strip(),
            value = fact.path("value").asText("").strip();
        double confidence = fact.path("confidence").asDouble();
        if (entity.isEmpty()
            || attribute.isEmpty()
            || value.isEmpty()
            || entity.length() > 150
            || attribute.length() > 80
            || value.length() > 800
            || confidence < 0.8
            || confidence > 1) continue;
        String content = entity + " / " + attribute + "：" + value;
        var vector = model.embed(List.of(content)).getFirst();
        vectors.ensure(COLLECTION, vector.size());
        String resolvedEntity = entity, resolvedAttribute = attribute;
        for (var hit : vectors.search(COLLECTION, vector, "userId == " + user, 3)) {
          if (((Number) hit.getOrDefault("distance", 0)).doubleValue() < 0.90) continue;
          var existing =
              db.queryForList(
                  "SELECT * FROM ai_memory WHERE id=? AND user_id=? AND deleted=FALSE",
                  Objects.toString(hit.get("memoryId"), ""),
                  user);
          if (existing.isEmpty()) continue;
          var decision =
              model.json(
                  model.complete(
                      List.of(
                          Map.of(
                              "role", "system", "content", Prompts.read("memory/check-conflicts")),
                          Map.of(
                              "role",
                              "user",
                              "content",
                              "旧：" + existing.getFirst().get("content") + "\n新：" + content)),
                      300));
          if (decision.path("sameEntity").asBoolean()
              && decision.path("sameAttribute").asBoolean()
              && Set.of("duplicate", "update").contains(decision.path("relation").asText())) {
            resolvedEntity = existing.getFirst().get("entity").toString();
            resolvedAttribute = existing.getFirst().get("attribute_name").toString();
            break;
          }
        }
        final String finalEntity = resolvedEntity, finalAttribute = resolvedAttribute;
        tx.executeWithoutResult(
            s -> {
              db.update("INSERT IGNORE INTO ai_memory_preference(user_id) VALUES(?)", user);
              var pref =
                  db.queryForMap(
                      "SELECT enabled,generation FROM ai_memory_preference WHERE user_id=? FOR"
                          + " UPDATE",
                      user);
              if (!Boolean.TRUE.equals(pref.get("enabled"))
                  || ((Number) pref.get("generation")).longValue() != generation) return;
              var old =
                  db.queryForList(
                      "SELECT * FROM ai_memory WHERE user_id=? AND entity=? AND attribute_name=?"
                          + " FOR UPDATE",
                      user,
                      finalEntity,
                      finalAttribute);
              if (old.isEmpty())
                db.update(
                    "INSERT INTO"
                        + " ai_memory(id,user_id,entity,attribute_name,fact_value,content,source,confidence)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    user,
                    finalEntity,
                    finalAttribute,
                    value,
                    content,
                    jobId,
                    confidence);
              else {
                var r = old.getFirst();
                if (!MemoryPolicy.mayReplace(
                    incomingOrder,
                    sourceOrder(user, r.get("source").toString()),
                    jobId,
                    r.get("source").toString())) return;
                if (!Objects.equals(value, r.get("fact_value"))
                    || Boolean.TRUE.equals(r.get("deleted"))) {
                  db.update(
                      "INSERT INTO ai_memory_history(memory_id,user_id,content,version,source)"
                          + " VALUES(?,?,?,?,?)",
                      r.get("id"),
                      user,
                      r.get("content"),
                      r.get("version"),
                      r.get("source"));
                  db.update(
                      "UPDATE ai_memory SET"
                          + " fact_value=?,content=?,source=?,confidence=?,version=version+1,deleted=FALSE,projected=FALSE,updated_at=CURRENT_TIMESTAMP"
                          + " WHERE id=?",
                      value,
                      content,
                      jobId,
                      confidence,
                      r.get("id"));
                } else if (!jobId.equals(r.get("source"))) {
                  db.update(
                      "UPDATE ai_memory SET"
                          + " source=?,confidence=?,version=version+1,projected=FALSE,updated_at=CURRENT_TIMESTAMP"
                          + " WHERE id=?",
                      jobId,
                      confidence,
                      r.get("id"));
                }
              }
            });
      }
      db.update(
          "UPDATE ai_memory_job SET status='DONE',error_code=NULL,updated_at=CURRENT_TIMESTAMP"
              + " WHERE id=?",
          jobId);
    } catch (Exception e) {
      db.update(
          "UPDATE ai_memory_job SET status='FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE"
              + " id=?",
          e.getClass().getSimpleName(),
          jobId);
      throw e;
    }
  }

  @Scheduled(initialDelay = 45000, fixedDelay = 20000)
  public void project() {
    if (!enabled) return;
    for (var row :
        db.queryForList(
            "SELECT * FROM ai_memory WHERE projected=FALSE ORDER BY updated_at LIMIT 5"))
      try {
        long user = ((Number) row.get("user_id")).longValue(),
            version = ((Number) row.get("version")).longValue();
        String id = row.get("id").toString();
        boolean deleted = Boolean.TRUE.equals(row.get("deleted"));
        long numericId = UUID.fromString(id).getMostSignificantBits() & Long.MAX_VALUE;
        if (deleted)
          vectors.delete(COLLECTION, "userId == " + user + " and memoryId == \"" + id + "\"");
        else {
          var v = model.embed(List.of(row.get("content").toString())).getFirst();
          vectors.ensure(COLLECTION, v.size());
          vectors.upsert(
              COLLECTION,
              List.of(
                  Map.of(
                      "id",
                      numericId,
                      "vector",
                      v,
                      "userId",
                      user,
                      "memoryId",
                      id,
                      "version",
                      version)));
        }
        try (var session = graph.session()) {
          session
              .run(
                  "MERGE (u:BitUser {userId:$user}) MERGE (e:BitEntity {userId:$user,name:$entity})"
                      + " MERGE (f:BitMemory {userId:$user,memoryId:$id}) SET"
                      + " f.entity=$entity,f.attribute=$attribute,f.value=$value,f.version=$version,f.deleted=$deleted"
                      + " MERGE (u)-[:HAS_ENTITY]->(e) MERGE (e)-[:HAS_FACT]->(f)",
                  Map.of(
                      "user",
                      user,
                      "id",
                      id,
                      "entity",
                      row.get("entity"),
                      "attribute",
                      row.get("attribute_name"),
                      "value",
                      deleted ? "" : row.get("fact_value"),
                      "version",
                      version,
                      "deleted",
                      deleted))
              .consume();
        }
        db.update(
            "UPDATE ai_memory SET projected=TRUE WHERE id=? AND user_id=? AND version=?",
            id,
            user,
            version);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .debug("Memory projection pending: {}", e.getClass().getSimpleName());
      }
  }

  @PreDestroy
  public void shutdown() {
    if (producer != null) producer.shutdown();
    if (consumer != null) consumer.shutdown();
    graph.close();
  }
}
