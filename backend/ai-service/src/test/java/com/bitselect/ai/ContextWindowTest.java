package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ContextWindowTest {
  @Test
  void preservesMostRecentWholeTurns() {
    List<ContextWindow.Turn> turns = new ArrayList<>();
    for (int i = 0; i < 20; i++) turns.add(new ContextWindow.Turn("turn-" + i, i, "x".repeat(263)));
    int unit = ContextWindow.tokens(turns.getFirst().content());
    var plan = ContextWindow.plan(turns, unit * 37, unit * 10);
    assertEquals(8, plan.retain().size());
    assertEquals(12, plan.summarize().size());
    assertEquals("turn-12", plan.retain().getFirst().id());
    assertEquals("turn-19", plan.retain().getLast().id());
  }

  @Test
  void doesNotSummarizeBelowThreshold() {
    assertTrue(
        ContextWindow.plan(List.of(new ContextWindow.Turn("a", 1, "short")), 10000, 100)
            .summarize()
            .isEmpty());
  }

  @Test
  void usesModelTokenizer() {
    assertEquals(QwenTokens.count("中文") + 8, ContextWindow.tokens("中文"));
    assertTrue(QwenTokens.count("中文") < 6);
  }
}
