package io.durableflow.integration;

import static org.assertj.core.api.Assertions.*;

import io.durableflow.api.ApiApplication;
import io.durableflow.model.*;
import io.durableflow.persistence.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ControlPlaneIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

  static ConfigurableApplicationContext context;
  static ControlPlane service;
  static JdbcTemplate db;
  static Json json;

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
    service = context.getBean(ControlPlane.class);
    db = context.getBean(JdbcTemplate.class);
    json = context.getBean(Json.class);
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
  }

  @BeforeEach
  void clean() {
    db.execute("TRUNCATE workflow_definitions CASCADE");
  }

  TaskSpec task(String id, String... dependencies) {
    return new TaskSpec(id, "uppercase", List.of(dependencies), Map.of("text", "hello"), 3, 60);
  }

  UUID definition(TaskSpec... tasks) {
    return (UUID)
        service.publish(new DefinitionRequest("example", List.of(tasks))).get("definition_id");
  }

  Submission request(UUID id, String key) {
    return new Submission(id, 1, key, Map.of("nested", Map.of("a", 1, "b", 2)));
  }

  @Test
  void versionsAreImmutable() {
    UUID id = definition(task("one"));
    var second = service.publish(new DefinitionRequest("example", List.of(task("changed"))));
    assertThat(second.get("version")).isEqualTo(2);
    assertThat(second.get("definition_id")).isEqualTo(id);
    assertThat(json.write(service.definition(id, 1).get("spec")))
        .contains("one")
        .doesNotContain("changed");
    assertThat(service.definition(id, null).get("version")).isEqualTo(2);
  }

  @Test
  void submissionPersistsDagAndReusesEquivalentKey() {
    UUID id = definition(task("root"), task("dependent", "root"), task("disconnected"));
    var execution = service.submit(request(id, "same"));
    UUID executionId = (UUID) execution.get("id");
    assertThat(service.submit(request(id, "same")).get("id")).isEqualTo(executionId);
    assertThat(service.tasks(executionId, 100, 0))
        .extracting(t -> t.get("status"))
        .containsExactlyInAnyOrder("READY", "READY", "BLOCKED");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM task_dependencies", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> service.submit(new Submission(id, 1, "same", Map.of("different", true))))
        .isInstanceOf(FlowException.class);
  }

  @Test
  void concurrentSubmissionCommitsExactlyOneExecutionAndTaskSet() throws Exception {
    UUID id = definition(task("a"), task("b", "a"));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<UUID>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return (UUID) service.submit(request(id, "concurrent")).get("id");
                }));
      start.countDown();
      var ids = new HashSet<UUID>();
      for (var f : results) ids.add(f.get(20, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM task_executions", Integer.class))
          .isEqualTo(2);
    }
  }

  @Test
  void cancellationIsDurableAndIdempotent() {
    UUID execution =
        (UUID)
            service
                .submit(request(definition(task("root"), task("child", "root")), "cancel"))
                .get("id");
    assertThat(service.cancel(execution).get("status")).isEqualTo("CANCELLED");
    assertThat(service.cancel(execution).get("status")).isEqualTo("CANCELLED");
    assertThat(service.tasks(execution, 100, 0)).allMatch(t -> "CANCELLED".equals(t.get("status")));
  }

  @Test
  void invalidDefinitionCannotLeavePartialDatabaseState() {
    assertThatThrownBy(() -> definition(task("a", "missing"))).isInstanceOf(FlowException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM workflow_definitions", Integer.class))
        .isZero();
  }

  @Test
  void apiReturnsStableProblemsAndOpenApi() throws Exception {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/api/v1/workflow-definitions"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"bad\",\"tasks\":[]}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.body()).contains("invalid-request").doesNotContain("stackTrace");
      var openapi =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(openapi.statusCode()).isEqualTo(200);
      assertThat(openapi.body()).contains("/api/v1/workflow-executions");
    }
  }

  @Test
  void jsonbIdempotencyIgnoresObjectKeyOrderAndNumericSpelling() {
    UUID id = definition(task("a"));
    var first = new LinkedHashMap<String, Object>();
    first.put("a", 1);
    first.put("b", null);
    var second = new LinkedHashMap<String, Object>();
    second.put("b", null);
    second.put("a", 1.0);
    var execution = service.submit(new Submission(id, 1, "semantic", first));
    assertThat(service.submit(new Submission(id, 1, "semantic", second)).get("id"))
        .isEqualTo(execution.get("id"));
  }

  @Test
  void httpSubmissionAcknowledgesOnlyCommittedExecution() throws Exception {
    UUID id = definition(task("a"));
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/api/v1/workflow-executions"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(json.write(request(id, "http"))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(202);
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM workflow_executions WHERE idempotency_key='http'",
                  Integer.class))
          .isEqualTo(1);
      assertThat(response.body()).contains("PENDING");
    }
  }
}
