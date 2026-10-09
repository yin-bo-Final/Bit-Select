package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

class KnowledgeProjectionTest {
  @TempDir Path directory;

  @Test
  void shortenedManualRemovesOldVectorTailBeforePublishingNewSqlChunks() throws Exception {
    var data = directory.resolve("products.json");
    Files.writeString(data, "[{\"id\":1,\"sku\":\"demo\",\"name\":\"测试说明书\"}]");
    Files.createDirectories(directory.resolve("manuals"));
    Files.writeString(
        directory.resolve("manuals/demo.md"), "## 使用说明\n\n这是新的精简说明书。", StandardCharsets.UTF_8);
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    var db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,product_id BIGINT,title"
            + " VARCHAR(250),content_hash VARCHAR(64),original_text TEXT,status VARCHAR(20) DEFAULT"
            + " 'PENDING',chunk_count INT DEFAULT 0,error_code VARCHAR(100),updated_at TIMESTAMP"
            + " DEFAULT CURRENT_TIMESTAMP)");
    db.execute(
        "CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,product_id"
            + " BIGINT,title VARCHAR(250),heading VARCHAR(250),content TEXT,start_offset"
            + " INT,end_offset INT,embedding_json TEXT)");
    db.update(
        "INSERT INTO"
            + " knowledge_document(id,product_id,title,content_hash,original_text,status,chunk_count)"
            + " VALUES(1,1,'旧说明书','old','旧正文','READY',3)");
    db.update(
        "INSERT INTO knowledge_chunk(id,document_id,product_id,content) VALUES(10000,1,1,'旧片段')");
    db.update(
        "INSERT INTO knowledge_chunk(id,document_id,product_id,content) VALUES(10001,1,1,'旧尾片段')");
    var model = mock(SiliconFlowClient.class);
    when(model.embed(anyList()))
        .thenAnswer(
            call -> {
              List<String> texts = call.getArgument(0);
              return Collections.nCopies(texts.size(), List.of(0.5f, 0.5f));
            });
    var vectors = mock(MilvusStore.class);
    Map<Long, Map<String, Object>> projection = new HashMap<>();
    projection.put(10000L, Map.of("id", 10000L, "documentId", 1L));
    projection.put(10001L, Map.of("id", 10001L, "documentId", 1L));
    projection.put(20000L, Map.of("id", 20000L, "documentId", 2L));
    doAnswer(
            call -> {
              assertEquals(
                  "PROCESSING",
                  db.queryForObject(
                      "SELECT status FROM knowledge_document WHERE id=1", String.class));
              projection
                  .entrySet()
                  .removeIf(e -> Objects.equals(e.getValue().get("documentId"), 1L));
              return null;
            })
        .when(vectors)
        .delete(KnowledgeService.COLLECTION, "documentId == 1");
    doAnswer(
            call -> {
              List<Map<String, Object>> records = call.getArgument(1);
              records.forEach(
                  record -> projection.put(((Number) record.get("id")).longValue(), record));
              return null;
            })
        .when(vectors)
        .upsert(eq(KnowledgeService.COLLECTION), anyList());
    var tika = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    tika.createContext(
        "/tika",
        exchange -> {
          byte[] parsed = exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, parsed.length);
          exchange.getResponseBody().write(parsed);
          exchange.close();
        });
    tika.start();
    try {
      var service =
          new KnowledgeService(
              db,
              new ObjectMapper(),
              model,
              vectors,
              new TransactionTemplate(new DataSourceTransactionManager(source)),
              data.toString(),
              "http://127.0.0.1:" + tika.getAddress().getPort(),
              "http://127.0.0.1:1",
              "fake",
              "fake",
              "test");
      // Replace object storage with a local test double before any operation can access the
      // network.
      ((S3Client) ReflectionTestUtils.getField(service, "objects")).close();
      ReflectionTestUtils.setField(service, "objects", mock(S3Client.class));
      service.indexAll();
      assertEquals(
          "READY",
          db.queryForObject("SELECT status FROM knowledge_document WHERE id=1", String.class));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id=1", Integer.class));
      assertEquals(
          Set.of(10000L, 20000L),
          projection.keySet(),
          "Other documents remain; stale tail is removed");
      var ordered = inOrder(vectors);
      ordered.verify(vectors).delete(KnowledgeService.COLLECTION, "documentId == 1");
      ordered.verify(vectors).upsert(eq(KnowledgeService.COLLECTION), anyList());
      verify(model, times(2)).embed(anyList());
    } finally {
      tika.stop(0);
    }
  }
}
