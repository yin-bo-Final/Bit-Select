package com.bitselect.commerce;

import com.bitselect.contracts.*;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

@Component
public class DubboProductLookup implements ProductLookup {
  @DubboReference(check = false, timeout = 3000, retries = 0)
  private CatalogRpc catalog;

  public List<CatalogRpc.ProductSnapshot> get(List<Long> ids) {
    try {
      return catalog.findProducts(ids);
    } catch (Exception e) {
      throw new ApiException(503, "CATALOG_UNAVAILABLE", "商品服务暂时不可用，请稍后重试");
    }
  }
}
