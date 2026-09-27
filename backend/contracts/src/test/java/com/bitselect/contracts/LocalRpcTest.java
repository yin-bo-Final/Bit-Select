package com.bitselect.contracts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalRpcTest {
  @Test
  void onlySupportedLoopbackMayBind() {
    assertDoesNotThrow(() -> LocalRpc.requireLoopback("::1"));
    assertThrows(IllegalArgumentException.class, () -> LocalRpc.requireLoopback("0.0.0.0"));
    assertThrows(IllegalArgumentException.class, () -> LocalRpc.requireLoopback("192.168.1.3"));
    assertThrows(IllegalArgumentException.class, () -> LocalRpc.requireLoopback("127.0.0.1"));
  }
}
