package com.bitselect.ai;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
  private final JdbcTemplate db;
  private final SiliconFlowClient model;
  private final MilvusStore vectors;
  private final LambdaMartRanker ranker;

  public RetrievalService(
      JdbcTemplate db, SiliconFlowClient model, MilvusStore vectors, LambdaMartRanker ranker) {
    this.db = db;
    this.model = model;
    this.vectors = vectors;
    this.ranker = ranker;
  }

  public List<Map<String, Object>> search(String query) throws Exception {
    return retrieve(query, null);
  }

  /** A scoped search never falls back to another SKU's manual, even when no matches exist. */
  public List<Map<String, Object>> search(String query, Collection<Long> productIds)
      throws Exception {
    var allowed =
        productIds.stream()
            .filter(Objects::nonNull)
            .filter(id -> id > 0)
            .distinct()
            .sorted()
            .toList();
    return allowed.isEmpty() ? List.of() : retrieve(query, allowed);
  }

  private List<Map<String, Object>> retrieve(String query, List<Long> allowed) throws Exception {
    String sql =
        "SELECT c.* FROM knowledge_chunk c JOIN knowledge_document d ON c.document_id=d.id WHERE"
            + " c.active=TRUE AND d.status='READY'";
    List<Map<String, Object>> rows;
    if (allowed == null) rows = db.queryForList(sql);
    else
      rows =
          db.queryForList(
              sql
                  + " AND c.product_id IN ("
                  + String.join(",", Collections.nCopies(allowed.size(), "?"))
                  + ")",
              allowed.toArray());
    if (rows.isEmpty()) return List.of();
    Map<Long, Map<String, Object>> indexed = new HashMap<>();
    Map<Long, String> docs = new HashMap<>();
    for (var row : rows) {
      long id = ((Number) row.get("id")).longValue();
      indexed.put(id, row);
      docs.put(id, row.get("title") + " " + row.get("content"));
    }
    Map<Long, Double> bm = Bm25.rank(query, docs), semantic = new HashMap<>();
    Set<Long> candidates = new LinkedHashSet<>();
    var lexical =
        bm.entrySet().stream()
            .sorted(
                Map.Entry.<Long, Double>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .toList();
    lexical.stream().limit(10).forEach(e -> candidates.add(e.getKey()));
    String filter =
        allowed == null
            ? ""
            : "productId in ["
                + String.join(",", allowed.stream().map(Object::toString).toList())
                + "]";
    for (var hit :
        vectors.search(
            KnowledgeService.COLLECTION, model.embed(List.of(query)).getFirst(), filter, 10)) {
      long id = ((Number) hit.get("id")).longValue();
      if (indexed.containsKey(id)) {
        candidates.add(id);
        semantic.put(id, ((Number) hit.getOrDefault("distance", 0.0)).doubleValue());
      }
    }
    // Guarantee evidence coverage for every selected product, not just the most similar SKU.
    if (allowed != null)
      for (long product : allowed)
        lexical.stream()
            .filter(
                e -> ((Number) indexed.get(e.getKey()).get("product_id")).longValue() == product)
            .findFirst()
            .ifPresent(e -> candidates.add(e.getKey()));
    var ids = new ArrayList<>(candidates);
    if (ids.isEmpty()) return List.of();
    var rerank = model.rerank(query, ids.stream().map(docs::get).toList());
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      long id = ids.get(i);
      var row = indexed.get(id);
      double b = bm.getOrDefault(id, 0.0),
          c = semantic.getOrDefault(id, 0.0),
          r = rerank.getOrDefault(i, 0.0);
      double exact = query.contains(row.get("title").toString().split(" · ")[0]) ? 1 : 0;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("productId", row.get("product_id"));
      item.put("title", row.get("title"));
      item.put("heading", row.get("heading"));
      item.put("excerpt", row.get("content"));
      item.put("score", ranker.score(new double[] {b, c, r, exact}));
      item.put("bm25Score", b);
      item.put("cosineScore", c);
      item.put("rerankScore", r);
      item.put("rankingMode", ranker.mode());
      result.add(item);
    }
    result.sort(
        Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("score")).doubleValue())
            .reversed()
            .thenComparing(
                Comparator.comparingDouble(
                        (Map<String, Object> m) -> ((Number) m.get("rerankScore")).doubleValue())
                    .reversed())
            .thenComparing(
                Comparator.comparingDouble(
                        (Map<String, Object> m) -> ((Number) m.get("cosineScore")).doubleValue())
                    .reversed())
            .thenComparingLong(m -> ((Number) m.get("id")).longValue()));
    if (allowed == null) return result.stream().limit(5).toList();
    Map<Long, Integer> counts = new HashMap<>();
    return result.stream()
        .filter(
            item ->
                counts.merge(((Number) item.get("productId")).longValue(), 1, Integer::sum) <= 2)
        .toList();
  }
}
