package com.bitselect.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RetrievalScopeTest {
  @Test
  void scopedSearchExcludesOtherSkusAndRetainsEachSelectedManual() throws Exception {
    var db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:retrieval"
                    + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""));
    db.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,status VARCHAR(20))");
    db.execute(
        "CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,product_id"
            + " BIGINT,title VARCHAR(100),heading VARCHAR(100),content VARCHAR(1000),active"
            + " BOOLEAN)");
    db.update(
        "INSERT INTO knowledge_document"
            + " VALUES(1,'READY'),(2,'READY'),(3,'READY'),(4,'PROCESSING')");
    for (int i = 1; i <= 12; i++)
      db.update(
          "INSERT INTO knowledge_chunk VALUES(?,1,1,'耳机A','参数',?,TRUE)",
          i,
          "耳机A的独有参数 chunk" + i + "#");
    db.update(
        "INSERT INTO knowledge_chunk"
            + " VALUES(20,2,2,'耳机B','参数','耳机B独有参数',TRUE),(30,3,3,'其他耳机','参数','不应泄漏的其他型号主动降噪参数',TRUE),(40,4,4,'未发布耳机','参数','未发布参数',TRUE)");
    var model = mock(SiliconFlowClient.class);
    var vectors = mock(MilvusStore.class);
    var ranker = mock(LambdaMartRanker.class);
    when(model.embed(anyList())).thenReturn(List.of(List.of(1.0f)));
    when(model.rerank(anyString(), anyList()))
        .thenAnswer(
            invocation -> {
              List<?> docs = invocation.getArgument(1);
              Map<Integer, Double> scores = new HashMap<>();
              for (int i = 0; i < docs.size(); i++)
                scores.put(i, docs.get(i).toString().contains("chunk2#") ? .9 : .5);
              return scores;
            });
    when(vectors.search(anyString(), anyList(), eq("productId in [1,2,4]"), eq(10)))
        .thenReturn(List.of(Map.of("id", 30L, "distance", .99), Map.of("id", 3L, "distance", .8)));
    when(ranker.score(any())).thenReturn(1.0);
    when(ranker.mode()).thenReturn("LAMBDAMART");
    var retrieval = new RetrievalService(db, model, vectors, ranker);
    var hits = retrieval.search("耳机", List.of(1L, 2L, 4L));
    assertEquals(
        Set.of(1L, 2L),
        hits.stream()
            .map(m -> ((Number) m.get("productId")).longValue())
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        2L,
        ((Number) hits.getFirst().get("id")).longValue(),
        "LambdaMART ties use rerank score first");
    assertEquals(3L, ((Number) hits.get(1).get("id")).longValue(), "rerank ties then use cosine");
    assertEquals(3, hits.size());
    assertTrue(hits.stream().noneMatch(m -> m.get("excerpt").toString().contains("不应泄漏")));
    verify(vectors).search(anyString(), anyList(), eq("productId in [1,2,4]"), eq(10));
    clearInvocations(model, vectors);
    assertTrue(retrieval.search("耳机", List.of()).isEmpty());
    verifyNoInteractions(model, vectors);
  }
}
