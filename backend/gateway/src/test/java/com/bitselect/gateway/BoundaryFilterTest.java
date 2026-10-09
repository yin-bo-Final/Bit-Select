package com.bitselect.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.PooledByteBufAllocator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class BoundaryFilterTest {
  private static final int MIB = 1024 * 1024;
  private final BoundaryFilter filter = new BoundaryFilter("", "http://localhost:3000");
  private final DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

  @Test
  void rejectsChunkedBodyWhenActualBytesExceedLimit() {
    AtomicInteger forwarded = new AtomicInteger();
    var request =
        MockServerHttpRequest.post("/api/ai/admin/knowledge/upload")
            .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 50000))
            .header("Transfer-Encoding", "chunked")
            .body(Flux.range(0, 21).map(ignored -> buffers.wrap(new byte[MIB])));
    var exchange = MockServerWebExchange.from(request);
    assertThat(request.getHeaders().getContentLength()).isEqualTo(-1);

    filter
        .filter(
            exchange,
            downstream ->
                downstream
                    .getRequest()
                    .getBody()
                    .doOnNext(
                        buffer -> {
                          forwarded.addAndGet(buffer.readableByteCount());
                          DataBufferUtils.release(buffer);
                        })
                    .then(
                        Mono.fromRunnable(
                            () -> downstream.getResponse().setStatusCode(HttpStatus.NO_CONTENT))))
        .block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("PAYLOAD_TOO_LARGE");
    assertThat(forwarded.get()).isEqualTo(20 * MIB);
  }

  @Test
  void permitsExactLimitAndForwardsEachChunkBeforeReadingTheNext() {
    AtomicInteger forwarded = new AtomicInteger();
    Flux<DataBuffer> body =
        Flux.range(0, 20)
            .map(
                index -> {
                  assertThat(forwarded.get()).isEqualTo(index);
                  return buffers.wrap(new byte[MIB]);
                });
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/example")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 50000))
                .body(body));

    filter
        .filter(
            exchange,
            downstream ->
                downstream
                    .getRequest()
                    .getBody()
                    .doOnNext(
                        buffer -> {
                          forwarded.incrementAndGet();
                          DataBufferUtils.release(buffer);
                        })
                    .then(
                        Mono.fromRunnable(
                            () -> downstream.getResponse().setStatusCode(HttpStatus.NO_CONTENT))))
        .block();

    assertThat(forwarded.get()).isEqualTo(20);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void rejectsAdvertisedOversizeBeforeInvokingDownstream() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/example")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 50000))
                .contentLength(20L * MIB + 1)
                .build());
    filter
        .filter(
            exchange,
            downstream -> {
              throw new AssertionError("oversized request must not reach downstream");
            })
        .block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void releasesRejectedPooledBufferAndHandlesProxyWrappedFailure() {
    var pooled =
        new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT).allocateBuffer(20 * MIB + 1);
    pooled.writePosition(20 * MIB + 1);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/example")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 50000))
                .body(Flux.just(pooled)));

    try {
      filter
          .filter(
              exchange,
              downstream ->
                  downstream
                      .getRequest()
                      .getBody()
                      .doOnNext(
                          buffer -> {
                            DataBufferUtils.release(buffer);
                            throw new AssertionError(
                                "oversized first chunk must not reach downstream");
                          })
                      .then()
                      .onErrorMap(
                          error -> new IllegalStateException("request publisher failed", error)))
          .block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
      assertThat(pooled.getNativeBuffer().refCnt()).isZero();
    } finally {
      if (pooled.getNativeBuffer().refCnt() > 0) DataBufferUtils.release(pooled);
    }
  }

  @Test
  void removesForgedIdentityHeadersWithoutBufferingResponse() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/chat/stream")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 50000))
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN")
                .build());
    filter
        .filter(
            exchange,
            downstream -> {
              assertThat(downstream.getRequest().getHeaders())
                  .doesNotContainKeys("X-User-Id", "X-User-Role");
              downstream
                  .getResponse()
                  .getHeaders()
                  .setContentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM);
              return downstream
                  .getResponse()
                  .writeWith(
                      Flux.just(
                          buffers.wrap(
                              "event: delta\ndata: first\n\n"
                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                          buffers.wrap(
                              "event: done\ndata: {}\n\n"
                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            })
        .block();
    assertThat(exchange.getResponse().getBodyAsString().block())
        .isEqualTo("event: delta\ndata: first\n\nevent: done\ndata: {}\n\n");
  }
}
