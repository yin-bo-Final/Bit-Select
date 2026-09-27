package com.bitselect.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NacosGatewayRulesTest {
  @Test
  void persistentDefaultsProtectEveryRoute() {
    assertEquals(4, NacosGatewayRules.parse(NacosGatewayRules.DEFAULT_RULES).size());
  }

  @Test
  void rejectMissingRouteAndUnsafeLimits() {
    assertThrows(IllegalArgumentException.class, () -> NacosGatewayRules.parse("[]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NacosGatewayRules.parse(
                NacosGatewayRules.DEFAULT_RULES.replace("\"count\":100", "\"count\":0")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NacosGatewayRules.parse(
                NacosGatewayRules.DEFAULT_RULES.replace("\"inventory\"", "\"ai\"")));
  }

  @Test
  void sentinelBlockBecomes429InsteadOfServerError() {
    var handler =
        new GatewayApplication()
            .sentinelErrors(org.springframework.http.codec.ServerCodecConfigurer.create());
    var exchange =
        org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(
                "/api/products"));
    handler
        .handle(exchange, new com.alibaba.csp.sentinel.slots.block.flow.FlowException("catalog"))
        .block();
    assertEquals(429, exchange.getResponse().getStatusCode().value());
    assertTrue(exchange.getResponse().getBodyAsString().block().contains("RATE_LIMITED"));
  }
}
