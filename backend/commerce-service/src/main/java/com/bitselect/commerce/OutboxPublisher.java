package com.bitselect.commerce;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
  private final JdbcTemplate db;
  private final DefaultMQProducer producer;

  public OutboxPublisher(JdbcTemplate db, @Value("${app.rocketmq.namesrv}") String namesrv) {
    this.db = db;
    producer = new DefaultMQProducer("bit-commerce-outbox");
    producer.setNamesrvAddr(namesrv);
    producer.setSendMsgTimeout(3000);
    producer.setRetryTimesWhenSendFailed(0);
  }

  @PostConstruct
  public void start() throws Exception {
    producer.start();
  }

  @PreDestroy
  public void stop() {
    producer.shutdown();
  }

  @Scheduled(fixedDelay = 3000)
  @Transactional
  public void publish() {
    var rows =
        db.queryForList(
            "SELECT id,topic,event_type,payload FROM event_outbox WHERE sent_at IS NULL ORDER BY"
                + " created_at LIMIT 20 FOR UPDATE SKIP LOCKED");
    for (var r : rows) {
      String id = (String) r.get("id");
      try {
        var result =
            producer.send(
                new Message(
                    (String) r.get("topic"),
                    (String) r.get("event_type"),
                    id,
                    ((String) r.get("payload")).getBytes(StandardCharsets.UTF_8)));
        if (result.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK)
          throw new IllegalStateException("Broker did not acknowledge durable send");
        db.update(
            "UPDATE event_outbox SET sent_at=CURRENT_TIMESTAMP,attempts=attempts+1 WHERE id=?", id);
      } catch (Exception e) {
        db.update("UPDATE event_outbox SET attempts=attempts+1 WHERE id=?", id);
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Outbox delivery pending for {} ({})", id, e.getClass().getSimpleName());
        break;
      }
    }
  }
}
