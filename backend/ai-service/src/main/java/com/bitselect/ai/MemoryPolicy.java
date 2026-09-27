package com.bitselect.ai;

import java.text.Normalizer;
import java.util.*;

/** Resolve memory conflicts using event order, never queue delivery order. */
public final class MemoryPolicy {
  private MemoryPolicy() {}

  public static boolean mayReplace(
      long incoming, long current, String incomingId, String currentId) {
    return incomingId.equals(currentId) || incoming > current;
  }

  private static String canonical(Object value) {
    return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .strip();
  }

  public static List<Map<String, Object>> newestFirst(Collection<Map<String, Object>> candidates) {
    var sorted = new ArrayList<>(candidates);
    sorted.sort(
        Comparator.comparingLong(
                (Map<String, Object> m) -> ((Number) m.getOrDefault("sourceOrder", 0L)).longValue())
            .reversed()
            .thenComparing(
                m -> Objects.toString(m.get("updatedAt"), ""), Comparator.reverseOrder()));
    Set<String> seen = new HashSet<>();
    return sorted.stream()
        .filter(
            m -> seen.add(canonical(m.get("entity")) + "|" + canonical(m.get("attribute_name"))))
        .limit(20)
        .toList();
  }

  public static List<Map<String, Object>> consistent(
      List<Map<String, Object>> newest, List<List<String>> groups, Set<String> uncertain) {
    Map<String, String> parent = new HashMap<>();
    newest.forEach(m -> parent.put(m.get("id").toString(), m.get("id").toString()));
    for (var group : groups) {
      String first = null;
      for (String id : group) {
        if (!parent.containsKey(id)) continue;
        if (first == null) first = id;
        else parent.put(root(parent, id), root(parent, first));
      }
    }
    Set<String> seen = new HashSet<>();
    List<Map<String, Object>> out = new ArrayList<>();
    for (var m : newest) {
      String id = m.get("id").toString();
      if (uncertain.contains(id) || !seen.add(root(parent, id))) continue;
      out.add(m);
      if (out.size() == 12) break;
    }
    return out;
  }

  private static String root(Map<String, String> p, String id) {
    while (!p.get(id).equals(id)) id = p.get(id);
    return id;
  }
}
