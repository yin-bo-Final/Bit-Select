package com.bitselect.ai;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LambdaMartRanker {
  private final JsonNode model;

  public LambdaMartRanker(
      ObjectMapper json, @Value("${AI_RANKING_MODEL:../data/ranking/model.json}") String path)
      throws Exception {
    model = Files.exists(Path.of(path)) ? json.readTree(Path.of(path).toFile()) : null;
  }

  public String mode() {
    return model == null ? "RERANK_BASELINE" : "LAMBDAMART";
  }

  public double score(double[] features) {
    if (model == null) return features[2];
    double score = 0;
    for (JsonNode t : model.path("tree_info")) score += predict(t.path("tree_structure"), features);
    return score;
  }

  private double predict(JsonNode node, double[] f) {
    if (node.has("leaf_value")) return node.path("leaf_value").asDouble();
    int index = node.path("split_feature").asInt();
    if (!node.path("decision_type").asText("<=").equals("<="))
      throw new IllegalStateException("UNSUPPORTED_RANK_TREE");
    return predict(
        node.path(f[index] <= node.path("threshold").asDouble() ? "left_child" : "right_child"), f);
  }
}
