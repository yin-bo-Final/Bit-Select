package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationCompressionTest {
  @Test
  void persistsWholeTurnBoundaryAndReusesSummaryWithoutOldMessages() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    var db = new JdbcTemplate(source);
    db.execute(
        "CREATE TABLE ai_conversation(id VARCHAR(36) PRIMARY KEY,user_id BIGINT,title"
            + " VARCHAR(100),summary TEXT,summary_through BIGINT DEFAULT 0,revision BIGINT DEFAULT"
            + " 0)");
    db.execute(
        "CREATE TABLE ai_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,conversation_id"
            + " VARCHAR(36),user_id BIGINT,turn_id VARCHAR(36),role VARCHAR(20),content TEXT)");
    var model = mock(SiliconFlowClient.class);
    when(model.complete(anyList(), eq(1200))).thenReturn("用户早期偏好静音键盘，预算300元。最新决定应覆盖此摘要。");
    var store =
        new ConversationStore(
            db,
            model,
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            new ObjectMapper(),
            10000);
    String id = store.ensure(7, null, "选键盘");
    for (int turn = 0; turn < 20; turn++) {
      db.update(
          "INSERT INTO ai_message(conversation_id,user_id,turn_id,role,content)"
              + " VALUES(?,?,?,'user',?)",
          id,
          7,
          "turn-" + turn,
          "用户轮次" + turn + "：" + "键盘预算300元，优先静音。".repeat(120));
      db.update(
          "INSERT INTO ai_message(conversation_id,user_id,turn_id,role,content)"
              + " VALUES(?,?,?,'assistant',?)",
          id,
          7,
          "turn-" + turn,
          "助手轮次" + turn + "：" + "请比较配列和手感。".repeat(120));
    }
    var messages = store.context(7, id, "现在预算改为200元", "当前可售商品");
    long through = ((Number) store.owned(7, id).get("summary_through")).longValue();
    assertTrue(through > 0 && through < 40);
    assertEquals(0, through % 2, "摘要必须在完整问答轮次后结束");
    assertEquals(42 - through, messages.size());
    assertTrue(messages.getFirst().get("content").contains("用户早期偏好"));
    assertEquals("user", messages.get(1).get("role"));
    assertTrue(messages.get(messages.size() - 2).get("content").startsWith("助手轮次19"));
    assertTrue(messages.stream().noneMatch(m -> m.get("content").startsWith("用户轮次0：")));
    var reused = store.context(7, id, "继续比较", "当前可售商品");
    assertEquals(messages.size(), reused.size());
    assertTrue(reused.getFirst().get("content").contains("用户早期偏好"));
    assertThrows(IllegalArgumentException.class, () -> store.context(8, id, "越权", ""));
    verify(model, times(1)).complete(anyList(), eq(1200));
    assertEquals(through, ((Number) store.owned(7, id).get("summary_through")).longValue());
  }
}
