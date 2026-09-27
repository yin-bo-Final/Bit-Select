package com.bitselect.catalog;
import java.text.Normalizer;import java.util.*;
/** Small catalog noun dictionary: usage scenarios never become product-name constraints. */
public final class ProductSearchTerm {
 private ProductSearchTerm(){}
 public static String normalize(String query){if(query==null)return "";String q=Normalizer.normalize(query,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);if(q.length()>100)q=q.substring(0,100);
  for(String noun:List.of("移动电源","充电宝","充电器","扩展坞","数据线","收纳包","收纳盒","收纳架","转换插座","耳机","键盘","鼠标","台灯","支架","风扇","牙刷","咖啡壶","玻璃杯","保温杯","烧水壶","温湿度计","加湿器","吸尘器","瑜伽垫","雨伞","双肩包","行李箱","浴巾","毛巾","砧板","锅","餐具")){if(q.contains(noun))return noun.equals("充电宝")?"移动电源":noun;}
  if(q.contains("earbud")||q.contains("headphone"))return "耳机";if(q.contains("keyboard"))return "键盘";
  return q;
 }
}
