package com.bitselect.ai;

import java.util.*;

/** Qwen3 vocabulary with per-message framing allowance; output/tool budgets are separate. */
public final class ContextWindow {
  private ContextWindow() {}

  public record Turn(String id, long lastMessageId, String content) {}

  public record Plan(List<Turn> summarize, List<Turn> retain) {}

  public static int tokens(String text) {
    return QwenTokens.count(text) + 8;
  }

  public static Plan plan(List<Turn> turns, int capacity, int fixedBudget) {
    if (capacity < 100) throw new IllegalArgumentException("capacity");
    int total = fixedBudget + turns.stream().mapToInt(t -> tokens(t.content())).sum();
    if (total < Math.floor(capacity * 0.60) || turns.size() < 2)
      return new Plan(List.of(), List.copyOf(turns));
    int retained = 0, boundary = turns.size();
    while (boundary > 0 && retained < capacity * 0.20) {
      boundary--;
      retained += tokens(turns.get(boundary).content());
    }
    return new Plan(
        List.copyOf(turns.subList(0, boundary)),
        List.copyOf(turns.subList(boundary, turns.size())));
  }
}
