package com.bitselect.ai;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MilvusStore {
  private final String url;
  private final ObjectMapper mapper;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public MilvusStore(@Value("${ai.milvus-url}") String url, ObjectMapper mapper) {
    this.url = url;
    this.mapper = mapper;
  }

  private JsonNode call(String path, Map<String, Object> body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(url + "/v2/vectordb/" + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("MILVUS_HTTP_" + response.statusCode());
    var node = mapper.readTree(response.body());
    if (node.path("code").asInt() != 0)
      throw new IllegalStateException(
          "MILVUS_CODE_" + node.path("code").asInt() + ":" + node.path("message").asText());
    return node.path("data");
  }

  public void ensure(String collection, int dimension) throws Exception {
    if (!call("collections/has", Map.of("collectionName", collection)).path("has").asBoolean())
      call(
          "collections/create",
          Map.of(
              "collectionName",
              collection,
              "dimension",
              dimension,
              "metricType",
              "COSINE",
              "primaryFieldName",
              "id",
              "vectorFieldName",
              "vector",
              "idType",
              "Int64",
              "autoId",
              false));
  }

  public void upsert(String collection, List<Map<String, Object>> data) throws Exception {
    call("entities/upsert", Map.of("collectionName", collection, "data", data));
  }

  public List<Map<String, Object>> search(
      String collection, List<Float> query, String filter, int k) throws Exception {
    JsonNode data =
        call(
            "entities/search",
            Map.of(
                "collectionName",
                collection,
                "data",
                List.of(query),
                "annsField",
                "vector",
                "filter",
                filter,
                "limit",
                k,
                "outputFields",
                List.of("*"),
                "searchParams",
                Map.of("metricType", "COSINE", "params", Map.of("ef", 64)),
                "consistencyLevel",
                "Strong"));
    return mapper.convertValue(
        data, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
  }

  public void delete(String collection, String filter) throws Exception {
    call("entities/delete", Map.of("collectionName", collection, "filter", filter));
  }
}
