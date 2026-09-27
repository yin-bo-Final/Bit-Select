package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class MemoryPolicyTest {
  private Map<String, Object> fact(String id, String entity, String attribute, long order) {
    return Map.of(
        "id",
        id,
        "entity",
        entity,
        "attribute_name",
        attribute,
        "sourceOrder",
        order,
        "updatedAt",
        "2026-09-27T00:00:00Z");
  }

  @Test
  void delayedOldJobCannotOverwriteNewFact() {
    assertFalse(MemoryPolicy.mayReplace(20, 42, "old", "new"));
    assertTrue(MemoryPolicy.mayReplace(43, 42, "newer", "new"));
    assertTrue(MemoryPolicy.mayReplace(42, 42, "same", "same"));
    assertFalse(MemoryPolicy.mayReplace(42, 42, "different", "same"));
  }

  @Test
  void canonicalDuplicateKeepsLatestSourceInsteadOfProjectionTime() {
    var facts =
        MemoryPolicy.newestFirst(
            List.of(fact("old", "Keyboard", "budget", 10), fact("new", "keyboard", "budget", 20)));
    assertEquals(List.of("new"), facts.stream().map(m -> m.get("id")).toList());
  }

  @Test
  void aliasConflictGroupsKeepOnlyNewestAndDropUncertainFacts() {
    var newest =
        MemoryPolicy.newestFirst(
            List.of(
                fact("old", "电脑键盘", "预算", 10),
                fact("new", "键盘", "价格上限", 20),
                fact("unknown", "某人", "需求", 30),
                fact("safe", "鼠标", "颜色", 15)));
    var result =
        MemoryPolicy.consistent(
            newest, List.of(List.of("old", "new", "not-real")), Set.of("unknown"));
    assertEquals(List.of("new", "safe"), result.stream().map(m -> m.get("id")).toList());
  }

  @Test
  void disablingMemorySkipsEveryModelAndVectorCall() throws Exception {
    JdbcTemplate db = mock(JdbcTemplate.class);
    SiliconFlowClient model = mock(SiliconFlowClient.class);
    MilvusStore vectors = mock(MilvusStore.class);
    when(db.queryForList("SELECT enabled FROM ai_memory_preference WHERE user_id=?", 7L))
        .thenReturn(List.of(Map.of("enabled", false)));
    var service =
        new MemoryService(
            db,
            new TransactionTemplate(),
            model,
            vectors,
            "127.0.0.1:1",
            "bolt://127.0.0.1:1",
            "neo4j",
            "test",
            true);
    try {
      assertTrue(service.recall(7, "我的预算是多少").isEmpty());
      verifyNoInteractions(model, vectors);
    } finally {
      service.shutdown();
    }
  }
}
