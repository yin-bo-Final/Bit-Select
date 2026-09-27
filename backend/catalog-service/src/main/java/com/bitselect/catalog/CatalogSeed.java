package com.bitselect.catalog;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CatalogSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String path;

  public CatalogSeed(
      JdbcTemplate db,
      ObjectMapper json,
      @Value("${CATALOG_SEED_PATH:../data/products.json}") String path) {
    this.db = db;
    this.json = json;
    this.path = path;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    Path file = Path.of(path);
    if (!Files.exists(file)) file = Path.of("data/products.json");
    if (!Files.exists(file)) file = Path.of("../../data/products.json");
    if (!Files.exists(file)) throw new IllegalStateException("找不到商品种子文件，请设置 CATALOG_SEED_PATH");
    JsonNode root = json.readTree(Files.readString(file));
    if (root.isObject()) root = root.path("products");
    for (JsonNode p : root) {
      long id = p.path("id").asLong();
      if (db.queryForObject("SELECT COUNT(*) FROM products WHERE id=?", Integer.class, id) > 0)
        continue;
      db.update(
          "INSERT INTO"
              + " products(id,name,category,category_name,description,price_cents,original_price_cents,image_url,specifications,tags,manual_url,featured,enabled)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          p.path("name").asText(),
          p.path("category").asText(),
          p.path("categoryName").asText(),
          p.path("description").asText(),
          p.path("priceCents").asLong(),
          p.path("originalPriceCents").asLong(),
          p.path("imageUrl").asText(),
          p.path("specifications").toString(),
          p.path("tags").toString(),
          p.path("manualUrl").asText(),
          p.path("featured").asBoolean(),
          p.path("enabled").asBoolean(true));
      db.update(
          "INSERT INTO inventory(product_id,stock,reserved) VALUES(?,?,0)",
          id,
          p.path("stock").asInt(100));
    }
  }
}
