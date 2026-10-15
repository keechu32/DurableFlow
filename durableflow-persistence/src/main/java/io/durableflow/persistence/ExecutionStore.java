package io.durableflow.persistence;

import io.durableflow.model.*;
import io.durableflow.observability.Telemetry;
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
  private final Telemetry telemetry;
  private final Audit audit;

  public ExecutionStore(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      Json json,
      Telemetry telemetry,
      Audit audit) {
    this.telemetry = telemetry;
    this.audit = audit;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
  }

  public void register(UUID worker, Set<String> types) {
    register(worker, types, "embedded");
  }

  public void register(UUID worker, Set<String> types, String instance) {
    if (instance == null || !instance.matches("[a-zA-Z0-9_.-]{1,128}"))
      throw new IllegalArgumentException("Invalid instance metadata");
    if (types.isEmpty()
        || types.size() > 16
        || types.stream().anyMatch(t -> !t.matches("[a-zA-Z0-9_-]{1,64}")))
      throw new IllegalArgumentException("Invalid worker capabilities");
    db.update(
        "INSERT INTO workers(id,supported_types,status,metadata) VALUES (?,?::text[],'ONLINE',?::jsonb) ON CONFLICT(id) DO UPDATE SET supported_types=EXCLUDED.supported_types,metadata=EXCLUDED.metadata,status='ONLINE',heartbeat_at=clock_timestamp()",
        worker,
        "{" + String.join(",", types) + "}",
        json.write(Map.of("instance", instance)));
  }

  public void workerHeartbeat(UUID worker) {
    db.update(
        "UPDATE workers SET heartbeat_at=clock_timestamp(),status=CASE WHEN status='OFFLINE' THEN 'ONLINE' ELSE status END WHERE id=?",
        worker);
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
                  "UPDATE task_executions SET status='LEASED',attempt=attempt+1,started_at=NULL,completed_at=NULL,output=NULL,error_category=NULL,error_message=NULL,lease_owner=?,lease_expires_at=clock_timestamp()+(? * interval '1 second'),heartbeat_at=clock_timestamp(),version=version+1 WHERE id=?",
                  worker,
                  leaseSeconds,
                  id);
              db.update(
                  "INSERT INTO task_attempts(task_id,attempt,worker_id,status) SELECT id,attempt,lease_owner,'LEASED' FROM task_executions WHERE id=?",
                  id);
              WorkflowStatus.PENDING.requireTransitionTo(WorkflowStatus.RUNNING);
              db.update(
                  "UPDATE workflow_executions SET status='RUNNING',started_at=COALESCE(started_at,clock_timestamp()),version=version+1 WHERE id=? AND status='PENDING'",
                  workflow);
              var claimed = lease(db.queryForMap("SELECT * FROM task_executions WHERE id=?", id));
              AfterCommit.run(
                  () -> {
                    telemetry.count("tasks.claimed", "LEASED");
                    if (claimed.attempt() > 1) telemetry.count("retry.attempts", "CLAIMED");
                  });
              event("task.claim", claimed.workflowId(), claimed.id());
              return claimed;
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
        (Integer) row.get("timeout_seconds"),
        (String) row.get("trace_parent"));
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
              int changed =
                  db.update(
                      "UPDATE task_executions SET status='RUNNING',started_at=clock_timestamp(),lease_expires_at=LEAST(lease_expires_at,clock_timestamp()+(timeout_seconds * interval '1 second')),version=version+1 WHERE id=? AND lease_owner=? AND attempt=? AND status='LEASED' AND lease_expires_at>clock_timestamp()",
                      lease.id(),
                      lease.workerId(),
                      lease.attempt());
              if (changed == 1)
                db.update(
                    "UPDATE task_attempts SET status='RUNNING',started_at=clock_timestamp() WHERE task_id=? AND attempt=?",
                    lease.id(),
                    lease.attempt());
              if (changed == 1) {
                double seconds =
                    db.queryForObject(
                        "SELECT EXTRACT(EPOCH FROM started_at-available_at) FROM task_executions WHERE id=?",
                        Double.class,
                        lease.id());
                AfterCommit.run(
                    () -> telemetry.timing("scheduling.latency", (long) (seconds * 1000000000L)));
              }
              return changed == 1;
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
    ErrorCategory category = error == null ? null : ErrorCategory.valueOf(error);
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              var workflow =
                  db.queryForList(
                      "SELECT status FROM workflow_executions WHERE id=? FOR UPDATE",
                      lease.workflowId());
              if (workflow.isEmpty()) return false;
              var history =
                  db.queryForList(
                      "SELECT status,error_category,(output IS NOT DISTINCT FROM ?::jsonb) AS equivalent FROM task_attempts WHERE task_id=? AND attempt=? AND worker_id=?",
                      output == null ? null : json.write(output),
                      lease.id(),
                      lease.attempt(),
                      lease.workerId());
              if (history.isEmpty()) return false;
              var attempt = history.getFirst();
              if ("SUCCEEDED".equals(attempt.get("status")))
                return category == null && Boolean.TRUE.equals(attempt.get("equivalent"));
              if ("FAILED".equals(attempt.get("status")))
                return category != null && category.name().equals(attempt.get("error_category"));
              if (!"RUNNING".equals(workflow.getFirst().get("status"))) return false;
              var rows =
                  db.queryForList(
                      "SELECT * FROM task_executions WHERE id=? AND lease_owner=? AND attempt=? AND status='RUNNING' AND lease_expires_at>clock_timestamp() FOR UPDATE",
                      lease.id(),
                      lease.workerId(),
                      lease.attempt());
              if (rows.isEmpty()) return false;
              var row = rows.getFirst();
              if (category != null) {
                retryOrDead(row, category, "FAILED");
                return true;
              }
              TaskStatus.RUNNING.requireTransitionTo(TaskStatus.SUCCEEDED);
              int changed =
                  db.update(
                      "UPDATE task_executions SET status='SUCCEEDED',output=?::jsonb,completed_at=clock_timestamp(),lease_expires_at=NULL,version=version+1 WHERE id=? AND version=?",
                      json.write(output),
                      lease.id(),
                      row.get("version"));
              if (changed != 1) throw new IllegalStateException("Concurrent task update");
              db.update(
                  "UPDATE task_attempts SET status='SUCCEEDED',output=?::jsonb,completed_at=clock_timestamp() WHERE task_id=? AND attempt=?",
                  json.write(output),
                  lease.id(),
                  lease.attempt());
              AfterCommit.run(() -> telemetry.count("tasks.completed", "SUCCEEDED"));
              return true;
            }));
  }

  private void retryOrDead(Map<String, Object> row, ErrorCategory category, String attemptStatus) {
    boolean retry =
        category.retryable() && (Integer) row.get("attempt") < (Integer) row.get("attempt_limit");
    TaskStatus from = TaskStatus.valueOf(row.get("status").toString());
    TaskStatus to = retry ? TaskStatus.RETRY_WAIT : TaskStatus.DEAD_LETTERED;
    from.requireTransitionTo(to);
    long delay =
        retry
            ? Backoff.milliseconds(
                (Integer) row.get("attempt"),
                java.util.concurrent.ThreadLocalRandom.current().nextDouble())
            : 0;
    int changed =
        db.update(
            "UPDATE task_executions SET status=?,error_category=?,error_message='Task attempt failed; payload and exception details redacted',available_at=clock_timestamp()+(? * interval '1 millisecond'),completed_at=CASE WHEN ? THEN NULL ELSE clock_timestamp() END,lease_expires_at=NULL,version=version+1 WHERE id=? AND version=?",
            to.name(),
            category.name(),
            delay,
            retry,
            row.get("id"),
            row.get("version"));
    if (changed != 1) throw new IllegalStateException("Concurrent task update");
    AfterCommit.run(
        () -> {
          telemetry.count("tasks.completed", to.name());
          if (!retry) telemetry.count("dead.lettered", "DEAD_LETTERED");
          if ("EXPIRED".equals(attemptStatus))
            telemetry.count("lease.expirations", category.name());
        });
    db.update(
        "UPDATE task_attempts SET status=?,error_category=?,completed_at=clock_timestamp() WHERE task_id=? AND attempt=?",
        attemptStatus,
        category.name(),
        row.get("id"),
        row.get("attempt"));
  }

  public void recover() {
    var ids =
        db.queryForList(
            "SELECT DISTINCT workflow_id FROM task_executions WHERE status IN ('LEASED','RUNNING') AND lease_expires_at<=clock_timestamp()",
            UUID.class);
    for (var id : ids)
      tx.executeWithoutResult(
          s -> {
            if (!lockWorkflow(id)) return;
            var tasks =
                db.queryForList(
                    "SELECT *, (started_at IS NOT NULL AND started_at+(timeout_seconds * interval '1 second')<=clock_timestamp()) AS timed_out FROM task_executions WHERE workflow_id=? AND status IN ('LEASED','RUNNING') AND lease_expires_at<=clock_timestamp() FOR UPDATE",
                    id);
            for (var task : tasks)
              retryOrDead(
                  task,
                  Boolean.TRUE.equals(task.get("timed_out"))
                      ? ErrorCategory.TIMEOUT
                      : ErrorCategory.LEASE_EXPIRED,
                  "EXPIRED");
          });
    db.update(
        "UPDATE workers SET status='OFFLINE' WHERE status='ONLINE' AND heartbeat_at<clock_timestamp()-interval '30 seconds'");
  }

  public List<Map<String, Object>> deadLetters(int limit, int offset) {
    ControlPlane.page(limit, offset);
    return db
        .queryForList(
            "SELECT * FROM task_executions WHERE status='DEAD_LETTERED' ORDER BY completed_at,id LIMIT ? OFFSET ?",
            limit,
            offset)
        .stream()
        .map(
            row -> {
              Map<String, Object> copy = new LinkedHashMap<>(row);
              copy.remove("input");
              copy.remove("output");
              copy.replaceAll(
                  (k, v) -> v instanceof java.sql.Timestamp t ? t.toInstant().toString() : v);
              return copy;
            })
        .toList();
  }

  public UUID retry(UUID taskId) {
    return retry(taskId, "system");
  }

  public UUID retry(UUID taskId, String actor) {
    return tx.execute(
        s -> {
          var lookup =
              db.queryForList("SELECT workflow_id FROM task_executions WHERE id=?", taskId);
          if (lookup.isEmpty()) throw FlowException.missing();
          UUID workflow = (UUID) lookup.getFirst().get("workflow_id");
          var state =
              WorkflowStatus.valueOf(
                  db.queryForObject(
                      "SELECT status FROM workflow_executions WHERE id=? FOR UPDATE",
                      String.class,
                      workflow));
          var row = db.queryForMap("SELECT * FROM task_executions WHERE id=? FOR UPDATE", taskId);
          if (state == WorkflowStatus.CANCELLED || !"DEAD_LETTERED".equals(row.get("status")))
            throw FlowException.conflict(
                "Only dead-lettered tasks in non-cancelled workflows can be retried");
          TaskStatus.DEAD_LETTERED.requireTransitionTo(TaskStatus.READY);
          db.update(
              "UPDATE task_executions SET status='READY',attempt_limit=attempt+max_attempts,available_at=clock_timestamp(),started_at=NULL,completed_at=NULL,error_category=NULL,error_message=NULL,lease_owner=NULL,lease_expires_at=NULL,version=version+1 WHERE id=? AND version=?",
              taskId,
              row.get("version"));
          int remaining =
              db.queryForObject(
                  "SELECT COUNT(*) FROM task_executions WHERE workflow_id=? AND status='DEAD_LETTERED'",
                  Integer.class,
                  workflow);
          if (remaining == 0) {
            var cancelled =
                db.queryForList(
                    "SELECT id,status,error_category FROM task_executions WHERE workflow_id=? AND status='CANCELLED' AND error_category='UPSTREAM_FAILED'",
                    workflow);
            for (var task : cancelled) {
              TaskStatus.CANCELLED.requireDependencyRetry(
                  TaskStatus.BLOCKED, task.get("error_category").toString());
            }
            db.update(
                "UPDATE task_executions SET status='BLOCKED',attempt_limit=attempt+max_attempts,available_at=clock_timestamp(),started_at=NULL,completed_at=NULL,error_category=NULL,error_message=NULL,lease_owner=NULL,lease_expires_at=NULL,version=version+1 WHERE workflow_id=? AND status='CANCELLED' AND error_category='UPSTREAM_FAILED'",
                workflow);
            if (state == WorkflowStatus.FAILED) {
              state.requireManualRetry();
              db.update(
                  "UPDATE workflow_executions SET status='RUNNING',completed_at=NULL,version=version+1 WHERE id=?",
                  workflow);
            }
          }
          audit.record("TASK_RETRY", actor, workflow, taskId);
          return workflow;
        });
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
              event("workflow.failed", id, null);
              AfterCommit.run(() -> telemetry.count("workflows.completed", "FAILED"));
              db.update(
                  "UPDATE task_attempts SET status='CANCELLED',completed_at=clock_timestamp() WHERE task_id IN (SELECT id FROM task_executions WHERE workflow_id=?) AND status IN ('LEASED','RUNNING')",
                  id);
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
                "UPDATE task_executions SET status='READY',version=version+1 WHERE workflow_id=? AND status='RETRY_WAIT' AND available_at<=clock_timestamp()",
                id);
            db.update(
                "UPDATE task_executions t SET status='READY',available_at=clock_timestamp(),version=version+1 WHERE t.workflow_id=? AND t.status='BLOCKED' AND NOT EXISTS (SELECT 1 FROM task_dependencies d JOIN task_executions p ON p.id=d.prerequisite_id WHERE d.task_id=t.id AND p.status<>'SUCCEEDED')",
                id);
            int unfinished =
                db.queryForObject(
                    "SELECT COUNT(*) FROM task_executions WHERE workflow_id=? AND status<>'SUCCEEDED'",
                    Integer.class,
                    id);
            if (unfinished == 0) {
              event("workflow.completed", id, null);
              AfterCommit.run(() -> telemetry.count("workflows.completed", "SUCCEEDED"));
              state.requireTransitionTo(WorkflowStatus.SUCCEEDED);
              db.update(
                  "UPDATE workflow_executions SET status='SUCCEEDED',completed_at=clock_timestamp(),version=version+1 WHERE id=?",
                  id);
            }
          });
  }

  private void event(String name, UUID workflow, UUID task) {
    String parent =
        db.queryForObject(
            "SELECT trace_parent FROM workflow_executions WHERE id=?", String.class, workflow);
    var attributes = new HashMap<String, String>();
    attributes.put("workflow_id", workflow.toString());
    if (task != null) attributes.put("task_id", task.toString());
    AfterCommit.run(
        () -> {
          try (var trace = telemetry.start(name, parent, attributes)) {
            /* Only committed state-change events and whitelisted identifiers. */
          }
        });
  }

  public List<Map<String, Object>> attempts(UUID task, int limit, int offset) {
    ControlPlane.page(limit, offset);
    if (db.queryForObject("SELECT COUNT(*) FROM task_executions WHERE id=?", Integer.class, task)
        == 0) throw FlowException.missing();
    return db
        .queryForList(
            "SELECT task_id,attempt,worker_id,status,leased_at,started_at,completed_at,error_category FROM task_attempts WHERE task_id=? ORDER BY attempt LIMIT ? OFFSET ?",
            task,
            limit,
            offset)
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
