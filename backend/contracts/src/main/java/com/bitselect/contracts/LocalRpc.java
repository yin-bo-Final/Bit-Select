package com.bitselect.contracts;

import java.net.InetAddress;

/** Dubbo rejects 127.*; IPv6 loopback binds only this computer and works with Nacos. */
public final class LocalRpc {
  private LocalRpc() {}

  public static void configure() {
    String bind = System.getenv().getOrDefault("DUBBO_IP_TO_BIND", "::1"),
        registry = System.getenv().getOrDefault("DUBBO_IP_TO_REGISTRY", bind);
    requireLoopback(bind);
    requireLoopback(registry);
    System.setProperty("DUBBO_IP_TO_BIND", bind);
    System.setProperty("DUBBO_IP_TO_REGISTRY", registry);
  }

  static void requireLoopback(String address) {
    try {
      if (!InetAddress.getByName(address).isLoopbackAddress())
        throw new IllegalArgumentException("RPC must bind to a loopback address");
      if (address.startsWith("127.") || address.equalsIgnoreCase("localhost"))
        throw new IllegalArgumentException("Dubbo requires IPv6 loopback ::1 for local-only RPC");
    } catch (java.net.UnknownHostException e) {
      throw new IllegalArgumentException("Invalid RPC loopback address", e);
    }
  }
}
