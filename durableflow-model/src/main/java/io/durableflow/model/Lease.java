package io.durableflow.model;

import java.util.*;

public record Lease(
    UUID id,
    UUID workflowId,
    String taskDefinitionId,
    String type,
    int attempt,
    UUID workerId,
    Map<String, Object> input,
    int timeoutSeconds,
    String traceParent) {
  public Lease(
      UUID id,
      UUID workflowId,
      String taskDefinitionId,
      String type,
      int attempt,
      UUID workerId,
      Map<String, Object> input,
      int timeoutSeconds) {
    this(id, workflowId, taskDefinitionId, type, attempt, workerId, input, timeoutSeconds, null);
  }

  public Lease {
    input = JsonPayload.snapshot(input);
  }

  public Map<String, Object> input() {
    return Collections.unmodifiableMap(input);
  }

  public String idempotencyKey() {
    return "durableflow:task:" + id;
  }
}
