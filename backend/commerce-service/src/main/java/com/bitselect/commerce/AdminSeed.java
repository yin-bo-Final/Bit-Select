package com.bitselect.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final String username, password;

  public AdminSeed(
      JdbcTemplate db,
      @Value("${app.admin.username:admin}") String username,
      @Value("${app.admin.password:}") String password) {
    this.db = db;
    this.username = username;
    this.password = password;
  }

  public void run(ApplicationArguments args) {
    if (db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN'", Integer.class) > 0)
      return;
    if (password.length() < 12) throw new IllegalStateException("首次启动必须设置至少12位 ADMIN_PASSWORD");
    db.update(
        "INSERT INTO"
            + " users(username,password_hash,nickname,role,balance_cents)VALUES(?,?,?,'ADMIN',0)",
        username,
        new BCryptPasswordEncoder(12).encode(password),
        "比特严选管理员");
  }
}
