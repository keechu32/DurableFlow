package io.durableflow.persistence;

import io.durableflow.model.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExecutionStore {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Json json;

  public ExecutionStore(JdbcTemplate db, PlatformTransactionManager manager, Json json) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
  }

  public void register(UUID worker, Set<String> types) {
    if (types.isEmpty()
        || types.size() > 16
        || types.stream().anyMatch(t -> !t.matches("[a-zA-Z0-9_-]{1,64}")))
      throw new IllegalArgumentException("Invalid worker capabilities");
    db.update(
        "INSERT INTO workers(id,supported_types,status,metadata) VALUES (?,?::text[],'ONLINE','{}'::jsonb) ON CONFLICT(id) DO UPDATE SET supported_types=EXCLUDED.supported_types,status='ONLINE',heartbeat_at=clock_timestamp()",
        worker,
        "{" + String.join(",", types) + "}");
  }

  public void workerHeartbeat(UUID worker) {
    db.update("UPDATE workers SET heartbeat_at=clock_timestamp() WHERE id=?", worker);
  }

  public void workerStatus(UUID worker, String status) {
    if (!Set.of("ONLINE", "DRAINING", "OFFLINE").contains(status))
      throw new IllegalArgumentException("Invalid worker status");
    db.update(
        "UPDATE workers SET status=?,heartbeat_at=clock_timestamp() WHERE id=?", status, worker);
  }

  public Optional<Lease> claim(UUID worker, Set<String> types, int leaseSeconds) {
    if (types.isEmpty()) return Optional.empty();
    String marks = String.join(",", Collections.nCopies(types.size(), "?"));
    return Optional.ofNullable(
        tx.execute(
            s -> {
              var workflows =
                  db.queryForList(
                      "SELECT w.id FROM workflow_executions w WHERE w.status IN ('PENDING','RUNNING') AND EXISTS (SELECT 1 FROM task_executions t WHERE t.workflow_id=w.id AND t.status='READY' AND t.available_at<=clock_timestamp() AND t.type IN ("
                          + marks
                          + ")) ORDER BY w.created_at,w.id LIMIT 1 FOR UPDATE OF w SKIP LOCKED",
                      types.toArray());
              if (workflows.isEmpty()) return null;
              UUID workflow = (UUID) workflows.getFirst().get("id");
              var args = new ArrayList<Object>();
              args.add(workflow);
              args.addAll(types);
              var rows =
                  db.queryForList(
                      "SELECT * FROM task_executions WHERE workflow_id=? AND status='READY' AND available_at<=clock_timestamp() AND type IN ("
                          + marks
                          + ") ORDER BY available_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                      args.toArray());
              if (rows.isEmpty()) return null;
              var task = rows.getFirst();
              UUID id = (UUID) task.get("id");
              TaskStatus.READY.requireTransitionTo(TaskStatus.LEASED);
              db.update(
                  "UPDATE task_executions SET status='LEASED',attempt=attempt+1,lease_owner=?,lease_expires_at=clock_timestamp()+(? * interval '1 second'),heartbeat_at=clock_timestamp(),version=version+1 WHERE id=?",
                  worker,
                  leaseSeconds,
                  id);
              WorkflowStatus.PENDING.requireTransitionTo(WorkflowStatus.RUNNING);
              db.update(
                  "UPDATE workflow_executions SET status='RUNNING',started_at=COALESCE(started_at,clock_timestamp()),version=version+1 WHERE id=? AND status='PENDING'",
                  workflow);
              return lease(db.queryForMap("SELECT * FROM task_executions WHERE id=?", id));
            }));
  }

  @SuppressWarnings("unchecked")
  private Lease lease(Map<String, Object> row) {
    return new Lease(
        (UUID) row.get("id"),
        (UUID) row.get("workflow_id"),
        (String) row.get("task_definition_id"),
        (String) row.get("type"),
        (Integer) row.get("attempt"),
        (UUID) row.get("lease_owner"),
        (Map<String, Object>) json.value(row.get("input").toString()),
        (Integer) row.get("timeout_seconds"));
  }

  private boolean lockWorkflow(UUID workflow) {
    var rows =
        db.queryForList("SELECT status FROM workflow_executions WHERE id=? FOR UPDATE", workflow);
    return !rows.isEmpty() && "RUNNING".equals(rows.getFirst().get("status"));
  }

  public boolean start(Lease lease) {
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              if (!lockWorkflow(lease.workflowId())) return false;
              TaskStatus.LEASED.requireTransitionTo(TaskStatus.RUNNING);
              return db.update(
                      "UPDATE task_executions SET status='RUNNING',started_at=clock_timestamp(),lease_expires_at=LEAST(lease_expires_at,clock_timestamp()+(timeout_seconds * interval '1 second')),version=version+1 WHERE id=? AND lease_owner=? AND attempt=? AND status='LEASED' AND lease_expires_at>clock_timestamp()",
                      lease.id(),
                      lease.workerId(),
                      lease.attempt())
                  == 1;
            }));
  }

  public boolean heartbeat(Lease lease, int seconds) {
    return db.update(
            "UPDATE task_executions SET heartbeat_at=clock_timestamp(),lease_expires_at=LEAST(started_at+(timeout_seconds * interval '1 second'),clock_timestamp()+(? * interval '1 second')),version=version+1 WHERE id=? AND lease_owner=? AND attempt=? AND status='RUNNING' AND lease_expires_at>clock_timestamp() AND started_at+(timeout_seconds * interval '1 second')>clock_timestamp()",
            seconds,
            lease.id(),
            lease.workerId(),
            lease.attempt())
        == 1;
  }

  public boolean succeed(Lease lease, Map<String, Object> output) {
    return finish(lease, output, null);
  }

  public boolean fail(Lease lease, String category) {
    return finish(lease, null, category);
  }

  private boolean finish(Lease lease, Map<String, Object> output, String error) {
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              if (!lockWorkflow(lease.workflowId())) return false;
              var rows =
                  db.queryForList(
                      "SELECT * FROM task_executions WHERE id=? AND lease_owner=? AND attempt=? AND status='RUNNING' AND lease_expires_at>clock_timestamp() FOR UPDATE",
                      lease.id(),
                      lease.workerId(),
                      lease.attempt());
              if (rows.isEmpty()) return false;
              TaskStatus.RUNNING.requireTransitionTo(
                  error == null ? TaskStatus.SUCCEEDED : TaskStatus.FAILED);
              var row = rows.getFirst();
              return db.update(
                      "UPDATE task_executions SET status=?,output=?::jsonb,error_category=?,error_message=?,completed_at=clock_timestamp(),lease_expires_at=NULL,version=version+1 WHERE id=? AND version=?",
                      error == null ? "SUCCEEDED" : "FAILED",
                      output == null ? null : json.write(output),
                      error,
                      error == null
                          ? null
                          : "Task handler failed; input and exception details redacted",
                      lease.id(),
                      row.get("version"))
                  == 1;
            }));
  }

  public void reconcile() {
    var ids =
        db.queryForList(
            "SELECT id FROM workflow_executions WHERE status IN ('PENDING','RUNNING') ORDER BY created_at,id",
            UUID.class);
    for (var id : ids)
      tx.executeWithoutResult(
          s -> {
            var rows =
                db.queryForList("SELECT status FROM workflow_executions WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) return;
            var state = WorkflowStatus.valueOf(rows.getFirst().get("status").toString());
            if (state.terminal()) return;
            int failed =
                db.queryForObject(
                    "SELECT COUNT(*) FROM task_executions WHERE workflow_id=? AND status IN ('FAILED','DEAD_LETTERED')",
                    Integer.class,
                    id);
            if (failed > 0) {
              state.requireTransitionTo(WorkflowStatus.FAILED);
              db.update(
                  "UPDATE task_executions SET status='CANCELLED',error_category='UPSTREAM_FAILED',error_message='Workflow failed',completed_at=clock_timestamp(),lease_expires_at=NULL,version=version+1 WHERE workflow_id=? AND status IN ('BLOCKED','READY','LEASED','RUNNING','RETRY_WAIT')",
                  id);
              db.update(
                  "UPDATE workflow_executions SET status='FAILED',completed_at=clock_timestamp(),version=version+1 WHERE id=?",
                  id);
              return;
            }
            db.update(
                "UPDATE task_executions t SET status='READY',available_at=clock_timestamp(),version=version+1 WHERE t.workflow_id=? AND t.status='BLOCKED' AND NOT EXISTS (SELECT 1 FROM task_dependencies d JOIN task_executions p ON p.id=d.prerequisite_id WHERE d.task_id=t.id AND p.status<>'SUCCEEDED')",
                id);
            int unfinished =
                db.queryForObject(
                    "SELECT COUNT(*) FROM task_executions WHERE workflow_id=? AND status<>'SUCCEEDED'",
                    Integer.class,
                    id);
            if (unfinished == 0) {
              state.requireTransitionTo(WorkflowStatus.SUCCEEDED);
              db.update(
                  "UPDATE workflow_executions SET status='SUCCEEDED',completed_at=clock_timestamp(),version=version+1 WHERE id=?",
                  id);
            }
          });
  }
}
