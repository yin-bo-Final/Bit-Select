package com.bitselect.catalog;

import com.bitselect.contracts.CatalogRpc;
import java.util.*;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.jdbc.core.JdbcTemplate;

@DubboService
public class CatalogProvider implements CatalogRpc {
  private final JdbcTemplate db;

  public CatalogProvider(JdbcTemplate db) {
    this.db = db;
  }

  public List<ProductSnapshot> findProducts(List<Long> ids) {
    if (ids == null || ids.isEmpty() || ids.size() > 100) return List.of();
    return db.query(
        "SELECT id,name,image_url,price_cents,enabled FROM products WHERE id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")",
        (r, n) ->
            new ProductSnapshot(
                r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.getBoolean(5)),
        ids.toArray());
  }
  @Override
  public List<ProductSnapshot> searchProducts(String query,long maxPriceCents,int limit) {
    String term=ProductSearchTerm.normalize(query);
    List<Object> args=new ArrayList<>();
    StringBuilder sql=new StringBuilder("SELECT p.id,p.name,p.image_url,p.price_cents,p.enabled FROM products p JOIN inventory i ON i.product_id=p.id WHERE p.enabled=TRUE AND i.stock>0");
    if(!term.isBlank()){sql.append(" AND p.name LIKE ? ESCAPE '!'");args.add("%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
    if(maxPriceCents>0){sql.append(" AND p.price_cents<=?");args.add(maxPriceCents);}
    sql.append(" ORDER BY p.price_cents ASC,p.id ASC LIMIT ?");args.add(Math.max(1,Math.min(limit,20)));
    return db.query(sql.toString(),(r,n)->new ProductSnapshot(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getBoolean(5)),args.toArray());
  }

}
