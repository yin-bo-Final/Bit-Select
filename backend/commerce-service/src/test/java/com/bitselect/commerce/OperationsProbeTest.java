package com.bitselect.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class OperationsProbeTest {
  ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  OperationsService service(
      List<OperationsService.ProbeTarget> targets, Duration timeout, Clock clock) {
    return new OperationsService(
        mock(JdbcTemplate.class), json, targets, timeout, Duration.ofSeconds(10), clock);
  }

  @SuppressWarnings("unchecked")
  List<OperationsService.ServiceHealth> health(OperationsService service) {
    return (List<OperationsService.ServiceHealth>) service.overview().get("services");
  }

  @Test
  void healthResultsAreCachedThenRefreshedAndNeverFollowRedirects() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger redirected = new AtomicInteger();
    server.createContext(
        "/actuator/health",
        exchange -> {
          calls.incrementAndGet();
          byte[] body =
              "{\"status\":\"UP\",\"secret\":\"PRIVATE_UPSTREAM\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/redirect/actuator/health",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/secret");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/secret",
        exchange -> {
          redirected.incrementAndGet();
          exchange.close();
        });
    server.start();
    var clock = new MutableClock();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    var service =
        service(
            List.of(
                new OperationsService.ProbeTarget("up", "up", "application", "http", base),
                new OperationsService.ProbeTarget(
                    "redirect", "redirect", "application", "http", base + "/redirect")),
            Duration.ofSeconds(2),
            clock);
    try {
      var first = health(service);
      assertEquals("up", first.getFirst().status());
      assertEquals("HEALTH_NOT_UP", first.get(1).detailCode());
      assertEquals(first, health(service));
      assertEquals(1, calls.get());
      clock.now = clock.now.plusSeconds(11);
      health(service);
      assertEquals(2, calls.get());
      assertEquals(0, redirected.get());
      assertFalse(json.writeValueAsString(first).contains("PRIVATE_UPSTREAM"));
      assertFalse(json.writeValueAsString(first).contains(base));
    } finally {
      service.close();
      server.stop(0);
    }
  }

  @Test
  void hangingUpstreamHasBoundedTimeoutAndTcpOnlyReportsReachability() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var release = new CountDownLatch(1);
    server.setExecutor(executor);
    server.createContext(
        "/actuator/health",
        exchange -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.start();
    try (var socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
      var service =
          service(
              List.of(
                  new OperationsService.ProbeTarget(
                      "hang",
                      "hang",
                      "application",
                      "http",
                      "http://127.0.0.1:" + server.getAddress().getPort()),
                  new OperationsService.ProbeTarget(
                      "tcp", "tcp", "middleware", "tcp", "127.0.0.1:" + socket.getLocalPort()),
                  new OperationsService.ProbeTarget(
                      "invalid",
                      "invalid",
                      "application",
                      "http",
                      "http://user:PRIVATE_PASSWORD@127.0.0.1:1")),
              Duration.ofMillis(150),
              Clock.systemUTC());
      try {
        var values = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> health(service));
        assertEquals("TIMEOUT", values.get(0).detailCode());
        assertEquals("TCP_REACHABLE", values.get(1).detailCode());
        assertEquals("unknown", values.get(2).status());
        assertEquals("INVALID_CONFIGURATION", values.get(2).detailCode());
        assertFalse(json.writeValueAsString(values).contains("PRIVATE_PASSWORD"));
      } finally {
        service.close();
      }
    } finally {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void middlewareConfigurationUsesOnlyHostAndPortAndInvalidValuesStayUnknown() {
    var env =
        new MockEnvironment()
            .withProperty("MYSQL_PORT", "3306")
            .withProperty("spring.data.redis.port", "6379")
            .withProperty("MILVUS_URI", "https://milvus.example.test/a/path?token=PRIVATE_TOKEN")
            .withProperty("NEO4J_URI", "bolt://user:PRIVATE_PASSWORD@localhost:7687");
    var targets = OperationsService.configuredTargets(env);
    assertEquals(13, targets.size());
    assertEquals(13L, targets.stream().map(OperationsService.ProbeTarget::id).distinct().count());
    assertEquals(
        "127.0.0.1:3306",
        targets.stream().filter(t -> t.id().equals("mysql")).findFirst().orElseThrow().endpoint());
    assertEquals(
        "127.0.0.1:6379",
        targets.stream().filter(t -> t.id().equals("redis")).findFirst().orElseThrow().endpoint());
    assertEquals(
        "milvus.example.test:443",
        targets.stream().filter(t -> t.id().equals("milvus")).findFirst().orElseThrow().endpoint());
    assertEquals(
        "",
        targets.stream().filter(t -> t.id().equals("neo4j")).findFirst().orElseThrow().endpoint());
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2025-01-01T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
