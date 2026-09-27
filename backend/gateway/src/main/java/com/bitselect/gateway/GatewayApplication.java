package com.bitselect.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.*;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class GatewayApplication {
  public static void main(String[] args) {
    String dashboard = System.getenv("SENTINEL_DASHBOARD");
    if (dashboard != null) System.setProperty("csp.sentinel.dashboard.server", dashboard);
    System.setProperty("project.name", "bit-gateway");
    SpringApplication.run(GatewayApplication.class, args);
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
                        "/api/ai/**", "/api/assistant/**",
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
    GatewayRuleManager.loadRules(
        Set.of(
            new GatewayFlowRule("catalog").setCount(100).setIntervalSec(1),
            new GatewayFlowRule("commerce").setCount(80).setIntervalSec(1),
            new GatewayFlowRule("ai").setCount(15).setIntervalSec(1)));
    return new SentinelGatewayFilter(-1);
  }
}
