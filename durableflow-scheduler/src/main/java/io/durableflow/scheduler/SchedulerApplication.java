package io.durableflow.scheduler;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SchedulerApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(SchedulerApplication.class);
    application.setDefaultProperties(Map.of("spring.config.name", "durableflow-scheduler"));
    application.run(args);
  }
}
