package com.bitselect.gateway;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class BoundaryFilter implements WebFilter, Ordered {
  private static final long MAX_BODY_BYTES = 20L * 1024 * 1024;
  private final Set<String> blocked;
  private final Set<String> origins;

  public BoundaryFilter(
      @Value("${app.blocked-ips:}") String blocked,
      @Value("${app.allowed-origin:http://localhost:3000}") String origin) {
    this.blocked = new HashSet<>(Arrays.asList(blocked.split(",")));
    origins = new HashSet<>(Arrays.asList(origin.split(",")));
  }

  public int getOrder() {
    return -100;
  }

  public Mono<Void> filter(ServerWebExchange e, WebFilterChain chain) {
    String ip =
        e.getRequest().getRemoteAddress() == null
            ? ""
            : e.getRequest().getRemoteAddress().getAddress().getHostAddress();
    if (blocked.contains(ip)) return fail(e, HttpStatus.FORBIDDEN, "IP_BLOCKED");
    String origin = e.getRequest().getHeaders().getOrigin();
    if (origin != null && !origins.contains(origin) && !origin.equals("http://127.0.0.1:3000"))
      return fail(e, HttpStatus.FORBIDDEN, "ORIGIN_REJECTED");
    String fetch = e.getRequest().getHeaders().getFirst("Sec-Fetch-Site");
    if ("cross-site".equals(fetch) && e.getRequest().getMethod() != HttpMethod.GET)
      return fail(e, HttpStatus.FORBIDDEN, "CSRF_REJECTED");
    if (origin != null) {
      e.getResponse().getHeaders().setAccessControlAllowOrigin(origin);
      e.getResponse().getHeaders().setAccessControlAllowCredentials(true);
      e.getResponse()
          .getHeaders()
          .setAccessControlAllowHeaders(
              List.of("Content-Type", "Authorization", "Idempotency-Key"));
      e.getResponse()
          .getHeaders()
          .setAccessControlAllowMethods(
              List.of(
                  HttpMethod.GET,
                  HttpMethod.POST,
                  HttpMethod.PUT,
                  HttpMethod.DELETE,
                  HttpMethod.OPTIONS));
    }
    if (e.getRequest().getMethod() == HttpMethod.OPTIONS) {
      e.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
      return e.getResponse().setComplete();
    }
    long size = e.getRequest().getHeaders().getContentLength();
    if (size > MAX_BODY_BYTES) return fail(e, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
    var request =
        new ServerHttpRequestDecorator(
            e.getRequest()
                .mutate()
                .headers(
                    h -> {
                      h.remove("X-User-Id");
                      h.remove("X-User-Role");
                    })
                .build()) {
          @Override
          public Flux<DataBuffer> getBody() {
            // Count actual bytes, including chunked requests; never collect the body in memory.
            return Flux.defer(
                () -> {
                  long[] received = {0};
                  return super.getBody()
                      .<DataBuffer>handle(
                          (buffer, sink) -> {
                            received[0] += buffer.readableByteCount();
                            if (received[0] > MAX_BODY_BYTES) {
                              DataBufferUtils.release(buffer);
                              sink.error(new BodyLimitExceededException());
                            } else sink.next(buffer);
                          })
                      .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                });
          }
        };
    return chain
        .filter(e.mutate().request(request).build())
        .onErrorResume(
            BoundaryFilter::bodyLimitExceeded,
            error -> {
              if (e.getResponse().isCommitted()) return Mono.error(error);
              return fail(e, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
            });
  }

  private static boolean bodyLimitExceeded(Throwable error) {
    // The HTTP proxy may wrap a request-publisher failure in a transport exception.
    for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
      if (error instanceof BodyLimitExceededException) return true;
    }
    return false;
  }

  private static final class BodyLimitExceededException extends RuntimeException {}

  private Mono<Void> fail(ServerWebExchange e, HttpStatus status, String code) {
    e.getResponse().setStatusCode(status);
    e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body =
        ("{\"code\":\"" + code + "\",\"message\":\"请求被网关拒绝\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap(body)));
  }
}
