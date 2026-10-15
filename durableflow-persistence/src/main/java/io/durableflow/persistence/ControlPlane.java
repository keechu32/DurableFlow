package io.durableflow.persistence;

import io.durableflow.model.*;
import io.durableflow.observability.Telemetry;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ControlPlane {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Json json;
  private final Telemetry telemetry;
  private final Audit audit;

  public ControlPlane(
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

  public Map<String, Object> publish(DefinitionRequest request) {
    request.validate();
    return tx.execute(
        s -> {
          db.update(
              "INSERT INTO workflow_definitions(id,name) VALUES (?,?) ON CONFLICT(name) DO NOTHING",
              UUID.randomUUID(),
              request.name());
          var definition =
              db.queryForMap(
                  "SELECT * FROM workflow_definitions WHERE name=? FOR UPDATE", request.name());
          UUID id = (UUID) definition.get("id");
          int version =
              db.queryForObject(
                  "SELECT COALESCE(MAX(version),0)+1 FROM definition_versions WHERE definition_id=?",
                  Integer.class,
                  id);
          db.update(
              "INSERT INTO definition_versions(definition_id,version,spec) VALUES (?,?,?::jsonb)",
              id,
              version,
              json.write(request));
          return definition(id, version);
        });
  }

  public Map<String, Object> definition(UUID id, Integer version) {
    String sql =
        "SELECT v.*,d.name FROM definition_versions v JOIN workflow_definitions d ON d.id=v.definition_id WHERE d.id=?";
    List<Map<String, Object>> rows =
        version == null
            ? db.queryForList(sql + " ORDER BY version DESC LIMIT 1", id)
            : db.queryForList(sql + " AND v.version=?", id, version);
    if (rows.isEmpty()) throw FlowException.missing();
    return convert(rows.getFirst());
  }

  public Map<String, Object> submit(Submission request) {
    request.validate();
    return tx.execute(
        s -> {
          var definition = definition(request.definitionId(), request.version());
          UUID id = UUID.randomUUID();
          String encoded = json.write(request);
          int inserted =
              db.update(
                  "INSERT INTO workflow_executions(id,definition_id,definition_version,idempotency_key,request,input,status,trace_parent) VALUES (?,?,?,?,?::jsonb,?::jsonb,'PENDING',?) ON CONFLICT(idempotency_key) DO NOTHING",
                  id,
                  request.definitionId(),
                  request.version(),
                  request.idempotencyKey(),
                  encoded,
                  json.write(request.input()),
                  telemetry.traceParent());
          if (inserted == 0) {
            var existing =
                db.queryForMap(
                    "SELECT id,(request=?::jsonb) AS equivalent FROM workflow_executions WHERE idempotency_key=?",
                    encoded,
                    request.idempotencyKey());
            if (!Boolean.TRUE.equals(existing.get("equivalent")))
              throw FlowException.conflict("Idempotency key was used with a different request");
            return execution((UUID) existing.get("id"));
          }
          DefinitionRequest spec =
              json.read(json.write(definition.get("spec")), DefinitionRequest.class);
          var ids = new HashMap<String, UUID>();
          spec.tasks().forEach(t -> ids.put(t.id(), UUID.randomUUID()));
          for (var task : spec.tasks())
            db.update(
                "INSERT INTO task_executions(id,workflow_id,task_definition_id,type,status,max_attempts,attempt_limit,timeout_seconds,input,trace_parent) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)",
                ids.get(task.id()),
                id,
                task.id(),
                task.type(),
                task.dependsOn().isEmpty() ? "READY" : "BLOCKED",
                task.maxAttempts(),
                task.maxAttempts(),
                task.timeoutSeconds(),
                json.write(task.input().isEmpty() ? request.input() : task.input()),
                telemetry.traceParent());
          for (var task : spec.tasks())
            for (var parent : task.dependsOn())
              db.update(
                  "INSERT INTO task_dependencies(task_id,prerequisite_id) VALUES (?,?)",
                  ids.get(task.id()),
                  ids.get(parent));
          telemetry.workflow(id);
          AfterCommit.run(() -> telemetry.count("workflows.submitted", "ACCEPTED"));
          return execution(id);
        });
  }

  public Map<String, Object> execution(UUID id) {
    var rows = db.queryForList("SELECT * FROM workflow_executions WHERE id=?", id);
    if (rows.isEmpty()) throw FlowException.missing();
    return convert(rows.getFirst());
  }

  public List<Map<String, Object>> tasks(UUID id, int limit, int offset) {
    execution(id);
    page(limit, offset);
    return db
        .queryForList(
            "SELECT * FROM task_executions WHERE workflow_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
            id,
            limit,
            offset)
        .stream()
        .map(this::convert)
        .toList();
  }

  public Map<String, Object> cancel(UUID id) {
    return cancel(id, "system");
  }

  public Map<String, Object> cancel(UUID id, String actor) {
    return tx.execute(
        s -> {
          var rows =
              db.queryForList("SELECT status FROM workflow_executions WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) throw FlowException.missing();
          var status = WorkflowStatus.valueOf((String) rows.getFirst().get("status"));
          if (status == WorkflowStatus.CANCELLED || status == WorkflowStatus.CANCELLING)
            return execution(id);
          if (status.terminal())
            throw FlowException.conflict("A terminal workflow cannot be cancelled");
          status.requireTransitionTo(WorkflowStatus.CANCELLING);
          db.update(
              "UPDATE workflow_executions SET status='CANCELLING',cancelled_at=clock_timestamp(),cancellation_reason='Requested by client',version=version+1 WHERE id=?",
              id);
          db.update(
              "UPDATE task_attempts SET status='CANCELLED',completed_at=clock_timestamp() WHERE task_id IN (SELECT id FROM task_executions WHERE workflow_id=?) AND status IN ('LEASED','RUNNING')",
              id);
          db.update(
              "UPDATE task_executions SET status='CANCELLED',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL,version=version+1 WHERE workflow_id=? AND status IN ('BLOCKED','READY','LEASED','RUNNING','RETRY_WAIT')",
              id);
          audit.record("WORKFLOW_CANCEL", actor, id, null);
          AfterCommit.run(() -> telemetry.count("workflows.completed", "CANCELLED"));
          WorkflowStatus.CANCELLING.requireTransitionTo(WorkflowStatus.CANCELLED);
          db.update(
              "UPDATE workflow_executions SET status='CANCELLED',completed_at=clock_timestamp(),version=version+1 WHERE id=?",
              id);
          return execution(id);
        });
  }

  public static void page(int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0 || offset > 1000000)
      throw FlowException.invalid("Invalid pagination bounds");
  }

  public Map<String, Object> convert(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    row.forEach(
        (k, v) ->
            result.put(
                k,
                v instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().toString() : v));
    for (String key : List.of("spec", "input", "output", "request", "metadata"))
      if (row.get(key) != null) result.put(key, json.value(row.get(key).toString()));
    result.remove("request");
    return result;
  }
}
