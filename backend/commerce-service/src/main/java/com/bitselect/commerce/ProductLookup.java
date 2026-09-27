package com.bitselect.commerce;

import com.bitselect.contracts.CatalogRpc.ProductSnapshot;
import java.util.List;

public interface ProductLookup {
  List<ProductSnapshot> get(List<Long> ids);
}
