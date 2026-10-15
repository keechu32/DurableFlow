package io.durableflow.persistence;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class EngineMetrics {
  private final JdbcTemplate db;

  public EngineMetrics(JdbcTemplate db, MeterRegistry registry) {
    this.db = db;
    for (String status :
        java.util.List.of(
            "BLOCKED",
            "READY",
            "LEASED",
            "RUNNING",
            "RETRY_WAIT",
            "SUCCEEDED",
            "FAILED",
            "DEAD_LETTERED",
            "CANCELLED"))
      Gauge.builder(
              "durableflow.queue.depth",
              () -> number("SELECT COUNT(*) FROM task_executions WHERE status=?", status))
          .tag("status", status)
          .register(registry);
    Gauge.builder(
            "durableflow.active.leases",
            () ->
                number(
                    "SELECT COUNT(*) FROM task_executions WHERE status IN ('LEASED','RUNNING') AND lease_expires_at>clock_timestamp()"))
        .register(registry);
    Gauge.builder(
            "durableflow.worker.heartbeat.age",
            () ->
                number(
                    "SELECT COALESCE(MAX(EXTRACT(EPOCH FROM clock_timestamp()-heartbeat_at)),0) FROM workers WHERE status='ONLINE'"))
        .baseUnit("seconds")
        .register(registry);
  }

  private double number(String sql, Object... args) {
    try {
      Number n = db.queryForObject(sql, Number.class, args);
      return n == null ? 0 : n.doubleValue();
    } catch (RuntimeException ex) {
      return Double.NaN;
    }
  }
}
