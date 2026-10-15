package io.durableflow.api;

import io.durableflow.persistence.ExecutionStore;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RetryController {
  private final ExecutionStore store;

  public RetryController(ExecutionStore store) {
    this.store = store;
  }

  @PostMapping("/task-executions/{id}/retry")
  public Map<String, Object> retry(@PathVariable UUID id) {
    return Map.of("workflow_execution_id", store.retry(id), "task_execution_id", id);
  }

  @GetMapping("/dead-letter-tasks")
  public List<Map<String, Object>> deadLetters(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
    return store.deadLetters(limit, offset);
  }
}
