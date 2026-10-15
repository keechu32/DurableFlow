package io.durableflow.integration;

import static org.assertj.core.api.Assertions.*;

import io.durableflow.api.ApiApplication;
import io.durableflow.model.*;
import io.durableflow.persistence.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SecurityIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

  static ConfigurableApplicationContext context;
  static ControlPlane control;
  static ExecutionStore store;
  static JdbcTemplate db;
  static Json json;
  static int port;

  @BeforeAll
  static void start() {
    context =
        new SpringApplicationBuilder(ApiApplication.class)
            .run(
                "--spring.config.name=durableflow-api",
                "--server.port=0",
                "--DURABLEFLOW_OPERATOR_TOKEN=" + TestAuth.OPERATOR,
                "--DURABLEFLOW_ADMIN_TOKEN=" + TestAuth.ADMIN,
                "--DURABLEFLOW_DB_URL=" + POSTGRES.getJdbcUrl(),
                "--DURABLEFLOW_DB_USER=" + POSTGRES.getUsername(),
                "--DURABLEFLOW_DB_PASSWORD=" + POSTGRES.getPassword());
    control = context.getBean(ControlPlane.class);
    store = context.getBean(ExecutionStore.class);
    db = context.getBean(JdbcTemplate.class);
    json = context.getBean(Json.class);
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
  }

  @BeforeEach
  void clean() {
    db.execute("TRUNCATE workflow_definitions,workers CASCADE");
  }

  HttpResponse<String> send(String method, String path, String body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          builder
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  UUID submit() {
    var definition =
        control.publish(
            new DefinitionRequest(
                "security",
                List.of(
                    new TaskSpec("a", "uppercase", List.of(), Map.of("text", "hello"), 1, 60))));
    return (UUID)
        control
            .submit(
                new Submission(
                    (UUID) definition.get("definition_id"),
                    1,
                    UUID.randomUUID().toString(),
                    Map.of()))
            .get("id");
  }

  @Test
  void authenticationAndAdminAuthorizationAreEnforcedAndAudited() throws Exception {
    assertThat(send("GET", "/api/v1/dead-letter-tasks", null, null).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/api/v1/dead-letter-tasks", null, "invalid").statusCode())
        .isEqualTo(401);
    UUID execution = submit();
    String cancel = "/api/v1/workflow-executions/" + execution + "/cancel";
    assertThat(send("POST", cancel, "{}", TestAuth.OPERATOR).statusCode()).isEqualTo(403);
    assertThat(control.execution(execution).get("status")).isEqualTo("PENDING");
    assertThat(send("POST", cancel, "{}", TestAuth.ADMIN).statusCode()).isEqualTo(200);
    assertThat(send("POST", cancel, "{}", TestAuth.ADMIN).statusCode()).isEqualTo(200);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT actor FROM audit_events", String.class))
        .isEqualTo("admin");
    assertThatThrownBy(() -> db.execute("UPDATE audit_events SET actor='tamper'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void manualRetryRequiresAdminAndAuditCommitsWithIt() throws Exception {
    submit();
    UUID worker = UUID.randomUUID();
    store.register(worker, Set.of("uppercase"));
    var lease = store.claim(worker, Set.of("uppercase"), 10).orElseThrow();
    store.start(lease);
    store.fail(lease, "HANDLER_ERROR");
    store.reconcile();
    String retry = "/api/v1/task-executions/" + lease.id() + "/retry";
    assertThat(send("POST", retry, "{}", TestAuth.OPERATOR).statusCode()).isEqualTo(403);
    assertThat(send("POST", retry, "{}", TestAuth.ADMIN).statusCode()).isEqualTo(200);
    assertThat(db.queryForObject("SELECT action FROM audit_events", String.class))
        .isEqualTo("TASK_RETRY");
  }

  @Test
  void strictJsonRejectsDuplicatesUnknownPropertiesAndCoercion() throws Exception {
    for (String body :
        List.of(
            "{\"name\":\"a\",\"name\":\"b\",\"tasks\":[]}",
            "{\"name\":\"a\",\"tasks\":[],\"unknown\":true}"))
      assertThat(send("POST", "/api/v1/workflow-definitions", body, TestAuth.OPERATOR).statusCode())
          .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/api/v1/workflow-executions",
                    "{\"definitionId\":\""
                        + UUID.randomUUID()
                        + "\",\"version\":\"1\",\"idempotencyKey\":\"a\"}",
                    TestAuth.OPERATOR)
                .statusCode())
        .isEqualTo(400);
    String nested = "{\"x\":".repeat(70) + "0" + "}".repeat(70);
    assertThat(send("POST", "/api/v1/workflow-definitions", nested, TestAuth.OPERATOR).statusCode())
        .isEqualTo(400);
  }

  @Test
  void bodyLimitAppliesToContentLengthAndChunkedBodies() throws Exception {
    String huge = "x".repeat(1048577);
    assertThat(send("POST", "/api/v1/workflow-definitions", huge, TestAuth.OPERATOR).statusCode())
        .isEqualTo(413);
    try (var client = HttpClient.newHttpClient()) {
      var req =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/v1/workflow-definitions"))
              .header("Authorization", "Bearer " + TestAuth.OPERATOR)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofInputStream(
                      () ->
                          new java.io.ByteArrayInputStream(
                              huge.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
              .build();
      assertThat(client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(413);
    }
  }

  @Test
  void metricsAndOpenApiAreAuthenticatedAndContainNoPayloads() throws Exception {
    assertThat(send("GET", "/v3/api-docs", null, null).statusCode()).isEqualTo(401);
    var openapi = send("GET", "/v3/api-docs", null, TestAuth.OPERATOR);
    assertThat(openapi.statusCode()).isEqualTo(200);
    var metrics = send("GET", "/actuator/prometheus", null, TestAuth.OPERATOR);
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body())
        .contains(
            "durableflow_active_leases",
            "durableflow_queue_depth",
            "durableflow_workflows_submitted_total",
            "durableflow_execution_duration_seconds")
        .doesNotContain(TestAuth.OPERATOR, TestAuth.ADMIN);
    assertThat(send("GET", "/actuator/env", null, TestAuth.ADMIN).statusCode()).isEqualTo(403);
  }

  @Test
  void rollbackDoesNotEmitSubmissionCounterOrAudit() {
    var registry = context.getBean(MeterRegistry.class);
    double before =
        registry.counter("durableflow.workflows.submitted", "status", "ACCEPTED").count();
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(
            s -> {
              UUID id = submit();
              control.cancel(id, "admin");
              s.setRollbackOnly();
            });
    assertThat(db.queryForObject("SELECT COUNT(*) FROM workflow_executions", Integer.class))
        .isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    assertThat(registry.counter("durableflow.workflows.submitted", "status", "ACCEPTED").count())
        .isEqualTo(before);
  }

  @Test
  void taskFailureLogsKeepIdentifiersAndTraceButRedactPayload() {
    String marker = "NEVER_LOG_PAYLOAD_SECRET_42";
    var definition =
        control.publish(
            new DefinitionRequest(
                "redaction",
                List.of(
                    new TaskSpec(
                        "bad",
                        "uppercase",
                        List.of(),
                        Map.of("text", Map.of("password", marker)),
                        1,
                        60))));
    control.submit(
        new Submission((UUID) definition.get("definition_id"), 1, "redaction", Map.of()));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(io.durableflow.worker.WorkerRuntime.class);
    var capture =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
          @Override
          protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    capture.start();
    logger.addAppender(capture);
    var worker =
        new io.durableflow.worker.WorkerRuntime(
            store,
            new io.durableflow.worker.Handlers(),
            context.getBean(io.durableflow.observability.Telemetry.class),
            5,
            10);
    try {
      worker.start();
      ExecutionIT.await(
          () ->
              db.queryForObject(
                      "SELECT COUNT(*) FROM task_executions WHERE status='DEAD_LETTERED'",
                      Integer.class)
                  == 1);
      worker.stop();
      var events =
          capture.list.stream()
              .filter(e -> e.getFormattedMessage().equals("Task execution finished"))
              .toList();
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().getMDCPropertyMap())
          .containsKeys("workflow_id", "task_id", "attempt", "worker_id", "trace_id", "span_id");
      assertThat(capture.list.toString()).doesNotContain(marker);
      assertThat(db.queryForObject("SELECT error_message FROM task_executions", String.class))
          .doesNotContain(marker);
    } finally {
      worker.stop();
      logger.detachAppender(capture);
      capture.stop();
    }
  }
}
