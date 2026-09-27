package com.bitselect.contracts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.*;

class SessionServiceTest {
  @Test
  void authenticationUsesAtomicSevenDaySlidingExpiry() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    String token = "a".repeat(43);
    when(values.getAndExpire(SessionService.key(token), Duration.ofDays(7))).thenReturn("42");
    var request = new MockHttpServletRequest();
    request.setCookies(new Cookie("bit_session", token));
    assertEquals(42L, new SessionService(redis, false).requireUserId(request));
    verify(values).getAndExpire(SessionService.key(token), Duration.ofDays(7));
    assertFalse(SessionService.key(token).contains(token));
  }

  @Test
  void expiredSessionIsRejected() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var req = new MockHttpServletRequest();
    req.addHeader("Authorization", "Bearer " + "b".repeat(43));
    var error =
        assertThrows(ApiException.class, () -> new SessionService(redis, false).requireUserId(req));
    assertEquals(401, error.status);
  }

  @Test
  void loginCookieIsHttpOnlyAndLogoutRevokes() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var service = new SessionService(redis, false);
    var response = new MockHttpServletResponse();
    service.login(7, response);
    String cookie = response.getHeader("Set-Cookie");
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Lax"));
    assertTrue(cookie.contains("Max-Age=604800"));
    var req = new MockHttpServletRequest();
    req.setCookies(new Cookie("bit_session", "c".repeat(43)));
    service.logout(req, new MockHttpServletResponse());
    verify(redis).delete(SessionService.key("c".repeat(43)));
  }
}
