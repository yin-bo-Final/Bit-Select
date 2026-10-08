package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bitselect.contracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AiWorkflowGroundingTest {
  @Test
  void partialStreamIsNeverSavedAndSavePhaseOnlyCompletesAfterCommit() throws Exception {
    var model = mock(SiliconFlowClient.class);
    var store = mock(ConversationStore.class);
    var mapper = new ObjectMapper();
    var workflow =
        new AiWorkflow(
            model, store, mock(MemoryService.class), mock(RetrievalService.class), mapper);
    when(model.complete(anyList(), eq(500))).thenReturn("hello");
    when(model.complete(anyList(), eq(200))).thenReturn("{\"intent\":\"general\"}");
    when(model.json(anyString())).thenAnswer(call -> mapper.readTree((String) call.getArgument(0)));
    when(store.context(anyLong(), anyString(), anyString(), anyString())).thenReturn(List.of());
    List<String> phases = new ArrayList<>();
    java.util.function.BiConsumer<String, Object> events =
        (event, data) -> {
          if (event.equals("phase")) {
            var phase = (Map<?, ?>) data;
            assertNotNull(phase.get("label"));
            phases.add(phase.get("code") + ":" + phase.get("status"));
          }
        };
    when(model.stream(anyList(), any()))
        .thenThrow(new IllegalStateException("MODEL_STREAM_TRUNCATED"));
    assertThrows(IllegalStateException.class, () -> workflow.run(1, "owned", "hello", events));
    verify(store, never()).save(anyLong(), anyString(), anyString(), anyString(), any());
    assertFalse(phases.contains("compose:completed"));
    assertFalse(phases.contains("save:running"));
    phases.clear();
    doReturn("complete answer").when(model).stream(anyList(), any());
    doAnswer(
            call -> {
              assertTrue(phases.contains("save:running"));
              assertFalse(phases.contains("save:completed"));
              return null;
            })
        .when(store)
        .save(anyLong(), anyString(), anyString(), anyString(), any());
    workflow.run(1, "owned", "hello", events);
    assertEquals(
        List.of(
            "rewrite:running",
            "rewrite:completed",
            "intent:running",
            "intent:completed",
            "retrieve:running",
            "retrieve:completed",
            "compose:running",
            "compose:completed",
            "save:running",
            "save:completed"),
        phases);
  }

  @Test
  void introductionDoesNotRetrieveRandomProductManuals() throws Exception {
    var model = mock(SiliconFlowClient.class);
    var store = mock(ConversationStore.class);
    var memory = mock(MemoryService.class);
    var retrieval = mock(RetrievalService.class);
    var mapper = new ObjectMapper();
    var workflow = new AiWorkflow(model, store, memory, retrieval, mapper);
    when(model.complete(anyList(), eq(500))).thenReturn("请简单介绍自己");
    when(model.complete(anyList(), eq(200))).thenReturn("{\"intent\":\"general\"}");
    when(model.json(anyString())).thenAnswer(call -> mapper.readTree((String) call.getArgument(0)));
    when(store.context(eq(1L), eq("hello"), anyString(), anyString())).thenReturn(List.of());
    when(model.stream(anyList(), any())).thenReturn("我是比特严选导购，可以协助选购并查询说明书。");
    workflow.run(1, "hello", "你好，请介绍自己", (event, value) -> {});
    verifyNoInteractions(retrieval);
    verify(store).save(eq(1L), eq("hello"), anyString(), anyString(), eq(List.of()));
  }

  @Test
  void recommendationsUseOnlyBudgetFilteredProductsWithTheirOwnManuals() throws Exception {
    var model = mock(SiliconFlowClient.class);
    var store = mock(ConversationStore.class);
    var memory = mock(MemoryService.class);
    var retrieval = mock(RetrievalService.class);
    var catalog = mock(CatalogRpc.class);
    var mapper = new ObjectMapper();
    var workflow = new AiWorkflow(model, store, memory, retrieval, mapper);
    ReflectionTestUtils.setField(workflow, "catalog", catalog);
    when(store.recentMessages(1, "own"))
        .thenReturn(List.of(Map.of("role", "user", "content", "预算300元耳机")));
    when(model.complete(anyList(), eq(500))).thenReturn("推荐预算300元以内通勤耳机");
    when(model.complete(anyList(), eq(200))).thenReturn("{\"intent\":\"recommend\"}");
    when(model.json(anyString())).thenAnswer(call -> mapper.readTree((String) call.getArgument(0)));
    when(store.budget(1, "own", "还有其他的吗")).thenReturn(30000L);
    when(catalog.searchProducts("耳机", 30000, 3))
        .thenReturn(
            List.of(
                new CatalogRpc.ProductSnapshot(1, "入门耳机", "", 9900, true),
                new CatalogRpc.ProductSnapshot(2, "缺说明书耳机", "", 19900, true),
                new CatalogRpc.ProductSnapshot(3, "超预算耳机", "", 39900, true)));
    Map<String, Object> valid = Map.of("productId", 1L, "excerpt", "入门耳机自己的说明书");
    when(retrieval.search(anyString(), eq(List.of(1L, 2L))))
        .thenReturn(List.of(valid, Map.of("productId", 99L, "excerpt", "其他商品参数")));
    when(store.context(eq(1L), eq("own"), anyString(), anyString())).thenReturn(List.of());
    when(model.stream(anyList(), any())).thenReturn("推荐入门耳机，99元。");
    workflow.run(1, "own", "还有其他的吗", (event, value) -> {});
    var evidence = ArgumentCaptor.forClass(String.class);
    verify(store).context(eq(1L), eq("own"), eq("还有其他的吗"), evidence.capture());
    String data = evidence.getValue();
    assertTrue(data.contains("\"priceYuan\":\"99.00\""));
    assertTrue(data.contains("\"budgetYuan\":\"300.00\""));
    assertTrue(data.contains("\"withinBudget\":true"));
    assertFalse(data.contains("缺说明书耳机"));
    assertFalse(data.contains("超预算耳机"));
    assertFalse(data.contains("其他商品参数"));
    verify(retrieval, never()).search(anyString());
    verify(store).save(1, "own", "还有其他的吗", "推荐入门耳机，99元。", List.of(valid));
    verify(store, never()).details(anyLong(), anyString());
  }

  @Test
  void walletToolsExposeYuanRecursivelyInsteadOfCents() throws Exception {
    var model = mock(SiliconFlowClient.class);
    var store = mock(ConversationStore.class);
    var memory = mock(MemoryService.class);
    var retrieval = mock(RetrievalService.class);
    var commerce = mock(CommerceReadRpc.class);
    var mapper = new ObjectMapper();
    var workflow = new AiWorkflow(model, store, memory, retrieval, mapper);
    ReflectionTestUtils.setField(workflow, "commerce", commerce);
    when(model.complete(anyList(), eq(500))).thenReturn("查询钱包");
    when(model.complete(anyList(), eq(200))).thenReturn("{\"intent\":\"wallet\"}");
    when(model.json(anyString())).thenAnswer(call -> mapper.readTree((String) call.getArgument(0)));
    when(commerce.userContext(7, "wallet"))
        .thenReturn("{\"balanceCents\":50000,\"ledger\":[{\"amountCents\":-9900}]}");
    when(store.context(eq(7L), eq("wallet"), anyString(), anyString())).thenReturn(List.of());
    when(model.stream(anyList(), any())).thenReturn("余额500元");
    workflow.run(7, "wallet", "我的余额是多少", (event, value) -> {});
    var evidence = ArgumentCaptor.forClass(String.class);
    verify(store).context(eq(7L), eq("wallet"), anyString(), evidence.capture());
    assertTrue(evidence.getValue().contains("\"balanceYuan\":\"500.00\""));
    assertTrue(evidence.getValue().contains("\"amountYuan\":\"-99.00\""));
    assertFalse(evidence.getValue().contains("Cents"));
    verifyNoInteractions(retrieval);
  }
}
