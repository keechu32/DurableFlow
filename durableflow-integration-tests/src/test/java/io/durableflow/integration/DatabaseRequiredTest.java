package io.durableflow.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.durableflow.api.ApiApplication;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class DatabaseRequiredTest {
  @Test
  void applicationDoesNotStartOrAcknowledgeReadinessWithoutDatabase() throws Exception {
    // Reserve an endpoint that never answers the PostgreSQL handshake.
    try (var socket = new ServerSocket()) {
      socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
      assertThatThrownBy(
              () ->
                  new SpringApplicationBuilder(ApiApplication.class)
                      .run(
                          "--spring.config.name=durableflow-api",
                          "--DURABLEFLOW_OPERATOR_TOKEN=" + TestAuth.OPERATOR,
                          "--DURABLEFLOW_ADMIN_TOKEN=" + TestAuth.ADMIN,
                          "--server.port=0",
                          "--DURABLEFLOW_DB_URL=jdbc:postgresql://127.0.0.1:"
                              + socket.getLocalPort()
                              + "/missing?connectTimeout=1&socketTimeout=1",
                          "--DURABLEFLOW_DB_USER=test",
                          "--DURABLEFLOW_DB_PASSWORD=test",
                          "--spring.datasource.hikari.initialization-fail-timeout=1"))
          .hasStackTraceContaining("Unable to obtain connection from database");
    }
  }
}
