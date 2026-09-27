package com.bitselect.commerce;

import com.bitselect.contracts.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Users {
  private final JdbcTemplate db;

  public Users(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> get(long id) {
    var rows =
        db.query(
            "SELECT id,username,nickname,role,balance_cents FROM users WHERE id=?",
            (r, n) ->
                Map.<String, Object>of(
                    "id",
                    r.getLong(1),
                    "username",
                    r.getString(2),
                    "nickname",
                    r.getString(3),
                    "role",
                    r.getString(4),
                    "balanceCents",
                    r.getLong(5)),
            id);
    if (rows.isEmpty()) throw new ApiException(401, "USER_NOT_FOUND", "用户不存在");
    return rows.getFirst();
  }

  public void admin(long id) {
    if (!"ADMIN".equals(get(id).get("role"))) throw new ApiException(403, "FORBIDDEN", "需要管理员权限");
  }
}
