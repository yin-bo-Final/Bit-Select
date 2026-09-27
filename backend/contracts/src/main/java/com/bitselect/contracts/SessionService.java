package com.bitselect.contracts;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  public static final Duration TTL = Duration.ofDays(7);
  private final StringRedisTemplate redis;
  private final boolean secure;
  private final SecureRandom random = new SecureRandom();

  public SessionService(
      StringRedisTemplate redis, @Value("${app.cookie-secure:false}") boolean secure) {
    this.redis = redis;
    this.secure = secure;
  }

  public String token(HttpServletRequest request) {
    if (request.getCookies() != null)
      for (Cookie c : request.getCookies())
        if (c.getName().equals("bit_session")) return c.getValue();
    String h = request.getHeader("Authorization");
    return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
  }

  public Long requireUserId(HttpServletRequest request) {
    String t = token(request);
    if (t == null || t.length() < 32 || t.length() > 256)
      throw new ApiException(401, "UNAUTHORIZED", "请先登录");
    String id = redis.opsForValue().getAndExpire(key(t), TTL);
    if (id == null) throw new ApiException(401, "SESSION_EXPIRED", "登录已过期，请重新登录");
    var attributes =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes a
        && a.getResponse() != null) cookie(a.getResponse(), t, TTL);
    return Long.valueOf(id);
  }

  public void login(long id, HttpServletResponse response) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String t = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    redis.opsForValue().set(key(t), Long.toString(id), TTL);
    cookie(response, t, TTL);
  }

  public void logout(HttpServletRequest request, HttpServletResponse response) {
    String t = token(request);
    if (t != null) redis.delete(key(t));
    cookie(response, "", Duration.ZERO);
  }

  private void cookie(HttpServletResponse response, String t, Duration age) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("bit_session", t)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(age)
            .build()
            .toString());
  }

  public static String key(String token) {
    try {
      return "bit:session:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
