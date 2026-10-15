package io.durableflow.api;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(ApiApplication.class);
    application.setDefaultProperties(Map.of("spring.config.name", "durableflow-api"));
    application.run(args);
  }
}
