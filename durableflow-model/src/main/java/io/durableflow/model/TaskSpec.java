package io.durableflow.model;

import java.util.List;
import java.util.Map;

public record TaskSpec(
    String id,
    String type,
    List<String> dependsOn,
    Map<String, Object> input,
    Integer maxAttempts,
    Integer timeoutSeconds) {
  public TaskSpec {
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    input = input == null ? Map.of() : JsonPayload.snapshot(input);
    maxAttempts = maxAttempts == null ? Integer.valueOf(3) : maxAttempts;
    timeoutSeconds = timeoutSeconds == null ? Integer.valueOf(60) : timeoutSeconds;
  }

  public Map<String, Object> input() {
    return java.util.Collections.unmodifiableMap(input);
  }
}
