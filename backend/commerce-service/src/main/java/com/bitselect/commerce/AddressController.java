package com.bitselect.commerce;

import com.bitselect.contracts.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {
  private final JdbcTemplate db;
  private final SessionService sessions;

  public AddressController(JdbcTemplate db, SessionService sessions) {
    this.db = db;
    this.sessions = sessions;
  }

  record Address(
      @NotBlank @Size(max = 80) String recipient,
      @NotBlank @Pattern(regexp = "[+0-9 -]{6,24}") String phone,
      @NotBlank @Size(max = 500) String detail,
      boolean isDefault) {}

  @GetMapping
  Object list(HttpServletRequest r) {
    return Map.of("items", addresses(sessions.requireUserId(r)));
  }

  private List<Map<String, Object>> addresses(long user) {
    return db.query(
        "SELECT id,recipient,phone,detail,is_default FROM user_addresses WHERE user_id=? ORDER BY"
            + " is_default DESC,id DESC",
        (rs, n) ->
            Map.of(
                "id",
                rs.getLong(1),
                "recipient",
                rs.getString(2),
                "phone",
                rs.getString(3),
                "detail",
                rs.getString(4),
                "isDefault",
                rs.getBoolean(5)),
        user);
  }

  private void lock(long user) {
    db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, user);
  }

  @PostMapping
  @Transactional
  Object create(HttpServletRequest r, @Valid @RequestBody Address a) {
    long user = sessions.requireUserId(r);
    lock(user);
    int count =
        db.queryForObject(
            "SELECT COUNT(*) FROM user_addresses WHERE user_id=?", Integer.class, user);
    if (count >= 20) throw ApiException.bad("最多保存20个地址");
    boolean primary = a.isDefault() || count == 0;
    if (primary) db.update("UPDATE user_addresses SET is_default=FALSE WHERE user_id=?", user);
    KeyHolder key = new GeneratedKeyHolder();
    db.update(
        c -> {
          var p =
              c.prepareStatement(
                  "INSERT INTO"
                      + " user_addresses(user_id,recipient,phone,detail,is_default)VALUES(?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          p.setLong(1, user);
          p.setString(2, a.recipient());
          p.setString(3, a.phone());
          p.setString(4, a.detail());
          p.setBoolean(5, primary);
          return p;
        },
        key);
    return addresses(user).stream()
        .filter(
            x ->
                ((Number) x.get("id")).longValue()
                    == Objects.requireNonNull(key.getKey()).longValue())
        .findFirst()
        .orElseThrow();
  }

  @PutMapping("/{address}")
  @Transactional
  Object update(HttpServletRequest r, @PathVariable long address, @Valid @RequestBody Address a) {
    long user = sessions.requireUserId(r);
    lock(user);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM user_addresses WHERE id=? AND user_id=?",
            Integer.class,
            address,
            user)
        == 0) throw ApiException.missing();
    if (a.isDefault())
      db.update("UPDATE user_addresses SET is_default=FALSE WHERE user_id=?", user);
    db.update(
        "UPDATE user_addresses SET recipient=?,phone=?,detail=?,is_default=? WHERE id=? AND"
            + " user_id=?",
        a.recipient(),
        a.phone(),
        a.detail(),
        a.isDefault(),
        address,
        user);
    ensureDefault(user);
    return addresses(user).stream()
        .filter(x -> ((Number) x.get("id")).longValue() == address)
        .findFirst()
        .orElseThrow();
  }

  @DeleteMapping("/{address}")
  @Transactional
  Object delete(HttpServletRequest r, @PathVariable long address) {
    long user = sessions.requireUserId(r);
    lock(user);
    if (db.update("DELETE FROM user_addresses WHERE id=? AND user_id=?", address, user) == 0)
      throw ApiException.missing();
    ensureDefault(user);
    return Map.of("ok", true);
  }

  private void ensureDefault(long user) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM user_addresses WHERE user_id=? AND is_default=TRUE",
            Integer.class,
            user)
        == 0) {
      var ids =
          db.query(
              "SELECT id FROM user_addresses WHERE user_id=? ORDER BY id DESC LIMIT 1",
              (rs, n) -> rs.getLong(1),
              user);
      if (!ids.isEmpty())
        db.update("UPDATE user_addresses SET is_default=TRUE WHERE id=?", ids.getFirst());
    }
  }
}
