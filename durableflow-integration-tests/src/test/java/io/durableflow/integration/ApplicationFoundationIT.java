package io.durableflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.durableflow.api.ApiApplication;
import io.durableflow.scheduler.SchedulerApplication;
import io.durableflow.worker.WorkerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ApplicationFoundationIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

  static Stream<Arguments> applications() {
    return Stream.of(
        Arguments.of(ApiApplication.class, "api"),
        Arguments.of(SchedulerApplication.class, "scheduler"),
        Arguments.of(WorkerApplication.class, "worker"));
  }

  @ParameterizedTest
  @MethodSource("applications")
  void eachApplicationMigratesAndServesHealth(Class<?> application, String role) throws Exception {
    try (var context =
        new SpringApplicationBuilder(application)
            .properties(
                Map.of(
                    "spring.config.name", "durableflow-" + role,
                    "DURABLEFLOW_DB_URL", POSTGRES.getJdbcUrl(),
                    "DURABLEFLOW_DB_USER", POSTGRES.getUsername(),
                    "DURABLEFLOW_DB_PASSWORD", POSTGRES.getPassword(),
                    "server.port", "0"))
            .run(
                "--server.port=0",
                "--DURABLEFLOW_OPERATOR_TOKEN=" + TestAuth.OPERATOR,
                "--DURABLEFLOW_ADMIN_TOKEN=" + TestAuth.ADMIN)) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      try (var client = HttpClient.newHttpClient()) {
        for (String path : new String[] {"health", "health/liveness", "health/readiness"}) {
          var response =
              client.send(
                  HttpRequest.newBuilder(
                          URI.create("http://localhost:" + port + "/actuator/" + path))
                      .timeout(Duration.ofSeconds(5))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(response.statusCode()).isEqualTo(200);
          assertThat(response.body())
              .contains("\"status\":\"UP\"")
              .doesNotContain("jdbc:", "password");
        }
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/env"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isIn(401, 403, 404);
      }
      var flyway = context.getBean(Flyway.class);
      flyway.validate();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      try (var connection = context.getBean(javax.sql.DataSource.class).getConnection();
          var statement = connection.createStatement();
          var result = statement.executeQuery("SHOW TIME ZONE")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("UTC");
      }
    }
  }

  @Test
  void migrationRejectsDuplicateFoundationMarkers() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load();
    flyway.migrate();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () -> statement.executeUpdate("INSERT INTO engine_metadata(singleton) VALUES (TRUE)"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("duplicate key");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate("INSERT INTO engine_metadata(singleton) VALUES (FALSE)"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("check constraint");
    }
  }

  @Test
  void concurrentMigrationAppliesEachVersionOnlyOnce() throws Exception {
    // The application uses PostgreSQL's existing public schema. Pre-create an isolated
    // equivalent so this checks migration serialization rather than schema-creation races.
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA concurrent_startup");
    }
    int pending =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("concurrent_startup")
            .load()
            .info()
            .pending()
            .length;
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> migrate =
          () -> {
            start.await();
            return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("concurrent_startup")
                .load()
                .migrate()
                .migrationsExecuted;
          };
      var first = executor.submit(migrate);
      var second = executor.submit(migrate);
      start.countDown();
      assertThat(
              first.get(30, java.util.concurrent.TimeUnit.SECONDS)
                  + second.get(30, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(pending);
    }
  }

  @Test
  void checksumDriftPreventsMigration() throws Exception {
    var flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("checksum_drift")
            .load();
    flyway.migrate();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE checksum_drift.flyway_schema_history "
              + "SET checksum = checksum + 1 WHERE version = '1'");
      assertThatThrownBy(flyway::migrate)
          .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class)
          .hasMessageContaining("checksum mismatch");
    }
  }
}
