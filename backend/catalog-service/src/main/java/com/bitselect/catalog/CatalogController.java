package com.bitselect.catalog;

import com.bitselect.contracts.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
  private final ProductRepository products;
  private final JdbcTemplate db;
  private final SessionService sessions;

  public CatalogController(ProductRepository p, JdbcTemplate d, SessionService s) {
    products = p;
    db = d;
    sessions = s;
  }

  private void admin(HttpServletRequest r) {
    Long id = sessions.requireUserId(r);
    if (!"ADMIN".equals(db.queryForObject("SELECT role FROM users WHERE id=?", String.class, id)))
      throw new ApiException(403, "FORBIDDEN", "需要管理员权限");
  }

  @GetMapping("/categories")
  Object categories() {
    return products.categories();
  }

  @GetMapping("/products")
  Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "featured") String sort) {
    return products.list(page, pageSize, q, category, sort, false);
  }

  @GetMapping("/products/{id}")
  Object one(@PathVariable long id) {
    return products.get(id, false);
  }

  @GetMapping("/admin/products")
  Object adminList(
      HttpServletRequest r,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "featured") String sort) {
    admin(r);
    return products.list(page, pageSize, q, category, sort, true);
  }

  public record ProductInput(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String category,
      @NotBlank @Size(max = 80) String categoryName,
      @NotBlank String description,
      @Min(1) @Max(1000000000) long priceCents,
      @Min(0) long originalPriceCents,
      @NotBlank @Size(max = 1024) String imageUrl,
      Map<String, Object> specifications,
      List<String> tags,
      String manualUrl,
      boolean featured,
      boolean enabled) {}

  @PostMapping("/admin/products")
  @Transactional
  Object create(HttpServletRequest r, @Valid @RequestBody ProductInput p) {
    admin(r);
    KeyHolder key = new GeneratedKeyHolder();
    db.update(
        c -> {
          var ps =
              c.prepareStatement(
                  "INSERT INTO"
                      + " products(name,category,category_name,description,price_cents,original_price_cents,image_url,specifications,tags,manual_url,featured,enabled)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          Object[] args = args(p);
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        key);
    long id = Objects.requireNonNull(key.getKey()).longValue();
    db.update("INSERT INTO inventory(product_id,stock,reserved)VALUES(?,0,0)", id);
    return products.get(id, true);
  }

  @PutMapping("/admin/products/{id}")
  @Transactional
  Object update(HttpServletRequest r, @PathVariable long id, @Valid @RequestBody ProductInput p) {
    admin(r);
    List<Object> a = new ArrayList<>(Arrays.asList(args(p)));
    a.add(id);
    int n =
        db.update(
            "UPDATE products SET"
                + " name=?,category=?,category_name=?,description=?,price_cents=?,original_price_cents=?,image_url=?,specifications=?,tags=?,manual_url=?,featured=?,enabled=?"
                + " WHERE id=?",
            a.toArray());
    if (n == 0) throw ApiException.missing();
    return products.get(id, true);
  }

  private Object[] args(ProductInput p) {
    return new Object[] {
      p.name(),
      p.category(),
      p.categoryName(),
      p.description(),
      p.priceCents(),
      p.originalPriceCents(),
      p.imageUrl(),
      Json.write(p.specifications() == null ? Map.of() : p.specifications()),
      Json.write(p.tags() == null ? List.of() : p.tags()),
      p.manualUrl() == null ? "" : p.manualUrl(),
      p.featured(),
      p.enabled()
    };
  }
}
