package com.bitselect.contracts;

import java.io.Serializable;
import java.util.List;

public interface CatalogRpc {
  List<ProductSnapshot> findProducts(List<Long> ids);

  /** Live saleable products; maxPriceCents <= 0 means no budget cap. Query is a product noun. */
  List<ProductSnapshot> searchProducts(String query, long maxPriceCents, int limit);

  record ProductSnapshot(long id, String name, String imageUrl, long priceCents, boolean enabled)
      implements Serializable {}
}
