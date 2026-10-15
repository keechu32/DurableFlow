package io.durableflow.integration;

import static org.assertj.core.api.Assertions.*;

import io.durableflow.api.ApiApplication;
import io.durableflow.model.*;
import io.durableflow.persistence.*;
import io.durableflow.worker.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ExecutionIT {
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

  TaskSpec task(String id, String type, Map<String, Object> input, String... deps) {
    return new TaskSpec(id, type, List.of(deps), input, 3, 60);
  }

  UUID submit(TaskSpec... tasks) {
    var definition = control.publish(new DefinitionRequest("test", List.of(tasks)));
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

  WorkerRuntime worker() {
    return new WorkerRuntime(store, new Handlers(), 5, 10);
  }

  static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline)
        throw new AssertionError("Condition did not become true within 15 seconds");
      java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
  }

  String status(UUID execution) {
    store.reconcile();
    return control.execution(execution).get("status").toString();
  }

  @Test
  void atomicOwnershipUnderCompetingWorkers() throws Exception {
    submit(task("one", "uppercase", Map.of("text", "hello")));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var results = new ArrayList<Future<Optional<Lease>>>();
      for (int i = 0; i < 16; i++) {
        UUID worker = UUID.randomUUID();
        store.register(worker, Set.of("uppercase"));
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return store.claim(worker, Set.of("uppercase"), 10);
                }));
      }
      start.countDown();
      var leases = new ArrayList<Lease>();
      for (var f : results) f.get(15, TimeUnit.SECONDS).ifPresent(leases::add);
      assertThat(leases).hasSize(1);
      var lease = leases.getFirst();
      assertThat(store.start(lease)).isTrue();
      assertThat(store.start(lease)).isFalse();
      var forged =
          new Lease(
              lease.id(),
              lease.workflowId(),
              lease.taskDefinitionId(),
              lease.type(),
              lease.attempt(),
              UUID.randomUUID(),
              lease.input(),
              60);
      assertThat(store.succeed(forged, Map.of())).isFalse();
      assertThat(store.succeed(lease, Map.of("value", "HELLO"))).isTrue();
    }
  }

  @Test
  void capabilitiesFilterClaims() {
    UUID execution = submit(task("one", "uppercase", Map.of("text", "hello")));
    UUID worker = UUID.randomUUID();
    store.register(worker, Set.of("delay"));
    assertThat(store.claim(worker, Set.of("delay"), 10)).isEmpty();
    assertThat(control.tasks(execution, 100, 0).getFirst().get("attempt")).isEqualTo(0);
  }

  @Test
  void threeWorkersExecuteIndependentTasksConcurrently() {
    UUID execution =
        submit(
            task("a", "delay", Map.of("milliseconds", 500)),
            task("b", "delay", Map.of("milliseconds", 500)),
            task("c", "delay", Map.of("milliseconds", 500)));
    var workers = List.of(worker(), worker(), worker());
    workers.forEach(WorkerRuntime::start);
    try {
      await(
          () ->
              db.queryForObject(
                      "SELECT COUNT(DISTINCT lease_owner) FROM task_executions WHERE status='RUNNING'",
                      Integer.class)
                  == 3);
      await(() -> "SUCCEEDED".equals(status(execution)));
      assertThat(control.tasks(execution, 100, 0))
          .allMatch(t -> "SUCCEEDED".equals(t.get("status")));
    } finally {
      workers.forEach(WorkerRuntime::stop);
    }
  }

  @Test
  void dependenciesStartAfterEveryPrerequisiteCompleted() {
    UUID execution =
        submit(
            task("a", "delay", Map.of("milliseconds", 100)),
            task("b", "sha256", Map.of("text", "abc")),
            task("c", "uppercase", Map.of("text", "done"), "a", "b"));
    var workers = List.of(worker(), worker());
    workers.forEach(WorkerRuntime::start);
    try {
      await(() -> "SUCCEEDED".equals(status(execution)));
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM task_dependencies d JOIN task_executions p ON p.id=d.prerequisite_id JOIN task_executions c ON c.id=d.task_id WHERE c.started_at<p.completed_at",
                  Integer.class))
          .isZero();
    } finally {
      workers.forEach(WorkerRuntime::stop);
    }
  }

  @Test
  void permanentFailureCancelsDownstream() {
    UUID execution =
        submit(
            task("a", "uppercase", Map.of("text", 123)),
            task("b", "delay", Map.of("milliseconds", 0), "a"));
    var worker = worker();
    worker.start();
    try {
      await(() -> "FAILED".equals(status(execution)));
      assertThat(control.tasks(execution, 100, 0))
          .extracting(t -> t.get("status"))
          .containsExactlyInAnyOrder("FAILED", "CANCELLED");
    } finally {
      worker.stop();
    }
  }

  @Test
  void cancellationBeforeClaimAndDuringExecution() {
    UUID cancelled = submit(task("a", "uppercase", Map.of("text", "hello")));
    control.cancel(cancelled);
    var worker = worker();
    worker.start();
    try {
      UUID execution = submit(task("b", "delay", Map.of("milliseconds", 5000)));
      await(
          () ->
              db.queryForObject(
                      "SELECT COUNT(*) FROM task_executions WHERE workflow_id=? AND status='RUNNING'",
                      Integer.class,
                      execution)
                  == 1);
      control.cancel(execution);
      assertThat(control.tasks(execution, 100, 0).getFirst().get("status")).isEqualTo("CANCELLED");
      assertThat(control.tasks(cancelled, 100, 0).getFirst().get("attempt")).isEqualTo(0);
    } finally {
      worker.stop();
    }
  }

  @Test
  void gracefulShutdownDrainsCurrentTaskAndDoesNotClaimNext() {
    UUID first = submit(task("first", "delay", Map.of("milliseconds", 500)));
    var worker = worker();
    worker.start();
    try {
      await(
          () ->
              db.queryForObject(
                      "SELECT COUNT(*) FROM task_executions WHERE status='RUNNING'", Integer.class)
                  == 1);
      UUID second = submit(task("second", "uppercase", Map.of("text", "later")));
      worker.stop();
      assertThat(control.tasks(first, 100, 0).getFirst().get("status")).isEqualTo("SUCCEEDED");
      assertThat(control.tasks(second, 100, 0).getFirst().get("attempt")).isEqualTo(0);
      assertThat(
              db.queryForObject(
                  "SELECT status FROM workers WHERE id=?", String.class, worker.workerId()))
          .isEqualTo("OFFLINE");
    } finally {
      worker.stop();
    }
  }

  @Test
  void builtInHandlersAreRestrictedAndDeterministic() throws Exception {
    var handlers = new Handlers();
    UUID worker = UUID.randomUUID(), workflow = UUID.randomUUID();
    assertThat(
            handlers
                .execute(
                    new Lease(
                        UUID.randomUUID(),
                        workflow,
                        "sha",
                        "sha256",
                        1,
                        worker,
                        Map.of("text", "abc"),
                        60))
                .get("value"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(
            handlers.execute(
                new Lease(
                    UUID.randomUUID(),
                    workflow,
                    "json",
                    "json-transform",
                    1,
                    worker,
                    Map.of(
                        "operation",
                        "pick",
                        "fields",
                        List.of("safe"),
                        "value",
                        Map.of("safe", 1, "secret", 2)),
                    60)))
        .isEqualTo(Map.of("safe", 1));
    assertThatThrownBy(
            () ->
                handlers.execute(
                    new Lease(
                        UUID.randomUUID(),
                        workflow,
                        "shell",
                        "shell",
                        1,
                        worker,
                        Map.of("command", "echo unsafe"),
                        60)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
