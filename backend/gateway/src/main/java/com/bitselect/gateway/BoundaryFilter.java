package com.bitselect.gateway;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
public class BoundaryFilter implements WebFilter, Ordered {
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
    if (size > 20 * 1024 * 1024) return fail(e, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
    return chain.filter(
        e.mutate()
            .request(
                e.getRequest()
                    .mutate()
                    .headers(
                        h -> {
                          h.remove("X-User-Id");
                          h.remove("X-User-Role");
                        })
                    .build())
            .build());
  }

  private Mono<Void> fail(ServerWebExchange e, HttpStatus status, String code) {
    e.getResponse().setStatusCode(status);
    e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body =
        ("{\"code\":\"" + code + "\",\"message\":\"请求被网关拒绝\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap(body)));
  }
}
