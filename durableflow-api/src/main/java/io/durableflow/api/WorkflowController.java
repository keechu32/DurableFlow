package io.durableflow.api;

import io.durableflow.model.*;
import io.durableflow.persistence.ControlPlane;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {
  private final ControlPlane service;

  public WorkflowController(ControlPlane service) {
    this.service = service;
  }

  @PostMapping("/workflow-definitions")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> publish(@RequestBody DefinitionRequest request) {
    return service.publish(request);
  }

  @GetMapping("/workflow-definitions/{id}")
  public Map<String, Object> definition(@PathVariable UUID id) {
    return service.definition(id, null);
  }

  @GetMapping("/workflow-definitions/{id}/versions/{version}")
  public Map<String, Object> version(@PathVariable UUID id, @PathVariable int version) {
    return service.definition(id, version);
  }

  @PostMapping("/workflow-executions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> submit(@RequestBody Submission request) {
    return service.submit(request);
  }

  @GetMapping("/workflow-executions/{id}")
  public Map<String, Object> execution(@PathVariable UUID id) {
    return service.execution(id);
  }

  @GetMapping("/workflow-executions/{id}/tasks")
  public List<Map<String, Object>> tasks(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.tasks(id, limit, offset);
  }

  @PostMapping("/workflow-executions/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable UUID id) {
    return service.cancel(id);
  }
}
