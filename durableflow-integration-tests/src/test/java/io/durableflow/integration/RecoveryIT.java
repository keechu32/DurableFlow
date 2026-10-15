package io.durableflow.integration;

import static org.assertj.core.api.Assertions.*;

import io.durableflow.api.ApiApplication;
import io.durableflow.model.*;
import io.durableflow.persistence.*;
import io.durableflow.scheduler.Leader;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RecoveryIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

  static ConfigurableApplicationContext context;
  static ControlPlane control;
  static ExecutionStore store;
  static JdbcTemplate db;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(ApiApplication.class)
            .run(
                "--spring.config.name=durableflow-api",
                "--DURABLEFLOW_OPERATOR_TOKEN=" + TestAuth.OPERATOR,
                "--DURABLEFLOW_ADMIN_TOKEN=" + TestAuth.ADMIN,
                "--server.port=0",
                "--DURABLEFLOW_DB_URL=" + POSTGRES.getJdbcUrl(),
                "--DURABLEFLOW_DB_USER=" + POSTGRES.getUsername(),
                "--DURABLEFLOW_DB_PASSWORD=" + POSTGRES.getPassword());
    control = context.getBean(ControlPlane.class);
    store = context.getBean(ExecutionStore.class);
    db = context.getBean(JdbcTemplate.class);
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
  }

  @BeforeEach
  void clean() {
    db.execute("TRUNCATE workflow_definitions,workers CASCADE");
  }

  UUID submit(int attempts, boolean child) {
    var root = new TaskSpec("root", "uppercase", List.of(), Map.of("text", "hello"), attempts, 60);
    var tasks = new ArrayList<TaskSpec>();
    tasks.add(root);
    if (child)
      tasks.add(
          new TaskSpec(
              "child", "uppercase", List.of("root"), Map.of("text", "world"), attempts, 60));
    var definition = control.publish(new DefinitionRequest("recovery", tasks));
    return (UUID)
        control
            .submit(
                new Submission(
                    (UUID) definition.get("definition_id"),
                    (Integer) definition.get("version"),
                    UUID.randomUUID().toString(),
                    Map.of()))
            .get("id");
  }

  Lease claim() {
    UUID worker = UUID.randomUUID();
    store.register(worker, Set.of("uppercase"));
    return store.claim(worker, Set.of("uppercase"), 10).orElseThrow();
  }

  void expire(Lease lease) {
    db.update(
        "UPDATE task_executions SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        lease.id());
  }

  void makeReady() {
    db.update(
        "UPDATE task_executions SET available_at=clock_timestamp()-interval '1 second' WHERE status='RETRY_WAIT'");
    store.reconcile();
  }

  String taskStatus(Lease lease) {
    return db.queryForObject(
        "SELECT status FROM task_executions WHERE id=?", String.class, lease.id());
  }

  @Test
  void crashBeforeStartAndCrashDuringExecutionAreRecovered() {
    for (boolean started : List.of(false, true)) {
      UUID execution = submit(3, false);
      var first = claim();
      if (started) assertThat(store.start(first)).isTrue();
      expire(first);
      assertThat(store.heartbeat(first, 10)).isFalse();
      store.recover();
      assertThat(taskStatus(first)).isEqualTo("RETRY_WAIT");
      makeReady();
      var second = claim();
      assertThat(second.id()).isEqualTo(first.id());
      assertThat(second.attempt()).isEqualTo(2);
      assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
      assertThat(store.start(first)).isFalse();
      assertThat(store.succeed(first, Map.of("value", "stale"))).isFalse();
      assertThat(store.start(second)).isTrue();
      assertThat(store.succeed(second, Map.of("value", "HELLO"))).isTrue();
      store.reconcile();
      assertThat(control.execution(execution).get("status")).isEqualTo("SUCCEEDED");
    }
  }

  @Test
  void retriesExhaustAndManualRetryReopensOnlyDependencyCancelledTasks() {
    UUID execution = submit(2, true);
    Lease lease = claim();
    store.start(lease);
    assertThat(store.fail(lease, "HANDLER_ERROR")).isTrue();
    assertThat(taskStatus(lease)).isEqualTo("RETRY_WAIT");
    assertThat(store.fail(lease, "HANDLER_ERROR")).isTrue();
    makeReady();
    lease = claim();
    store.start(lease);
    store.fail(lease, "HANDLER_ERROR");
    store.reconcile();
    assertThat(taskStatus(lease)).isEqualTo("DEAD_LETTERED");
    assertThat(control.execution(execution).get("status")).isEqualTo("FAILED");
    assertThat(store.deadLetters(100, 0)).hasSize(1);
    store.retry(lease.id());
    var retried = claim();
    assertThat(retried.attempt()).isEqualTo(3);
    store.start(retried);
    store.succeed(retried, Map.of("value", "HELLO"));
    store.reconcile();
    var child = claim();
    assertThat(child.taskDefinitionId()).isEqualTo("child");
    store.start(child);
    store.succeed(child, Map.of("value", "WORLD"));
    store.reconcile();
    assertThat(control.execution(execution).get("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void nonRetryableFailureDeadLettersImmediately() {
    submit(10, false);
    var lease = claim();
    store.start(lease);
    store.fail(lease, "INVALID_INPUT");
    assertThat(taskStatus(lease)).isEqualTo("DEAD_LETTERED");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM task_attempts", Integer.class)).isEqualTo(1);
  }

  @Test
  void expiredSlowWorkerCannotExtendOrCommitAndTimeoutIsCategorized() {
    submit(3, false);
    var first = claim();
    store.start(first);
    db.update(
        "UPDATE task_executions SET started_at=clock_timestamp()-interval '61 seconds',lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
        first.id());
    assertThat(store.heartbeat(first, 10)).isFalse();
    assertThat(store.succeed(first, Map.of())).isFalse();
    store.recover();
    assertThat(
            db.queryForObject(
                "SELECT error_category FROM task_executions WHERE id=?", String.class, first.id()))
        .isEqualTo("TIMEOUT");
  }

  @Test
  void duplicateSuccessIsIdempotentEvenAfterWorkflowCompletion() throws Exception {
    UUID execution = submit(3, false);
    var lease = claim();
    store.start(lease);
    assertThat(store.succeed(lease, Map.of("value", "HELLO"))).isTrue();
    store.reconcile();
    long version =
        db.queryForObject("SELECT version FROM task_executions WHERE id=?", Long.class, lease.id());
    try (var pool = Executors.newFixedThreadPool(8)) {
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 16; i++)
        results.add(pool.submit(() -> store.succeed(lease, Map.of("value", "HELLO"))));
      for (var f : results) assertThat(f.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(store.succeed(lease, Map.of("value", "different"))).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT version FROM task_executions WHERE id=?", Long.class, lease.id()))
        .isEqualTo(version);
    assertThat(control.execution(execution).get("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void lostCompletionAfterEffectCanRepeatButDestinationKeyDeduplicates() {
    submit(3, false);
    var first = claim();
    store.start(first);
    db.execute("CREATE TABLE IF NOT EXISTS external_effects (key TEXT PRIMARY KEY)");
    db.execute("TRUNCATE external_effects");
    assertThat(
            db.update(
                "INSERT INTO external_effects(key) VALUES (?) ON CONFLICT DO NOTHING",
                first.idempotencyKey()))
        .isEqualTo(1);
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(
            s -> {
              store.succeed(first, Map.of("value", "HELLO"));
              s.setRollbackOnly();
            });
    assertThat(taskStatus(first)).isEqualTo("RUNNING");
    expire(first);
    store.recover();
    makeReady();
    var second = claim();
    store.start(second);
    assertThat(
            db.update(
                "INSERT INTO external_effects(key) VALUES (?) ON CONFLICT DO NOTHING",
                second.idempotencyKey()))
        .isZero();
    store.succeed(second, Map.of("value", "HELLO"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM task_attempts", Integer.class)).isEqualTo(2);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM external_effects", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void databaseConnectionLossDuringCompletionRollsBackAndCanBeReportedAgain() {
    submit(3, false);
    var lease = claim();
    store.start(lease);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(
                        s -> {
                          store.succeed(lease, Map.of("value", "HELLO"));
                          int pid = db.queryForObject("SELECT pg_backend_pid()", Integer.class);
                          try (var connection =
                                  DriverManager.getConnection(
                                      POSTGRES.getJdbcUrl(),
                                      POSTGRES.getUsername(),
                                      POSTGRES.getPassword());
                              var statement =
                                  connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
                            statement.setInt(1, pid);
                            statement.execute();
                          } catch (java.sql.SQLException ex) {
                            throw new IllegalStateException(ex);
                          }
                        }))
        .isInstanceOf(RuntimeException.class);
    assertThat(taskStatus(lease)).isEqualTo("RUNNING");
    assertThat(store.succeed(lease, Map.of("value", "HELLO"))).isTrue();
  }

  @Test
  void schedulerTransactionCrashDoesNotExposePartialUnblock() throws Exception {
    UUID execution = submit(3, true);
    var root = claim();
    store.start(root);
    store.succeed(root, Map.of("value", "HELLO"));
    try (var crashed =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      crashed.setAutoCommit(false);
      try (var statement =
          crashed.prepareStatement(
              "UPDATE task_executions SET status='READY' WHERE workflow_id=? AND status='BLOCKED'")) {
        statement.setObject(1, execution);
        statement.executeUpdate();
      }
      crashed.abort(Runnable::run);
    }
    assertThat(
            db.queryForObject(
                "SELECT status FROM task_executions WHERE task_definition_id='child'",
                String.class))
        .isEqualTo("BLOCKED");
    store.reconcile();
    assertThat(
            db.queryForObject(
                "SELECT status FROM task_executions WHERE task_definition_id='child'",
                String.class))
        .isEqualTo("READY");
  }

  @Test
  void competingSchedulersElectOneAndLeadershipTransfersAfterBackendDeath() {
    try (var first = new Leader(context.getBean(DataSource.class));
        var second = new Leader(context.getBean(DataSource.class))) {
      assertThat(first.elected()).isTrue();
      assertThat(second.elected()).isFalse();
      db.execute(
          "SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' AND objid=741938251 AND granted");
      assertThat(first.elected()).isFalse();
      assertThat(second.elected()).isTrue();
      assertThat(first.elected()).isFalse();
    }
  }

  @Test
  void heartbeatNeverExtendsBeyondTimeoutAndCancellationIsIdempotent() {
    UUID execution = submit(3, false);
    var lease = claim();
    db.update("UPDATE task_executions SET timeout_seconds=1 WHERE id=?", lease.id());
    store.start(lease);
    store.heartbeat(lease, 300);
    assertThat(
            db.queryForObject(
                "SELECT lease_expires_at<=started_at+interval '1 second' FROM task_executions WHERE id=?",
                Boolean.class,
                lease.id()))
        .isTrue();
    control.cancel(execution);
    long version = ((Number) control.execution(execution).get("version")).longValue();
    control.cancel(execution);
    assertThat(((Number) control.execution(execution).get("version")).longValue())
        .isEqualTo(version);
    assertThat(store.succeed(lease, Map.of())).isFalse();
    assertThatThrownBy(() -> store.retry(lease.id())).isInstanceOf(FlowException.class);
  }
}
