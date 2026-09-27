package com.bitselect.ai;

import java.util.*;

public final class SemanticChunker {
  private SemanticChunker() {}

  public record Chunk(String heading, String content, int start, int end) {}

  public static double cosine(List<Float> a, List<Float> b) {
    double dot = 0, aa = 0, bb = 0;
    for (int i = 0; i < a.size(); i++) {
      dot += a.get(i) * b.get(i);
      aa += a.get(i) * a.get(i);
      bb += b.get(i) * b.get(i);
    }
    return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
  }

  public static List<String> paragraphs(String text) {
    return Arrays.stream(text.replace("\r\n", "\n").split("\n\\s*\n"))
        .map(String::strip)
        .filter(s -> !s.isBlank())
        .toList();
  }

  public static List<Chunk> split(String text, List<List<Float>> vectors) {
    var parts = paragraphs(text);
    if (parts.size() != vectors.size()) throw new IllegalArgumentException("paragraph vectors");
    List<int[]> blocks = new ArrayList<>();
    int start = 0, length = 0, cursor = 0;
    String previous = "";
    List<String> headings = new ArrayList<>();
    String heading = "产品概述";
    for (int i = 0; i < parts.size(); i++) {
      String p = parts.get(i);
      int offset = text.indexOf(p, cursor);
      int budget = ContextWindow.tokens(p);
      boolean boundary =
          i > 0
              && (length + budget > 1000
                  || (length > 600
                      && (p.startsWith("##")
                          || cosine(vectors.get(i - 1), vectors.get(i)) < 0.66)));
      if (boundary) {
        blocks.add(new int[] {start, cursor});
        headings.add(heading);
        start = offset;
        length = 0;
      }
      if (p.startsWith("##"))
        heading = p.lines().findFirst().orElse("说明").replaceFirst("^#+\\s*", "");
      length += budget;
      cursor = offset + p.length();
      previous = p;
    }
    if (cursor > start) {
      blocks.add(new int[] {start, cursor});
      headings.add(heading);
    }
    List<Chunk> out = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++) {
      var block = blocks.get(i);
      int left = QwenTokens.extend(text, block[0], -1, 200),
          right = QwenTokens.extend(text, block[1], 1, 200);
      out.add(new Chunk(headings.get(i), text.substring(left, right), block[0], block[1]));
    }
    return out;
  }
}
