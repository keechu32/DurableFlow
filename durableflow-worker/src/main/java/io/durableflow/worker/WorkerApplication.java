package io.durableflow.worker;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkerApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(WorkerApplication.class);
    application.setDefaultProperties(Map.of("spring.config.name", "durableflow-worker"));
    application.run(args);
  }
}
