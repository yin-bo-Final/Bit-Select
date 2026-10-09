package com.bitselect.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SiliconFlowClient {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final ObjectMapper mapper;
  private final String base, key, chatModel, embeddingModel, rerankModel;
  private final int streamTimeoutSeconds;
  private final ThreadLocal<RequestControl> requestControl = new ThreadLocal<>();
  private static final ScheduledExecutorService DEADLINES =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "model-stream-deadline");
            t.setDaemon(true);
            return t;
          });

  public static final class RequestControl {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<InputStream> streams = ConcurrentHashMap.newKeySet();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    public void cancel() {
      cancelled.set(true);
      for (Thread worker : workers) worker.interrupt();
      for (InputStream input : streams)
        try {
          input.close();
        } catch (IOException ignored) {
        }
    }

    void bind() {
      workers.add(Thread.currentThread());
      check();
    }

    void attach(InputStream input) {
      streams.add(input);
      if (cancelled.get()) {
        cancel();
        throw new CancellationException("MODEL_REQUEST_CANCELLED");
      }
    }

    void detach(InputStream input) {
      streams.remove(input);
    }

    void unbind() {
      workers.remove(Thread.currentThread());
    }

    void check() {
      if (cancelled.get() || Thread.currentThread().isInterrupted())
        throw new CancellationException("MODEL_REQUEST_CANCELLED");
    }
  }

  public RequestControl newRequestControl() {
    return new RequestControl();
  }

  public void bindRequest(RequestControl control) {
    requestControl.set(control);
    try {
      control.bind();
    } catch (RuntimeException e) {
      clearRequest();
      throw e;
    }
  }

  public RequestControl currentRequestControl() {
    return requestControl.get();
  }

  public void checkRequest() {
    RequestControl control = requestControl.get();
    if (control != null) control.check();
  }

  public void clearRequest() {
    RequestControl control = requestControl.get();
    if (control != null) control.unbind();
    requestControl.remove();
  }

  public SiliconFlowClient(
      ObjectMapper mapper,
      @Value("${ai.base-url}") String base,
      @Value("${ai.api-key}") String key,
      @Value("${ai.chat-model}") String chat,
      @Value("${ai.embedding-model}") String embedding,
      @Value("${ai.rerank-model}") String rerank,
      @Value("${ai.stream-timeout-seconds:120}") int streamTimeoutSeconds) {
    this.mapper = mapper;
    this.base = base;
    this.key = key;
    this.chatModel = chat;
    this.embeddingModel = embedding;
    this.rerankModel = rerank;
    this.streamTimeoutSeconds = Math.max(1, streamTimeoutSeconds);
  }

  public boolean configured() {
    return key != null && !key.isBlank();
  }

  private HttpRequest request(String path, Object body) throws Exception {
    if (!configured()) throw new IllegalStateException("MODEL_KEY_MISSING");
    RequestControl control = requestControl.get();
    if (control != null) control.check();
    return HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();
  }

  private JsonNode post(String path, Object body) throws Exception {
    var r = http.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    checkRequest();
    if (r.statusCode() != 200) throw new IllegalStateException("MODEL_HTTP_" + r.statusCode());
    return mapper.readTree(r.body());
  }

  public String complete(List<Map<String, String>> messages, int maxTokens) throws Exception {
    var choice =
        post(
                "/chat/completions",
                Map.of(
                    "model",
                    chatModel,
                    "messages",
                    messages,
                    "max_tokens",
                    maxTokens,
                    "temperature",
                    0.2))
            .path("choices")
            .path(0);
    String reason = choice.path("finish_reason").asText("");
    if (!reason.isEmpty() && !reason.equals("stop"))
      throw new IllegalStateException(
          reason.equals("length") ? "MODEL_RESPONSE_LIMIT" : "MODEL_RESPONSE_INCOMPLETE");
    var content = choice.path("message").path("content");
    if (!content.isTextual() || content.asText().isBlank())
      throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
    return content.asText();
  }

  public String stream(List<Map<String, String>> messages, Consumer<String> delta)
      throws Exception {
    RequestControl control = requestControl.get();
    boolean temporary = control == null;
    if (temporary) {
      control = newRequestControl();
      bindRequest(control);
    }
    final RequestControl active = control;
    ScheduledFuture<?> deadline =
        DEADLINES.schedule(active::cancel, streamTimeoutSeconds, TimeUnit.SECONDS);
    InputStream input = null;
    try {
      var r =
          http.send(
              request(
                  "/chat/completions",
                  Map.of(
                      "model",
                      chatModel,
                      "messages",
                      messages,
                      "max_tokens",
                      2048,
                      "temperature",
                      0.3,
                      "stream",
                      true)),
              HttpResponse.BodyHandlers.ofInputStream());
      input = r.body();
      active.attach(input);
      if (r.statusCode() != 200) {
        r.body().close();
        throw new IllegalStateException("MODEL_HTTP_" + r.statusCode());
      }
      StringBuilder answer = new StringBuilder();
      boolean finished = false;
      StringBuilder frame = new StringBuilder();
      try (var reader =
          new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          active.check();
          if (!line.isEmpty()) {
            if (line.startsWith("data:")) {
              if (!frame.isEmpty()) frame.append('\n');
              frame.append(line.substring(5).stripLeading());
              if (frame.length() > 1_048_576)
                throw new IllegalStateException("MODEL_STREAM_FRAME_TOO_LARGE");
            }
            continue;
          }
          if (frame.isEmpty()) continue;
          String value = frame.toString().strip();
          frame.setLength(0);
          if (value.equals("[DONE]")) {
            finished = true;
            break;
          }
          var node = mapper.readTree(value);
          if (node.has("error")) throw new IllegalStateException("MODEL_STREAM_ERROR");
          var choice = node.path("choices").path(0);
          String reason = choice.path("finish_reason").asText("");
          if (!reason.isEmpty() && !reason.equals("stop"))
            throw new IllegalStateException(
                reason.equals("length") ? "MODEL_RESPONSE_LIMIT" : "MODEL_RESPONSE_INCOMPLETE");
          String text = choice.path("delta").path("content").asText("");
          if (!text.isEmpty()) {
            answer.append(text);
            delta.accept(text);
          }
        }
      }
      active.check();
      if (!finished) throw new IllegalStateException("MODEL_STREAM_TRUNCATED");
      if (answer.isEmpty()) throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
      return answer.toString();
    } finally {
      deadline.cancel(false);
      if (input != null) {
        active.detach(input);
        try {
          input.close();
        } catch (IOException ignored) {
        }
      }
      if (temporary) clearRequest();
    }
  }

  public List<List<Float>> embed(List<String> texts) throws Exception {
    JsonNode data =
        post(
                "/embeddings",
                Map.of("model", embeddingModel, "input", texts, "encoding_format", "float"))
            .path("data");
    List<List<Float>> out = new ArrayList<>(Collections.nCopies(texts.size(), null));
    for (JsonNode row : data) {
      List<Float> v = new ArrayList<>();
      row.path("embedding").forEach(n -> v.add((float) n.asDouble()));
      out.set(row.path("index").asInt(), v);
    }
    if (out.stream().anyMatch(Objects::isNull))
      throw new IllegalStateException("EMBEDDING_INCOMPLETE");
    return out;
  }

  public Map<Integer, Double> rerank(String query, List<String> texts) throws Exception {
    JsonNode rows =
        post(
                "/rerank",
                Map.of(
                    "model",
                    rerankModel,
                    "query",
                    query,
                    "documents",
                    texts,
                    "top_n",
                    texts.size(),
                    "return_documents",
                    false))
            .path("results");
    Map<Integer, Double> result = new HashMap<>();
    for (JsonNode row : rows)
      result.put(row.path("index").asInt(), row.path("relevance_score").asDouble());
    return result;
  }

  public JsonNode json(String response) throws Exception {
    String clean =
        response.strip().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
    return mapper.readTree(clean);
  }
}
