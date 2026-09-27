package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LambdaMartRankerTest {
  @Test
  void matchesNativeLightGbmOnRealAndSyntheticTreeBoundaries() throws Exception {
    var json = new ObjectMapper();
    for (String directory : new String[] {"../../data/ranking", "../../data/ranking/fixtures"}) {
      Path root = Path.of(directory);
      var fixtures = json.readTree(root.resolve("equivalence-fixtures.json").toFile());
      var ranker = new LambdaMartRanker(json, root.resolve("model.json").toString());
      assertEquals("LAMBDAMART", ranker.mode());
      assertTrue(fixtures.path("cases").size() >= 20);
      for (var sample : fixtures.path("cases")) {
        double[] features = json.convertValue(sample.path("features"), double[].class);
        assertEquals(sample.path("expectedScore").asDouble(), ranker.score(features), 1e-10);
      }
    }
  }

  @Test
  void missingModelIsExplicitRerankerFallback() throws Exception {
    var ranker = new LambdaMartRanker(new ObjectMapper(), "target/absent-ranking-model.json");
    assertEquals("RERANK_BASELINE", ranker.mode());
    assertEquals(0.7, ranker.score(new double[] {2, 0.6, 0.7, 0}));
  }
}
