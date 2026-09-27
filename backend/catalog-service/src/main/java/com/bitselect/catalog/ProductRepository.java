package com.bitselect.catalog;

import com.bitselect.contracts.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
  private final JdbcTemplate db;

  public ProductRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> map(ResultSet rs, int row) throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", rs.getLong("id"));
    p.put("name", rs.getString("name"));
    p.put("category", rs.getString("category"));
    p.put("categoryName", rs.getString("category_name"));
    p.put("description", rs.getString("description"));
    p.put("priceCents", rs.getLong("price_cents"));
    p.put("originalPriceCents", rs.getLong("original_price_cents"));
    p.put("imageUrl", rs.getString("image_url"));
    p.put("specifications", Json.read(rs.getString("specifications")));
    p.put("tags", Json.read(rs.getString("tags")));
    p.put("manualUrl", rs.getString("manual_url"));
    p.put("stock", rs.getInt("stock"));
    p.put("sold", rs.getLong("sold"));
    p.put("featured", rs.getBoolean("featured"));
    p.put("enabled", rs.getBoolean("enabled"));
    return p;
  }

  public Map<String, Object> get(long id, boolean admin) {
    var rows =
        db.query(
            "SELECT p.*,i.stock FROM products p JOIN inventory i ON i.product_id=p.id WHERE p.id=?"
                + (admin ? "" : " AND p.enabled=TRUE"),
            this::map,
            id);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> list(
      int page, int pageSize, String q, String category, String sort, boolean admin) {
    page = Math.max(1, page);
    pageSize = Math.max(1, Math.min(100, pageSize));
    StringBuilder where = new StringBuilder(admin ? " WHERE 1=1" : " WHERE p.enabled=TRUE");
    List<Object> a = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      where.append(" AND (p.name LIKE ? OR p.description LIKE ?)");
      a.add("%" + q + "%");
      a.add("%" + q + "%");
    }
    if (category != null && !category.isBlank()) {
      where.append(" AND p.category=?");
      a.add(category);
    }
    long total =
        db.queryForObject("SELECT COUNT(*) FROM products p" + where, Long.class, a.toArray());
    String order =
        switch (sort == null ? "" : sort) {
          case "price_asc" -> "p.price_cents ASC,p.id";
          case "price_desc" -> "p.price_cents DESC,p.id";
          case "newest" -> "p.id DESC";
          default -> "p.featured DESC,p.id";
        };
    a.add(pageSize);
    a.add((page - 1) * pageSize);
    return Map.of(
        "items",
        db.query(
            "SELECT p.*,i.stock FROM products p JOIN inventory i ON i.product_id=p.id"
                + where
                + " ORDER BY "
                + order
                + " LIMIT ? OFFSET ?",
            this::map,
            a.toArray()),
        "total",
        total,
        "page",
        page,
        "pageSize",
        pageSize);
  }

  public List<Map<String, Object>> categories() {
    return db.query(
        "SELECT category,category_name,COUNT(*) AS n FROM products WHERE enabled=TRUE GROUP BY"
            + " category,category_name ORDER BY category",
        (rs, n) -> Map.of("id", rs.getString(1), "name", rs.getString(2), "count", rs.getLong(3)));
  }
}
