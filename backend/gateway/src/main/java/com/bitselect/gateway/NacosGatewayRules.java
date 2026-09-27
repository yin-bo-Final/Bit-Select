package com.bitselect.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.*;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.*;
import java.util.*;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nacos owns durable gateway rules; each instance subscribes and validates before applying. */
@Component
public class NacosGatewayRules {
  static final String DEFAULT_RULES =
      """
[{"resource":"catalog","count":100,"intervalSec":1},{"resource":"commerce","count":80,"intervalSec":1},{"resource":"ai","count":15,"intervalSec":1},{"resource":"inventory","count":40,"intervalSec":1}]
""";
  private static final Set<String> ROUTES = Set.of("catalog", "commerce", "ai", "inventory");
  private final String address, dataId, group;
  private volatile ConfigService config;
  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());

  public NacosGatewayRules(
      @Value("${app.sentinel.nacos-address:127.0.0.1:18848}") String address,
      @Value("${app.sentinel.data-id:bit-gateway-flow-rules.json}") String dataId,
      @Value("${app.sentinel.group:BIT_SELECT}") String group) {
    this.address = address;
    this.dataId = dataId;
    this.group = group;
    GatewayRuleManager.loadRules(parse(DEFAULT_RULES));
  }

  @PostConstruct
  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public synchronized void connect() {
    if (config != null) return;
    ConfigService candidate = null;
    try {
      Properties p = new Properties();
      p.setProperty("serverAddr", address);
      candidate = NacosFactory.createConfigService(p);
      String stored = candidate.getConfig(dataId, group, 5000);
      if (stored == null || stored.isBlank()) {
        stored = DEFAULT_RULES;
        if (!candidate.publishConfig(dataId, group, stored, "json"))
          throw new IllegalStateException("Nacos rule initialization not acknowledged");
      }
      GatewayRuleManager.loadRules(parse(stored));
      candidate.addListener(
          dataId,
          group,
          new Listener() {
            public Executor getExecutor() {
              return null;
            }

            public void receiveConfigInfo(String content) {
              try {
                GatewayRuleManager.loadRules(parse(content));
                log.info(
                    "Applied persisted Sentinel gateway rules from Nacos {}/{}", group, dataId);
              } catch (Exception e) {
                log.warn("Rejected invalid Nacos Sentinel rules; retaining current limits");
              }
            }
          });
      config = candidate;
      log.info("Subscribed Sentinel gateway rules to Nacos {}/{}", group, dataId);
    } catch (Exception e) {
      if (candidate != null)
        try {
          candidate.shutDown();
        } catch (Exception ignored) {
        }
      log.warn(
          "Nacos gateway-rule subscription pending; current limits remain active ({})",
          e.getClass().getSimpleName());
    }
  }

  static Set<GatewayFlowRule> parse(String content) {
    try {
      var node = new ObjectMapper().readTree(content);
      if (!node.isArray() || node.size() != ROUTES.size())
        throw new IllegalArgumentException("All gateway routes require one rule");
      Set<String> seen = new HashSet<>();
      Set<GatewayFlowRule> rules = new HashSet<>();
      for (var item : node) {
        String resource = item.path("resource").asText();
        double count = item.path("count").asDouble(-1);
        long interval = item.path("intervalSec").asLong(-1);
        if (!ROUTES.contains(resource)
            || !seen.add(resource)
            || !Double.isFinite(count)
            || count < 1
            || count > 100000
            || interval < 1
            || interval > 60) throw new IllegalArgumentException("Invalid gateway rule");
        rules.add(new GatewayFlowRule(resource).setCount(count).setIntervalSec(interval));
      }
      return rules;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Invalid gateway rule JSON", e);
    }
  }

  @PreDestroy
  public void close() {
    ConfigService current = config;
    if (current != null)
      try {
        current.shutDown();
      } catch (Exception ignored) {
      }
  }
}
