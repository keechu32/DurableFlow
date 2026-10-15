package io.durableflow.integration;

import static org.assertj.core.api.Assertions.*;

import io.durableflow.api.ApiApplication;
import io.durableflow.persistence.*;
import io.durableflow.scheduler.SchedulerApplication;
import io.durableflow.worker.WorkerApplication;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class TraceIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

  static final InMemorySpanExporter CAPTURE = InMemorySpanExporter.create();

  @Configuration
  static class Capture {
    @Bean
    @Primary
    SpanExporter capture() {
      return CAPTURE;
    }
  }

  @Test
  void oneTraceSpansApiSchedulerAndWorkerWithoutPayloads() throws Exception {
    CAPTURE.reset();
    try (var api =
            new SpringApplicationBuilder(ApiApplication.class, Capture.class).run(args("api"));
        var scheduler =
            new SpringApplicationBuilder(SchedulerApplication.class, Capture.class)
                .run(args("scheduler"));
        var worker =
            new SpringApplicationBuilder(WorkerApplication.class, Capture.class)
                .run(args("worker"));
        var client = HttpClient.newHttpClient()) {
      var control = api.getBean(ControlPlane.class);
      var json = api.getBean(Json.class);
      var definition =
          control.publish(
              new io.durableflow.model.DefinitionRequest(
                  "trace",
                  List.of(
                      new io.durableflow.model.TaskSpec(
                          "a",
                          "uppercase",
                          List.of(),
                          Map.of("text", "NEVER_EXPORT_PAYLOAD"),
                          3,
                          60))));
      int port = ((WebServerApplicationContext) api).getWebServer().getPort();
      var body =
          json.write(
              new io.durableflow.model.Submission(
                  (UUID) definition.get("definition_id"), 1, "trace-1", Map.of()));
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/v1/workflow-executions"))
                  .header("Authorization", "Bearer " + TestAuth.OPERATOR)
                  .header("Content-Type", "application/json")
                  .header("traceparent", "00-12345678901234567890123456789012-1234567890123456-01")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(202);
      UUID execution =
          UUID.fromString((String) ((Map<?, ?>) json.value(response.body())).get("id"));
      ExecutionIT.await(() -> "SUCCEEDED".equals(control.execution(execution).get("status")));
      ExecutionIT.await(
          () -> {
            for (var context : List.of(api, scheduler, worker))
              context
                  .getBean(OpenTelemetrySdk.class)
                  .getSdkTracerProvider()
                  .forceFlush()
                  .join(5, java.util.concurrent.TimeUnit.SECONDS);
            var services =
                CAPTURE.getFinishedSpanItems().stream()
                    .filter(span -> "12345678901234567890123456789012".equals(span.getTraceId()))
                    .map(
                        span ->
                            span.getResource()
                                .getAttribute(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "service.name")))
                    .collect(java.util.stream.Collectors.toSet());
            return services.containsAll(
                List.of("durableflow-api", "durableflow-scheduler", "durableflow-worker"));
          });
      var spans =
          CAPTURE.getFinishedSpanItems().stream()
              .filter(s -> "12345678901234567890123456789012".equals(s.getTraceId()))
              .toList();
      assertThat(spans)
          .extracting(
              s ->
                  s.getResource()
                      .getAttribute(
                          io.opentelemetry.api.common.AttributeKey.stringKey("service.name")))
          .contains("durableflow-api", "durableflow-scheduler", "durableflow-worker");
      assertThat(spans.toString()).doesNotContain("NEVER_EXPORT_PAYLOAD", TestAuth.OPERATOR);
      assertThat(spans).anyMatch(s -> "task.execute".equals(s.getName()));
    }
  }

  String[] args(String role) {
    return new String[] {
      "--spring.config.name=durableflow-" + role,
      "--server.port=0",
      "--DURABLEFLOW_OPERATOR_TOKEN=" + TestAuth.OPERATOR,
      "--DURABLEFLOW_ADMIN_TOKEN=" + TestAuth.ADMIN,
      "--DURABLEFLOW_DB_URL=" + POSTGRES.getJdbcUrl(),
      "--DURABLEFLOW_DB_USER=" + POSTGRES.getUsername(),
      "--DURABLEFLOW_DB_PASSWORD=" + POSTGRES.getPassword(),
      "--durableflow.poll-millis=10"
    };
  }
}
