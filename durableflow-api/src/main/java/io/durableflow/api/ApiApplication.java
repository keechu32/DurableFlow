package io.durableflow.api;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.durableflow.api", "io.durableflow.persistence"})
public class ApiApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(ApiApplication.class);
    application.setDefaultProperties(Map.of("spring.config.name", "durableflow-api"));
    application.run(args);
  }
}
