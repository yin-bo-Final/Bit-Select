package com.bitselect.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.*;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class GatewayApplication {
  public static void main(String[] args) {
    configureNacosLogging();
    String dashboard = System.getenv("SENTINEL_DASHBOARD");
    if (dashboard != null) System.setProperty("csp.sentinel.dashboard.server", dashboard);
    System.setProperty("project.name", "bit-gateway");
    SpringApplication.run(GatewayApplication.class, args);
  }

  static void configureNacosLogging() {
    // Nacos rolling files must belong to one JVM; Windows cannot rename a shared open file.
    System.getProperties()
        .putIfAbsent(
            "JM.LOG.PATH",
            java.nio.file.Path.of(
                    System.getProperty("user.home"),
                    "logs",
                    "bit-select",
                    "gateway",
                    Long.toString(ProcessHandle.current().pid()))
                .toString());
  }

  @Bean
  RedisRateLimiter ipLimiter() {
    return new RedisRateLimiter(20, 40, 1);
  }

  @Bean
  KeyResolver ipKey() {
    return exchange ->
        Mono.just(
            exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
  }

  @Bean
  RouteLocator routes(
      RouteLocatorBuilder b,
      RedisRateLimiter limiter,
      KeyResolver key,
      @Value("${app.catalog-url}") String catalog,
      @Value("${app.commerce-url}") String commerce,
      @Value("${app.ai-url}") String ai) {
    return b.routes()
        .route(
            "inventory",
            r ->
                r.path("/api/admin/products/*/inventory")
                    .filters(
                        f ->
                            f.requestRateLimiter(
                                c -> c.setRateLimiter(limiter).setKeyResolver(key)))
                    .uri(commerce))
        .route(
            "catalog",
            r ->
                r.path("/api/products/**", "/api/categories", "/api/admin/products/**")
                    .filters(
                        f ->
                            f.requestRateLimiter(
                                c -> c.setRateLimiter(limiter).setKeyResolver(key)))
                    .uri(catalog))
        .route(
            "ai",
            r ->
                r.path(
                        "/api/ai/**",
                        "/api/assistant/**",
                        "/api/knowledge/**",
                        "/api/memory/**",
                        "/api/admin/knowledge/**")
                    .filters(
                        f ->
                            f.requestRateLimiter(
                                c -> c.setRateLimiter(limiter).setKeyResolver(key)))
                    .uri(ai))
        .route(
            "commerce",
            r ->
                r.path("/api/**")
                    .filters(
                        f ->
                            f.requestRateLimiter(
                                c -> c.setRateLimiter(limiter).setKeyResolver(key)))
                    .uri(commerce))
        .build();
  }

  @Bean
  GlobalFilter sentinel() {
    return new SentinelGatewayFilter(-1);
  }

  @Bean
  @org.springframework.core.annotation.Order(-2)
  com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler
      sentinelErrors(org.springframework.http.codec.ServerCodecConfigurer codecs) {
    com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager.setBlockHandler(
        (exchange, error) ->
            org.springframework.web.reactive.function.server.ServerResponse.status(429)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("code", "RATE_LIMITED", "message", "请求过于频繁，请稍后再试")));
    return new com.alibaba.csp.sentinel.adapter.gateway.sc.exception
        .SentinelGatewayBlockExceptionHandler(java.util.List.of(), codecs);
  }
}
