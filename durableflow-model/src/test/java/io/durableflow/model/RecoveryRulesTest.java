package io.durableflow.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RecoveryRulesTest {
  @Test
  void jitterIsBoundedExponentialAndCapped() {
    assertEquals(80, Backoff.milliseconds(1, 0));
    assertEquals(120, Backoff.milliseconds(1, 1));
    assertEquals(200, Backoff.milliseconds(2, .5));
    for (int n = 1; n < 100; n++) {
      long min = Backoff.milliseconds(n, 0), max = Backoff.milliseconds(n, 1);
      assertTrue(min > 0 && min <= max && max <= 30000);
    }
    assertEquals(30000, Backoff.milliseconds(100, .5));
    assertThrows(IllegalArgumentException.class, () -> Backoff.milliseconds(0, .5));
    assertThrows(IllegalArgumentException.class, () -> Backoff.milliseconds(1, Double.NaN));
  }

  @Test
  void onlyDependencyFailureAndFailedWorkflowCanBeReactivated() {
    assertDoesNotThrow(
        () -> TaskStatus.CANCELLED.requireDependencyRetry(TaskStatus.BLOCKED, "UPSTREAM_FAILED"));
    assertThrows(
        IllegalStateException.class,
        () -> TaskStatus.CANCELLED.requireDependencyRetry(TaskStatus.READY, "USER_CANCELLED"));
    for (var state : WorkflowStatus.values())
      if (state == WorkflowStatus.FAILED) assertDoesNotThrow(state::requireManualRetry);
      else assertThrows(IllegalStateException.class, state::requireManualRetry);
    assertFalse(ErrorCategory.INVALID_INPUT.retryable());
    assertTrue(ErrorCategory.LEASE_EXPIRED.retryable());
  }
}
