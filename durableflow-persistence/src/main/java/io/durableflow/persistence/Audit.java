package io.durableflow.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class Audit {
  private final JdbcTemplate db;

  public Audit(JdbcTemplate db) {
    this.db = db;
  }

  public void record(String action, String actor, UUID workflow, UUID task) {
    db.update(
        "INSERT INTO audit_events(id,action,actor,workflow_id,task_id) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        action,
        actor,
        workflow,
        task);
  }

  public java.util.List<java.util.Map<String, Object>> list(int limit, int offset) {
    ControlPlane.page(limit, offset);
    return db
        .queryForList(
            "SELECT * FROM audit_events ORDER BY created_at,id LIMIT ? OFFSET ?", limit, offset)
        .stream()
        .map(
            row -> {
              row.replaceAll(
                  (k, v) -> v instanceof java.sql.Timestamp t ? t.toInstant().toString() : v);
              return row;
            })
        .toList();
  }
}
