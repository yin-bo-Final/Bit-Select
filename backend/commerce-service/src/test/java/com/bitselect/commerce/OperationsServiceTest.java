package com.bitselect.commerce;

import static org.junit.jupiter.api.Assertions.*;

import com.bitselect.contracts.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class OperationsServiceTest {
  JdbcTemplate db;
  OperationsService operations;
  ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @BeforeEach
  void setup() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:ops"
                + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V1__commerce_schema.sql"),
            new ClassPathResource("db/migration/V2__ai_schema.sql"),
            new ClassPathResource("db/migration/V3__addresses_and_returns.sql"))
        .execute(source);
    db = new JdbcTemplate(source);
    operations =
        new OperationsService(
            db,
            json,
            List.of(),
            Duration.ofMillis(100),
            Duration.ofSeconds(10),
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC));
    db.update(
        "INSERT INTO users(id,username,password_hash,nickname,role)"
            + " VALUES(1,'customer','PRIVATE_PASSWORD','PRIVATE_NICKNAME','USER'),(2,'admin','PRIVATE_ADMIN_PASSWORD','admin','ADMIN')");
    int order = 1;
    for (var status :
        List.of(
            "PENDING_PAYMENT",
            "PENDING_PAYMENT",
            "PAID",
            "SHIPPED",
            "COMPLETED",
            "CANCELLED",
            "REFUNDED")) {
      String id = id(order++);
      db.update(
          "INSERT INTO"
              + " orders(id,order_no,user_id,status,total_cents,address_json,request_key,request_hash,expires_at)"
              + " VALUES(?,?,1,?,9900,'PRIVATE_ADDRESS',?,'hash',CURRENT_TIMESTAMP)",
          id,
          id,
          status,
          id);
    }
    outbox(1, 0, null, "2024-01-01 00:00:00");
    outbox(2, 2, null, "2024-01-02 00:00:00");
    outbox(3, 1, "2024-01-05 00:00:00", "2023-01-01 00:00:00");
    memory(11, "PENDING", 0, null);
    memory(12, "SENT", 0, null);
    memory(13, "SENT", 1, null);
    memory(14, "FAILED", 3, "IllegalStateException");
    memory(15, "FAILED", 8, "DB: PRIVATE_PASSWORD");
    memory(16, "PROCESSING", 8, null);
    memory(17, "DONE", 8, null);
    db.update(
        "INSERT INTO knowledge_document(id,product_id,title,content_hash,original_text,status)"
            + " VALUES(1,1,'PRIVATE_TITLE','hash','PRIVATE_DOCUMENT','READY'),(2,2,'title','hash','PRIVATE_DOCUMENT','FAILED')");
    db.update(
        "INSERT INTO"
            + " knowledge_chunk(id,document_id,product_id,title,heading,content,start_offset,end_offset,active)"
            + " VALUES(1,1,1,'title','heading','PRIVATE_CHUNK',0,10,TRUE),(2,2,2,'title','heading','PRIVATE_CHUNK',0,10,FALSE)");
  }

  @AfterEach
  void cleanup() {
    operations.close();
  }

  static String id(int suffix) {
    return "00000000-0000-0000-0000-" + String.format("%012d", suffix);
  }

  void outbox(int suffix, int attempts, String sent, String created) {
    db.update(
        "INSERT INTO"
            + " event_outbox(id,topic,event_type,aggregate_id,payload,attempts,sent_at,created_at)"
            + " VALUES(?,'bit-commerce-events','ORDER_PAID',?,'PRIVATE_PAYLOAD',?,?,?)",
        id(suffix),
        id(100),
        attempts,
        sent,
        created);
  }

  void memory(int suffix, String status, int attempts, String error) {
    db.update(
        "INSERT INTO"
            + " ai_memory_job(id,user_id,conversation_id,content,status,attempts,error_code,created_at,updated_at)"
            + " VALUES(?,1,?,'PRIVATE_USER_MESSAGE',?,?,?,'2024-02-01 00:00:00','2024-02-02"
            + " 00:00:00')",
        id(suffix),
        id(200),
        status,
        attempts,
        error);
  }

  @Test
  void overviewUsesRealCountsAndDistinguishesProcessingFromExhaustedFailures() {
    var overview = operations.overview();
    var commerce = (Map<?, ?>) overview.get("commerce");
    assertEquals(2L, commerce.get("users"));
    assertEquals(1L, commerce.get("customers"));
    assertEquals(7L, commerce.get("orders"));
    var states = (Map<?, ?>) commerce.get("ordersByStatus");
    assertEquals(2L, states.get("PENDING_PAYMENT"));
    assertEquals(7L, states.values().stream().mapToLong(v -> ((Number) v).longValue()).sum());
    var queues = (List<?>) overview.get("queues");
    var outbox = (Map<?, ?>) queues.get(0);
    assertEquals(1L, outbox.get("pending"));
    assertEquals(1L, outbox.get("retrying"));
    assertEquals(1L, outbox.get("completed"));
    assertEquals(0L, outbox.get("processing"));
    assertEquals(0L, outbox.get("failed"));
    assertEquals(
        java.sql.Timestamp.valueOf("2024-01-01 00:00:00").toInstant(),
        outbox.get("oldestPendingAt"));
    var memory = (Map<?, ?>) queues.get(1);
    assertEquals(2L, memory.get("pending"));
    assertEquals(2L, memory.get("retrying"));
    assertEquals(1L, memory.get("processing"));
    assertEquals(1L, memory.get("failed"));
    assertEquals(1L, memory.get("completed"));
    assertEquals(7L, memory.get("total"));
    assertEquals(Map.of("documents", 2L, "indexed", 1L, "chunks", 1L), overview.get("knowledge"));
  }

  @Test
  void paginationAndFiltersAreConsistentAndExposeOnlySafeMetadata() throws Exception {
    var first = operations.events("commerce", "all", 1, 2);
    var second = operations.events("commerce", "all", 2, 2);
    assertEquals(3L, first.get("total"));
    assertEquals(2, ((List<?>) first.get("items")).size());
    assertEquals(1, ((List<?>) second.get("items")).size());
    assertNotEquals(
        ((List<?>) first.get("items")).getFirst(), ((List<?>) second.get("items")).getFirst());
    var processing = operations.events("memory", "processing", 1, 50);
    assertEquals(1L, processing.get("total"));
    assertEquals(
        id(16),
        ((OperationsService.EventMetadata) ((List<?>) processing.get("items")).getFirst()).id());
    assertEquals("retrying", operations.event("memory", id(14)).status());
    assertEquals("IllegalStateException", operations.event("memory", id(14)).errorCode());
    assertEquals("UNCLASSIFIED_ERROR", operations.event("memory", id(15)).errorCode());
    assertEquals(0L, operations.events("commerce", "processing", 1, 10).get("total"));
    assertEquals(0L, operations.events("commerce", "failed", 1, 10).get("total"));
    assertEquals(
        List.of(), operations.events("commerce", "all", Integer.MAX_VALUE, 50).get("items"));
    String serialized =
        json.writeValueAsString(
            List.of(operations.overview(), first, operations.events("memory", "all", 1, 50)));
    assertFalse(serialized.contains("PRIVATE_"));
    assertFalse(serialized.contains("payload"));
    assertFalse(serialized.contains("userId"));
    assertFalse(serialized.contains("conversation_id"));
  }

  @Test
  void invalidPaginationAndQueryFragmentsAreRejectedBeforeSqlAndMissingEventsReturn404() {
    for (int[] page :
        List.of(new int[] {0, 20}, new int[] {1, 0}, new int[] {1, 51}, new int[] {-1, 20}))
      assertEquals(
          400,
          assertThrows(
                  ApiException.class, () -> operations.events("commerce", "all", page[0], page[1]))
              .status);
    assertEquals(
        400,
        assertThrows(
                ApiException.class, () -> operations.events("commerce UNION SELECT", "all", 1, 20))
            .status);
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> operations.events("memory", "all OR 1=1", 1, 20))
            .status);
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> operations.event("commerce", "' OR 1=1 --")).status);
    assertEquals(
        404, assertThrows(ApiException.class, () -> operations.event("commerce", id(999))).status);
    assertEquals(3L, operations.events("commerce", "all", 1, 20).get("total"));
  }
}
