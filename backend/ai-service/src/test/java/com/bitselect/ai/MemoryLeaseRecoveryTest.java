package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class MemoryLeaseRecoveryTest {
  @Test
  void exhaustedCrashedAttemptBecomesFailedWithoutChangingActiveOrRetryableJobs() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE ai_memory_job(id VARCHAR(36),status VARCHAR(20),attempts INT,error_code"
            + " VARCHAR(100),updated_at TIMESTAMP)");
    var expired = Timestamp.from(Instant.now().minusSeconds(600));
    var active = Timestamp.from(Instant.now());
    db.update("INSERT INTO ai_memory_job VALUES('crashed','PROCESSING',8,NULL,?)", expired);
    db.update("INSERT INTO ai_memory_job VALUES('active','PROCESSING',8,NULL,?)", active);
    db.update("INSERT INTO ai_memory_job VALUES('retryable','PROCESSING',7,NULL,?)", expired);
    db.update("INSERT INTO ai_memory_job VALUES('done','DONE',8,NULL,?)", expired);
    var service =
        new MemoryService(
            db,
            new TransactionTemplate(),
            mock(SiliconFlowClient.class),
            mock(MilvusStore.class),
            "127.0.0.1:1",
            "bolt://127.0.0.1:1",
            "test",
            "test",
            true);
    try {
      service.reclaimExhaustedJobs();
      assertEquals(
          "FAILED",
          db.queryForObject("SELECT status FROM ai_memory_job WHERE id='crashed'", String.class));
      assertEquals(
          "ProcessingLeaseExpiredException",
          db.queryForObject(
              "SELECT error_code FROM ai_memory_job WHERE id='crashed'", String.class));
      for (String id : List.of("active", "retryable"))
        assertEquals(
            "PROCESSING",
            db.queryForObject("SELECT status FROM ai_memory_job WHERE id=?", String.class, id));
      assertEquals(
          "DONE",
          db.queryForObject("SELECT status FROM ai_memory_job WHERE id='done'", String.class));
    } finally {
      service.shutdown();
    }
  }
}
