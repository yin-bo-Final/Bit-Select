package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class QwenTokensTest {
  @Test
  void matchesUpstreamHuggingFaceTokenizer() throws Exception {
    var fixtures =
        new ObjectMapper()
            .readTree(getClass().getResourceAsStream("/qwen-tokenizer-fixtures.json"));
    for (var row : fixtures)
      assertEquals(
          row.path("count").asInt(),
          QwenTokens.count(row.path("text").asText()),
          row.path("text").asText().substring(0, Math.min(30, row.path("text").asText().length())));
  }

  @Test
  void overlapDoesNotExceedTwoHundredModelTokens() {
    String text = "请比较这两款充电器的额定功率与接口。".repeat(100);
    int at = text.length() / 2;
    int left = QwenTokens.extend(text, at, -1, 200), right = QwenTokens.extend(text, at, 1, 200);
    assertTrue(QwenTokens.count(text.substring(left, at)) <= 200);
    assertTrue(QwenTokens.count(text.substring(at, right)) <= 200);
    assertTrue(at - left > 200);
  }
}
