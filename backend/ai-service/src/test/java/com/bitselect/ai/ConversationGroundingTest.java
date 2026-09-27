package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationGroundingTest {
  @Test
  void rewriteKeepsWholeRecentTurnsWithoutCitationPayloads() {
    var history =
        List.<Map<String, Object>>of(
            Map.of("role", "user", "content", "旧问题很长"),
            Map.of("role", "assistant", "content", "旧回答"),
            Map.of("role", "user", "content", "预算300元耳机"),
            Map.of(
                "role", "assistant", "content", "商品回答", "sources", List.of("说明书".repeat(10000))));
    var recent = ConversationStore.rewriteMessages(history, 16);
    assertEquals(
        List.of(
            Map.of("role", "user", "content", "预算300元耳机"),
            Map.of("role", "assistant", "content", "商品回答")),
        recent);
    assertTrue(recent.stream().allMatch(m -> m.keySet().equals(Set.of("role", "content"))));
  }

  @Test
  void recentHistoryAndBudgetAreOwnedAndIgnoreAssistantAmounts() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:grounding"
                + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa",
            "");
    var db = new JdbcTemplate(source);
    db.execute("CREATE TABLE ai_conversation(id VARCHAR(36),user_id BIGINT)");
    db.execute(
        "CREATE TABLE ai_message(id BIGINT PRIMARY KEY,conversation_id VARCHAR(36),user_id"
            + " BIGINT,role VARCHAR(16),content VARCHAR(1000))");
    db.update("INSERT INTO ai_conversation VALUES('own',1),('other',2)");
    db.update(
        "INSERT INTO ai_message"
            + " VALUES(1,'own',1,'user','预算300元'),(2,'own',1,'assistant','预算上限99999元'),(3,'other',2,'user','预算1元')");
    var store =
        new ConversationStore(
            db,
            mock(SiliconFlowClient.class),
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            new ObjectMapper(),
            262144);
    assertEquals(30000L, store.budget(1, "own", "还有其他的吗"));
    assertEquals(10000L, store.budget(1, "own", "预算为100元"));
    assertEquals(2, store.recentMessages(1, "own").size());
    assertThrows(IllegalArgumentException.class, () -> store.budget(1, "other", "还有吗"));
    assertThrows(IllegalArgumentException.class, () -> store.recentMessages(1, "other"));
  }
}
