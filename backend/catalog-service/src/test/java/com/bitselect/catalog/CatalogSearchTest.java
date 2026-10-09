package com.bitselect.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CatalogSearchTest {
  @Test
  void retainsSpecificProductTypesAndMapsCatalogAliases() {
    assertEquals("开放式耳机", ProductSearchTerm.normalize("帮我选一款开放式耳机"));
    assertEquals("鼠标垫", ProductSearchTerm.normalize("有什么鼠标垫"));
    assertEquals("智能插座", ProductSearchTerm.normalize("推荐一个智能插座"));
    assertEquals("充电线", ProductSearchTerm.normalize("买一根数据线"));
    assertEquals("切菜板", ProductSearchTerm.normalize("好清理的砧板"));
    assertEquals("读卡器", ProductSearchTerm.normalize("读卡器"));
  }

  @Test
  void budgetAndAvailabilityAreSqlConstraints() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__commerce_schema.sql"))
        .execute(source);
    var db = new JdbcTemplate(source);
    for (int i = 1; i <= 4; i++) {
      db.update(
          "INSERT INTO"
              + " products(id,name,category,category_name,description,price_cents,original_price_cents,image_url,specifications,tags,enabled)VALUES(?,?,'audio','音频','说明',?,?,"
              + " '/test.svg','{}','[]',?)",
          i,
          "无线耳机 " + i,
          i * 9900,
          i * 9900,
          i != 2);
      db.update(
          "INSERT INTO inventory(product_id,stock,reserved)VALUES(?,?,0)", i, i == 3 ? 0 : 10);
    }
    var found = new CatalogProvider(db).searchProducts("预算300元的通勤耳机", 30000, 10);
    assertEquals(1, found.size());
    assertEquals(9900, found.getFirst().priceCents());
    assertEquals("耳机", ProductSearchTerm.normalize("通勤蓝牙耳机"));
    assertTrue(new CatalogProvider(db).searchProducts("耳机", 5000, 10).isEmpty());
    var beyondCatalog =
        new ProductRepository(db).list(Integer.MAX_VALUE, 100, null, null, null, false);
    assertEquals(3L, beyondCatalog.get("total"));
    assertTrue(((java.util.List<?>) beyondCatalog.get("items")).isEmpty());
  }
}
