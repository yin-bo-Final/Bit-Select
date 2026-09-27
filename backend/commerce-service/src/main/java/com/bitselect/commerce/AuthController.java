package com.bitselect.commerce;

import com.bitselect.contracts.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final JdbcTemplate db;
  private final Users users;
  private final SessionService sessions;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

  public AuthController(JdbcTemplate db, Users users, SessionService sessions) {
    this.db = db;
    this.users = users;
    this.sessions = sessions;
  }

  record Register(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String username,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotBlank @Size(max = 80) String nickname) {}

  record Login(@NotBlank String username, @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/register")
  Object register(@Valid @RequestBody Register data, HttpServletResponse response) {
    KeyHolder key = new GeneratedKeyHolder();
    db.update(
        c -> {
          var p =
              c.prepareStatement(
                  "INSERT INTO users(username,password_hash,nickname,role,balance_cents)"
                      + " VALUES(?,?,?,'USER',0)",
                  Statement.RETURN_GENERATED_KEYS);
          p.setString(1, data.username());
          p.setString(2, passwords.encode(data.password()));
          p.setString(3, data.nickname());
          return p;
        },
        key);
    long id = Objects.requireNonNull(key.getKey()).longValue();
    sessions.login(id, response);
    return Map.of("user", users.get(id));
  }

  @PostMapping("/login")
  Object login(@Valid @RequestBody Login data, HttpServletResponse response) {
    var rows =
        db.queryForList("SELECT id,password_hash FROM users WHERE username=?", data.username());
    if (rows.isEmpty()
        || !passwords.matches(data.password(), (String) rows.getFirst().get("password_hash")))
      throw new ApiException(401, "INVALID_CREDENTIALS", "用户名或密码错误");
    long id = ((Number) rows.getFirst().get("id")).longValue();
    sessions.login(id, response);
    return Map.of("user", users.get(id));
  }

  @GetMapping("/me")
  Object me(HttpServletRequest request) {
    return users.get(sessions.requireUserId(request));
  }

  @PostMapping("/logout")
  Object logout(HttpServletRequest request, HttpServletResponse response) {
    sessions.logout(request, response);
    return Map.of("ok", true);
  }
}
