package io.durableflow.model;

import java.util.Map;
import java.util.UUID;

public record Submission(
    UUID definitionId, int version, String idempotencyKey, Map<String, Object> input) {
  public Submission {
    input = input == null ? Map.of() : JsonPayload.snapshot(input);
  }

  public void validate() {
    if (definitionId == null || version < 1)
      throw FlowException.invalid("definitionId and a positive version are required");
    if (idempotencyKey == null || !idempotencyKey.matches("[a-zA-Z0-9._:-]{1,128}"))
      throw FlowException.invalid("Invalid idempotency key");
  }

  public Map<String, Object> input() {
    return java.util.Collections.unmodifiableMap(input);
  }
}
