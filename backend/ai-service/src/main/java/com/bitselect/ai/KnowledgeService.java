package com.bitselect.ai;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Service
public class KnowledgeService {
  static final String COLLECTION = "bit_knowledge_qwen4b_v2";
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final SiliconFlowClient model;
  private final MilvusStore vectors;
  private final TransactionTemplate tx;
  private final Path data;
  private final String tika, bucket;
  private final S3Client objects;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public KnowledgeService(
      JdbcTemplate db,
      ObjectMapper json,
      SiliconFlowClient model,
      MilvusStore vectors,
      TransactionTemplate tx,
      @Value("${ai.data-path}") String data,
      @Value("${ai.tika-url}") String tika,
      @Value("${ai.rustfs-url}") String endpoint,
      @Value("${ai.rustfs-access-key}") String access,
      @Value("${ai.rustfs-secret-key}") String secret,
      @Value("${ai.rustfs-bucket}") String bucket) {
    this.db = db;
    this.json = json;
    this.model = model;
    this.vectors = vectors;
    this.tx = tx;
    this.data = Path.of(data).toAbsolutePath();
    this.tika = tika;
    this.bucket = bucket;
    objects =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        access.isBlank() ? "unconfigured" : access,
                        secret.isBlank() ? "unconfigured" : secret)))
            .build();
  }

  public Map<String, Object> status() {
    return Map.of(
        "running",
        running.get(),
        "documents",
        db.queryForList(
            "SELECT id,product_id AS productId,title,status,chunk_count AS chunkCount,error_code AS"
                + " errorCode,updated_at AS updatedAt FROM knowledge_document ORDER BY id"));
  }

  public boolean indexAsync() {
    if (!running.compareAndSet(false, true)) return false;
    Thread.startVirtualThread(
        () -> {
          try {
            indexAll();
          } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Knowledge job failed: {}", e.getClass().getSimpleName());
          } finally {
            running.set(false);
          }
        });
    return true;
  }

  public void indexAll() throws Exception {
    JsonNode products = json.readTree(data.toFile());
    for (JsonNode product : products) {
      long id = product.path("id").asLong();
      String title = product.path("name").asText();
      Path path = data.getParent().resolve("manuals").resolve(product.path("sku").asText() + ".md");
      String raw = Files.readString(path);
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          ("qwen-token-boundaries-v2\n" + raw)
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var old =
          db.queryForList("SELECT content_hash,status FROM knowledge_document WHERE id=?", id);
      if (!old.isEmpty()
          && hash.equals(old.getFirst().get("content_hash"))
          && "READY".equals(old.getFirst().get("status"))) continue;
      if (old.isEmpty())
        db.update(
            "INSERT INTO knowledge_document(id,product_id,title,content_hash,original_text)"
                + " VALUES(?,?,?,?,?)",
            id,
            id,
            title,
            hash,
            raw);
      db.update("UPDATE knowledge_document SET status='PROCESSING',error_code=NULL WHERE id=?", id);
      try {
        objects.putObject(
            b ->
                b.bucket(bucket)
                    .key("manuals/" + id + "/" + hash + ".md")
                    .contentType("text/markdown; charset=utf-8"),
            RequestBody.fromString(raw));
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create(tika + "/tika"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Accept", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofString(raw))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().isBlank())
          throw new IllegalStateException("TIKA_PARSE_FAILED");
        String cleaned =
            response
                .body()
                .replace("\r\n", "\n")
                .replace("\u0000", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .strip();
        var paragraphs = SemanticChunker.paragraphs(cleaned);
        var paragraphVectors = model.embed(paragraphs);
        var chunks = SemanticChunker.split(cleaned, paragraphVectors);
        var embeddings = model.embed(chunks.stream().map(SemanticChunker.Chunk::content).toList());
        vectors.ensure(COLLECTION, embeddings.getFirst().size());
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++)
          records.add(
              Map.of(
                  "id",
                  id * 10000 + i,
                  "vector",
                  embeddings.get(i),
                  "productId",
                  id,
                  "documentId",
                  id));
        vectors.upsert(COLLECTION, records);
        tx.executeWithoutResult(
            status -> {
              db.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
              for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                try {
                  db.update(
                      "INSERT INTO"
                          + " knowledge_chunk(id,document_id,product_id,title,heading,content,start_offset,end_offset,embedding_json)"
                          + " VALUES(?,?,?,?,?,?,?,?,?)",
                      id * 10000 + i,
                      id,
                      id,
                      title,
                      chunk.heading(),
                      chunk.content(),
                      chunk.start(),
                      chunk.end(),
                      json.writeValueAsString(embeddings.get(i)));
                } catch (Exception e) {
                  throw new IllegalStateException("CHUNK_SAVE_FAILED", e);
                }
              }
              db.update(
                  "UPDATE knowledge_document SET"
                      + " content_hash=?,original_text=?,status='READY',chunk_count=?,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE id=?",
                  hash,
                  raw,
                  chunks.size(),
                  id);
            });
      } catch (Exception e) {
        db.update(
            "UPDATE knowledge_document SET"
                + " status='FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            e.getClass().getSimpleName(),
            id);
      }
    }
  }
}
