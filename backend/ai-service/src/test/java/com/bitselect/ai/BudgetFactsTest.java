package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.bitselect.contracts.CatalogRpc;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetFactsTest {
  @Test
  void productKeywordsCoverTheDemoCatalogAndAvoidSubstringConfusion() {
    for (String name :
        List.of(
            "读卡器", "衣物毛球修剪器", "除螨清洁机", "温湿度计", "智能插座", "厨房收纳架", "桌面收纳架", "桌面理线器", "数码收纳包", "旅行转换插座",
            "无线充电座")) assertFalse(BudgetFacts.keyword(name).isEmpty(), name);
    assertEquals("鼠标垫", BudgetFacts.keyword("护腕鼠标垫"));
    assertEquals("开放式耳机", BudgetFacts.keyword("开放式耳机"));
    assertEquals("移动电源", BudgetFacts.keyword("通勤充电宝"));
    assertEquals(
        List.of(17L, 23L), BudgetFacts.productIds("比较 /products/17 和 BS-0023，再看 /products/17"));
  }

  @Test
  void recognizesCommonChineseBudgetsWithoutProductCounts() {
    for (String question :
        List.of("预算上限300元", "预算为300元", "预算控制在300元以内", "300块钱左右", "预算三百元", "三百元以内"))
      assertEquals(30000L, BudgetFacts.maximumCents(question), question);
    assertEquals(50000L, BudgetFacts.maximumCents("预算300到500元"));
    assertEquals(120000L, BudgetFacts.maximumCents("预算1.2千元"));
    assertEquals(200000L, BudgetFacts.maximumCents("预算两千元"));
    assertNull(BudgetFacts.maximumCents("最多3款65W充电器"));
  }

  @Test
  void followsLatestExplicitUserBudgetAndHonorsRemoval() {
    var history = List.of("还有别的吗", "预算为200元", "预算上限300元");
    assertEquals(20000L, BudgetFacts.resolveMaximumCents("我想要黑色的", history));
    assertEquals(15000L, BudgetFacts.resolveMaximumCents("预算改成150元", history));
    assertEquals(20000L, BudgetFacts.maximumCents("原本预算300元，现在预算200元"));
    assertNull(BudgetFacts.resolveMaximumCents("这次不限预算", history));
    assertNull(BudgetFacts.resolveMaximumCents("还有别的吗", List.of("预算不限", "预算300元")));
    assertEquals(0L, BudgetFacts.resolveMaximumCents("预算0元", history));
  }

  @Test
  void recursivelyConvertsAllBusinessMoneyAndPreservesIdentifiers() {
    var input =
        Map.of(
            "id",
            12,
            "balanceCents",
            50000,
            "orders",
            List.of(
                Map.of(
                    "totalCents",
                    9900L,
                    "items",
                    List.of(Map.of("unitPriceCents", 9900, "quantity", 1)))),
            "ledger",
            List.of(Map.of("amountCents", -3900)));
    var output = (Map<?, ?>) BudgetFacts.moneyInYuan(input);
    assertEquals("500.00", output.get("balanceYuan"));
    assertFalse(output.containsKey("balanceCents"));
    assertEquals(12, output.get("id"));
    var order = (Map<?, ?>) ((List<?>) output.get("orders")).getFirst();
    assertEquals("99.00", order.get("totalYuan"));
    var item = (Map<?, ?>) ((List<?>) order.get("items")).getFirst();
    assertEquals("99.00", item.get("unitPriceYuan"));
    assertEquals(1, item.get("quantity"));
    assertEquals(
        "-39.00", ((Map<?, ?>) ((List<?>) output.get("ledger")).getFirst()).get("amountYuan"));
  }

  @Test
  void moneyDoesNotMixCentsWithYuan() {
    Long budget = BudgetFacts.maximumCents("预算300元，帮我挑一副通勤耳机");
    assertEquals(30000L, budget);
    var facts =
        BudgetFacts.product(new CatalogRpc.ProductSnapshot(1, "耳机", "", 9900, true), budget);
    assertEquals("99.00", facts.get("priceYuan"));
    assertEquals(true, facts.get("withinBudget"));
    assertEquals("201.00", facts.get("remainingBudgetYuan"));
  }

  @Test
  void extractsDecimalsAndHonorsBoundary() {
    assertEquals(9999L, BudgetFacts.maximumCents("99.99元以内"));
    assertNull(BudgetFacts.maximumCents("65W的充电器"));
  }
}
