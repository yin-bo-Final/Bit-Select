package com.bitselect.ai;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bitselect.contracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.BiConsumer;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

@Service
public class AiWorkflow {
  private final SiliconFlowClient model;
  private final ConversationStore store;
  private final MemoryService memory;
  private final RetrievalService retrieval;
  private final ObjectMapper json;

  @DubboReference(check = false)
  private CatalogRpc catalog;

  @DubboReference(check = false)
  private CommerceReadRpc commerce;

  public AiWorkflow(
      SiliconFlowClient model,
      ConversationStore store,
      MemoryService memory,
      RetrievalService retrieval,
      ObjectMapper json) {
    this.model = model;
    this.store = store;
    this.memory = memory;
    this.retrieval = retrieval;
    this.json = json;
  }

  public boolean admin(long user) throws Exception {
    return "ADMIN"
        .equals(json.readTree(commerce.userContext(user, "profile")).path("role").asText());
  }

  public String run(
      long user, String conversation, String question, BiConsumer<String, Object> send)
      throws Exception {
    var control = model.currentRequestControl();
    Map<String, KeyStrategy> strategies = new HashMap<>();
    for (String key : List.of("query", "intent", "evidence", "sources", "memories"))
      strategies.put(key, new ReplaceStrategy());
    StateGraph flow =
        new StateGraph(() -> strategies)
            .addNode(
                "rewrite",
                requestNode(
                    control,
                    state -> {
                      phase(send, "rewrite", "running", "理解问题");
                      String recent =
                          json.writeValueAsString(store.recentMessages(user, conversation));
                      String q =
                          model.complete(
                              List.of(
                                  Map.of(
                                      "role",
                                      "system",
                                      "content",
                                      Prompts.read("retrieval/rewrite-query")),
                                  Map.of(
                                      "role",
                                      "user",
                                      "content",
                                      "近期对话：" + recent + "\n当前问题：" + question)),
                              500);
                      phase(send, "rewrite", "completed", "理解问题");
                      return Map.of("query", q);
                    }))
            .addNode(
                "intent",
                requestNode(
                    control,
                    state -> {
                      phase(send, "intent", "running", "识别需求");
                      String q = state.value("query").orElse(question).toString();
                      var classified =
                          model.json(
                              model.complete(
                                  List.of(
                                      Map.of(
                                          "role",
                                          "system",
                                          "content",
                                          Prompts.read("retrieval/classify-intent")),
                                      Map.of("role", "user", "content", q)),
                                  200));
                      String intent = classified.path("intent").asText("general");
                      if (!Set.of("recommend", "compare", "manual", "order", "wallet", "general")
                          .contains(intent)) intent = "general";
                      phase(send, "intent", "completed", "识别需求");
                      return Map.of("intent", intent);
                    }))
            .addNode(
                "retrieve",
                requestNode(
                    control,
                    state -> {
                      String q = state.value("query").orElse(question).toString(),
                          intent = state.value("intent").orElse("general").toString();
                      phase(
                          send,
                          "retrieve",
                          "running",
                          Set.of("order", "wallet").contains(intent) ? "查询实时业务数据" : "查找说明书与偏好");
                      List<Map<String, Object>> memories = memory.recall(user, q);
                      List<Map<String, Object>> sources;
                      String live;
                      Long budget = store.budget(user, conversation, question);
                      if (Set.of("order", "wallet").contains(intent)) {
                        sources = List.of();
                        live =
                            json.writeValueAsString(
                                BudgetFacts.moneyInYuan(
                                    json.readValue(
                                        commerce.userContext(user, intent), Object.class)));
                      } else if (intent.equals("general")) {
                        sources = List.of();
                        live = "本轮是问候、能力说明或偏好确认，没有具体商品检索任务。请直接回应用户，不引入随机商品类别。";
                      } else if (Set.of("recommend", "compare").contains(intent)) {
                        String keyword = BudgetFacts.keyword(question);
                        if (keyword.isEmpty()) keyword = BudgetFacts.keyword(q);
                        var requestedIds = BudgetFacts.productIds(question);
                        if (requestedIds.isEmpty() && intent.equals("compare")) {
                          var recent = store.recentMessages(user, conversation);
                          for (int i = recent.size() - 1; i >= 0 && requestedIds.isEmpty(); i--)
                            requestedIds = BudgetFacts.productIds(recent.get(i).get("content"));
                        }
                        // Explicit comparisons keep the chosen products, including any over-budget
                        // option.
                        var products =
                            !requestedIds.isEmpty()
                                ? catalog.findProducts(requestedIds)
                                : keyword.isEmpty()
                                    ? List.<CatalogRpc.ProductSnapshot>of()
                                    : catalog.searchProducts(
                                        keyword, budget == null ? 0 : budget, 3);
                        boolean explicitComparison =
                            intent.equals("compare") && !requestedIds.isEmpty();
                        var candidates =
                            products.stream()
                                .filter(
                                    p ->
                                        p.enabled()
                                            && (explicitComparison
                                                || budget == null
                                                || p.priceCents() <= budget))
                                .limit(3)
                                .toList();
                        var ids = candidates.stream().map(CatalogRpc.ProductSnapshot::id).toList();
                        sources =
                            ids.isEmpty()
                                ? List.of()
                                : retrieval.search(q, ids).stream()
                                    .filter(
                                        s ->
                                            s.get("productId") instanceof Number n
                                                && ids.contains(n.longValue()))
                                    .toList();
                        var supported =
                            sources.stream()
                                .map(s -> ((Number) s.get("productId")).longValue())
                                .collect(java.util.stream.Collectors.toSet());
                        live =
                            json.writeValueAsString(
                                candidates.stream()
                                    .filter(p -> supported.contains(p.id()))
                                    .map(p -> BudgetFacts.product(p, budget))
                                    .toList());
                      } else {
                        sources = retrieval.search(q);
                        var ids =
                            sources.stream()
                                .map(s -> ((Number) s.get("productId")).longValue())
                                .distinct()
                                .toList();
                        live =
                            ids.isEmpty()
                                ? "[]"
                                : json.writeValueAsString(
                                    catalog.findProducts(ids).stream()
                                        .map(p -> BudgetFacts.product(p, budget))
                                        .toList());
                      }
                      String evidence =
                          "当前有效记忆（如与当前问题冲突，当前明确表达优先）："
                              + json.writeValueAsString(memories)
                              + "\n"
                              + "实时业务结果（所有以Yuan结尾的金额字段单位均为人民币元；withinBudget由服务器计算，true表示未超预算，必须遵守）："
                              + live
                              + "\n说明书证据："
                              + json.writeValueAsString(sources)
                              + "\n"
                              + "商品与说明书必须按productId一一对应。最多推荐3款，只能推荐实时业务结果中的商品，只能使用该商品自己的说明书参数；其他SKU参数不得借用。";
                      if (sources.isEmpty()
                          && !Set.of("order", "wallet", "general").contains(intent))
                        evidence += "\n未检索到已发布说明书，不得虚构具体商品参数或推荐链接；可询问需求。";
                      phase(send, "retrieve", "completed", "资料已就绪");
                      return Map.of("evidence", evidence, "sources", sources, "memories", memories);
                    }))
            .addEdge(StateGraph.START, "rewrite")
            .addEdge("rewrite", "intent")
            .addEdge("intent", "retrieve")
            .addEdge("retrieve", StateGraph.END);
    var result = flow.compile().invoke(Map.of()).orElseThrow();
    Object sources = result.value("sources").orElse(List.of());
    send.accept("sources", Map.of("items", sources));
    phase(send, "compose", "running", "整理回答");
    var messages =
        store.context(user, conversation, question, result.value("evidence").orElse("").toString());
    String answer = model.stream(messages, text -> send.accept("delta", Map.of("content", text)));
    model.checkRequest();
    phase(send, "compose", "completed", "回答已生成");
    phase(send, "save", "running", "保存会话");
    model.checkRequest();
    store.save(user, conversation, question, answer, sources);
    phase(send, "save", "completed", "会话已保存");
    return answer;
  }

  private AsyncNodeAction requestNode(SiliconFlowClient.RequestControl control, NodeAction action) {
    // The graph may execute nodes on another thread. Never rely on a ThreadLocal being inherited.
    return node_async(
        state -> {
          var previous = model.currentRequestControl();
          boolean rebound = control != null && previous != control;
          try {
            if (rebound) model.bindRequest(control);
            model.checkRequest();
            var result = action.apply(state);
            model.checkRequest();
            return result;
          } finally {
            if (rebound) {
              model.clearRequest();
              Thread.interrupted();
              if (previous != null) model.bindRequest(previous);
            }
          }
        });
  }

  private static void phase(
      BiConsumer<String, Object> send, String code, String status, String label) {
    send.accept("phase", Map.of("code", code, "status", status, "label", label));
  }
}
