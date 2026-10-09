package com.bitselect.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig(OutboxPublisherTest.Config.class)
class OutboxPublisherTest {
  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSource source() {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__commerce_schema.sql"))
          .execute(source);
      return source;
    }

    @Bean
    JdbcTemplate db(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    PlatformTransactionManager manager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    DefaultMQProducer producer() {
      return mock(DefaultMQProducer.class);
    }

    @Bean
    OutboxPublisher publisher(JdbcTemplate db, DefaultMQProducer producer) {
      return new OutboxPublisher(db, producer);
    }
  }

  @Autowired JdbcTemplate db;
  @Autowired DefaultMQProducer producer;
  @Autowired OutboxPublisher publisher;

  @BeforeEach
  void setup() {
    db.update("DELETE FROM event_outbox");
    reset(producer);
  }

  void add(int id, String topic) {
    db.update(
        "INSERT INTO event_outbox(id,topic,event_type,aggregate_id,payload,created_at)"
            + " VALUES(?,?,'OrderPaid','order','{}',?)",
        String.valueOf(id),
        topic,
        Timestamp.from(Instant.parse("2025-01-01T00:00:00Z").plusSeconds(id)));
  }

  SendResult result(SendStatus status) {
    var result = new SendResult();
    result.setSendStatus(status);
    return result;
  }

  @Test
  void failedFirstRecordCannotBlockAnotherTopicInTheSameBatch() throws Exception {
    add(1, "invalid-topic");
    add(2, "valid-topic");
    when(producer.send(any(Message.class)))
        .thenAnswer(
            call -> {
              Message message = call.getArgument(0);
              if (message.getTopic().equals("invalid-topic"))
                throw new IllegalArgumentException("Invalid topic");
              return result(SendStatus.SEND_OK);
            });
    publisher.publish();
    assertEquals(
        1L,
        db.queryForObject(
            "SELECT COUNT(*) FROM event_outbox WHERE id='2' AND sent_at IS NOT NULL", Long.class));
    assertEquals(
        1, db.queryForObject("SELECT attempts FROM event_outbox WHERE id='1'", Integer.class));
  }

  @Test
  void fullPageOfPoisonRecordsCannotStarveLaterEvents() throws Exception {
    for (int i = 1; i <= 20; i++) add(i, "invalid-topic");
    add(21, "valid-topic");
    when(producer.send(any(Message.class)))
        .thenAnswer(
            call -> {
              Message message = call.getArgument(0);
              if (message.getTopic().equals("invalid-topic"))
                throw new IllegalArgumentException("Invalid topic");
              return result(SendStatus.SEND_OK);
            });
    publisher.publish();
    publisher.publish();
    assertEquals(
        1L,
        db.queryForObject(
            "SELECT COUNT(*) FROM event_outbox WHERE id='21' AND sent_at IS NOT NULL", Long.class));
  }

  @Test
  void networkSendDoesNotHoldDatabaseTransactionAndOnlyAcknowledgedSendsComplete()
      throws Exception {
    add(1, "valid-topic");
    when(producer.send(any(Message.class)))
        .thenAnswer(
            call -> {
              assertFalse(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "Slow broker must not hold database locks");
              return result(SendStatus.FLUSH_DISK_TIMEOUT);
            });
    publisher.publish();
    assertEquals(
        0L,
        db.queryForObject(
            "SELECT COUNT(*) FROM event_outbox WHERE sent_at IS NOT NULL", Long.class));
    when(producer.send(any(Message.class))).thenReturn(result(SendStatus.SEND_OK));
    publisher.publish();
    publisher.publish();
    assertEquals(
        1L,
        db.queryForObject(
            "SELECT COUNT(*) FROM event_outbox WHERE sent_at IS NOT NULL", Long.class));
    assertEquals(
        2, db.queryForObject("SELECT attempts FROM event_outbox WHERE id='1'", Integer.class));
    verify(producer, times(2)).send(argThat((Message message) -> message.getKeys().equals("1")));
  }
}
