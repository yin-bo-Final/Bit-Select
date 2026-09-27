package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RetrievalAlgorithmsTest {
  @Test
  void exactChineseTermsRankBeforeUnrelated() {
    var ranked = Bm25.rank("USB充电器", Map.of(1L, "USB-C 氮化镓充电器 65W", 2L, "记忆棉枕 卧室"));
    assertTrue(ranked.get(1L) > ranked.getOrDefault(2L, 0.0));
  }

  @Test
  void semanticBreaksKeepWholeParagraphs() {
    String text = "# 概述\n\n" + "甲".repeat(700) + "\n\n## 参数\n\n" + "乙".repeat(700);
    var parts = SemanticChunker.paragraphs(text);
    List<List<Float>> v = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) v.add(i < 2 ? List.of(1f, 0f) : List.of(0f, 1f));
    var chunks = SemanticChunker.split(text, v);
    assertTrue(chunks.size() >= 2);
    assertEquals(0, chunks.getFirst().start());
    assertEquals(text.length(), chunks.getLast().end());
  }
}
