package com.bitselect.ai;

import java.util.*;

public final class Bm25 {
  private Bm25() {}

  public static List<String> tokens(String text) {
    List<String> result = new ArrayList<>();
    var m =
        java.util.regex.Pattern.compile(
                "[a-z0-9]+|[\\p{IsHan}]+", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text.toLowerCase(Locale.ROOT));
    while (m.find()) {
      String part = m.group();
      if (part.matches("[a-z0-9]+")) result.add(part);
      else {
        int[] c = part.codePoints().toArray();
        for (int i = 0; i < c.length; i++) {
          result.add(new String(c, i, 1));
          if (i + 1 < c.length) result.add(new String(c, i, 2));
        }
      }
    }
    return result;
  }

  public static Map<Long, Double> rank(String query, Map<Long, String> documents) {
    var q = new HashSet<>(tokens(query));
    Map<Long, List<String>> parts = new HashMap<>();
    Map<String, Integer> df = new HashMap<>();
    double sum = 0;
    for (var d : documents.entrySet()) {
      var t = tokens(d.getValue());
      parts.put(d.getKey(), t);
      sum += t.size();
      for (String term : new HashSet<>(t)) df.merge(term, 1, Integer::sum);
    }
    double avg = sum / Math.max(1, documents.size());
    Map<Long, Double> scores = new HashMap<>();
    for (var d : parts.entrySet()) {
      Map<String, Integer> counts = new HashMap<>();
      d.getValue().forEach(t -> counts.merge(t, 1, Integer::sum));
      double score = 0;
      for (String term : q) {
        int f = counts.getOrDefault(term, 0);
        double idf =
            Math.log(
                1
                    + (documents.size() - df.getOrDefault(term, 0) + 0.5)
                        / (df.getOrDefault(term, 0) + 0.5));
        score +=
            idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * d.getValue().size() / Math.max(avg, 1)));
      }
      if (score > 0) scores.put(d.getKey(), score);
    }
    return scores;
  }
}
