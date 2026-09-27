package com.bitselect.catalog;

import java.text.Normalizer;
import java.util.*;

/** Small catalog noun dictionary: usage scenarios never become product-name constraints. */
public final class ProductSearchTerm {
  private ProductSearchTerm() {}

  public static String normalize(String query) {
    if (query == null) return "";
    String q = Normalizer.normalize(query, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    if (q.length() > 100) q = q.substring(0, 100);
    for (String noun :
        List.of(
                "开放式耳机", "无线耳机", "护腕鼠标垫", "鼠标垫", "显示器支架", "电脑支架", "手机支架", "厨房收纳架", "桌面收纳架", "无线充电座",
                "移动电源", "充电宝", "充电器", "充电线", "扩展坞", "数据线", "读卡器", "理线器", "收纳包", "收纳盒", "收纳箱", "收纳架",
                "转换插座", "转接插座", "智能插座", "温湿度计", "毛球修剪器", "除螨清洁机", "洗漱包", "记忆棉枕", "吹风机", "耳机", "音箱",
                "键盘", "鼠标", "台灯", "支架", "风扇", "牙刷", "咖啡壶", "饭盒", "保温杯", "加湿器", "吸尘器", "雨伞", "双肩包",
                "浴巾", "切菜板", "砧板", "炒锅", "餐具", "枕头")
            .stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList()) {
      if (q.contains(noun))
        return switch (noun) {
          case "充电宝" -> "移动电源";
          case "数据线" -> "充电线";
          case "收纳盒" -> "收纳箱";
          case "转接插座" -> "转换插座";
          case "砧板" -> "切菜板";
          case "枕头" -> "记忆棉枕";
          default -> noun;
        };
    }
    if (q.contains("earbud") || q.contains("headphone")) return "耳机";
    if (q.contains("keyboard")) return "键盘";
    return q;
  }
}
