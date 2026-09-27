package com.bitselect.ai;

import com.bitselect.contracts.CatalogRpc;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class BudgetFacts {
  private BudgetFacts() {}

  private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]{1,2})?(?:千|万)?|[零〇一二两三四五六七八九十百千万]+)";
  private static final String AMOUNT =
      "(" + NUMBER + ")(?:\\s*(?:元|块钱|块))?(?:\\s*(?:到|至|[-~～—])\\s*(" + NUMBER + "))?";
  private static final String INVALID_UNIT = "(?!(?:[0-9.]|\\s*(?:款|个|台|只|副|件|W|瓦|小时|天|年|折|毫安)))";
  private static final Pattern NAMED =
      Pattern.compile(
          "(?:预算|价格上限|价位)(?:\\s*(?:上限|最多|最高|是|为|在|大概|大约|约|控制在|不要超过|不超过|不高于|改成|调整到|提高到|降低到|增加到|到|：|:))*\\s*[¥￥]?\\s*"
              + AMOUNT
              + INVALID_UNIT,
          Pattern.CASE_INSENSITIVE);
  private static final Pattern MAXIMUM =
      Pattern.compile(
          "(?:不超过|不要超过|不能超过|不高于|最多|最高|上限)\\s*[¥￥]?\\s*" + AMOUNT + INVALID_UNIT,
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SUFFIX =
      Pattern.compile("[¥￥]?\\s*(" + NUMBER + ")\\s*(?:元|块钱|块)\\s*(?:以内|以下|左右|上下|封顶)");
  private static final Pattern UNLIMITED =
      Pattern.compile("(?:不限预算|预算(?:不设上限|不限|无限制|没有上限)|不(?:限制|考虑)预算|取消预算(?:限制)?|不设价格上限)");

  private record Mention(int position, Long cents) {}

  /** The last explicit amount wins, so corrections in the same message remain authoritative. */
  private static Mention explicit(String question) {
    if (question == null) return null;
    Mention latest = null;
    var unlimited = UNLIMITED.matcher(question);
    while (unlimited.find()) latest = new Mention(unlimited.start(), null);
    for (Pattern pattern : List.of(NAMED, MAXIMUM, SUFFIX)) {
      var matcher = pattern.matcher(question);
      while (matcher.find()) {
        String number =
            matcher.groupCount() > 1 && matcher.group(2) != null
                ? matcher.group(2)
                : matcher.group(1);
        Long cents = cents(number);
        if (cents != null && (latest == null || matcher.start() > latest.position()))
          latest = new Mention(matcher.start(), cents);
      }
    }
    return latest;
  }

  public static Long maximumCents(String question) {
    var mention = explicit(question);
    return mention == null ? null : mention.cents();
  }

  /** History is newest first and must contain only the authenticated user's own messages. */
  public static Long resolveMaximumCents(String question, List<String> newestUserMessages) {
    var current = explicit(question);
    if (current != null) return current.cents();
    for (String previous : newestUserMessages) {
      var found = explicit(previous);
      if (found != null) return found.cents();
    }
    return null;
  }

  private static Long cents(String raw) {
    try {
      BigDecimal amount;
      if (Character.isDigit(raw.charAt(0)) && raw.charAt(0) <= '9') {
        int multiplier = raw.endsWith("千") ? 1000 : raw.endsWith("万") ? 10000 : 1;
        amount =
            new BigDecimal(multiplier == 1 ? raw : raw.substring(0, raw.length() - 1))
                .multiply(BigDecimal.valueOf(multiplier));
      } else {
        long total = 0, section = 0, digit = 0;
        String digits = "零一二三四五六七八九";
        for (char ch : raw.toCharArray()) {
          if (ch == '两') ch = '二';
          if (ch == '〇') ch = '零';
          int d = digits.indexOf(ch);
          if (d >= 0) digit = d;
          else if (ch == '万') {
            total += (section + digit) * 10000;
            section = digit = 0;
          } else {
            int scale = ch == '十' ? 10 : ch == '百' ? 100 : 1000;
            section += (digit == 0 ? 1 : digit) * scale;
            digit = 0;
          }
        }
        amount = BigDecimal.valueOf(total + section + digit);
      }
      return amount.movePointRight(2).longValueExact();
    } catch (ArithmeticException | NumberFormatException ignored) {
      return null;
    }
  }

  public static String keyword(String question) {
    String normalized =
        question
            .replace("充电宝", "移动电源")
            .replace("数据线", "充电线")
            .replace("砧板", "切菜板")
            .replace("除螨仪", "除螨清洁机")
            .replace("剃毛器", "毛球修剪器");
    for (String key :
        List.of(
            "开放式耳机", "无线耳机", "耳机", "无线充电座", "充电器", "充电线", "移动电源", "扩展坞", "读卡器", "转换插座", "智能插座",
            "理线器", "数码收纳包", "洗漱包", "鼠标垫", "键盘", "鼠标", "显示器支架", "电脑支架", "手机支架", "支架", "台灯", "桌面收纳架",
            "厨房收纳架", "收纳架", "音箱", "温湿度计", "风扇", "加湿器", "吸尘器", "牙刷", "吹风机", "毛球修剪器", "除螨清洁机", "除螨",
            "保温杯", "炒锅", "饭盒", "餐具", "咖啡壶", "切菜板", "双肩包", "收纳箱", "枕", "雨伞", "浴巾"))
      if (normalized.contains(key)) return key;
    return "";
  }

  public static List<Long> productIds(String text) {
    var matcher =
        Pattern.compile(
                "/products/(\\d{1,10})(?!\\d)|\\bBS[-－](\\d{1,6})\\b", Pattern.CASE_INSENSITIVE)
            .matcher(text);
    Set<Long> ids = new LinkedHashSet<>();
    while (matcher.find() && ids.size() < 3) {
      long id = Long.parseLong(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
      if (id > 0) ids.add(id);
    }
    return List.copyOf(ids);
  }

  /**
   * Convert monetary fields recursively at the tool boundary; identifiers and counts stay intact.
   */
  public static Object moneyInYuan(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> {
            String name = key.toString();
            if (name.endsWith("Cents") && item instanceof Number number) {
              result.put(
                  name.substring(0, name.length() - 5) + "Yuan",
                  new BigDecimal(number.toString()).movePointLeft(2).setScale(2).toPlainString());
            } else result.put(name, moneyInYuan(item));
          });
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(BudgetFacts::moneyInYuan).toList();
    return value;
  }

  public static Map<String, Object> product(CatalogRpc.ProductSnapshot p, Long budget) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("productId", p.id());
    fact.put("name", p.name());
    fact.put("priceYuan", BigDecimal.valueOf(p.priceCents(), 2).toPlainString());
    fact.put("currency", "CNY");
    fact.put("onSale", p.enabled());
    fact.put("url", "/products/" + p.id());
    if (budget != null) {
      fact.put("budgetYuan", BigDecimal.valueOf(budget, 2).toPlainString());
      fact.put("withinBudget", p.priceCents() <= budget);
      fact.put(
          "remainingBudgetYuan", BigDecimal.valueOf(budget - p.priceCents(), 2).toPlainString());
    }
    return fact;
  }
}
