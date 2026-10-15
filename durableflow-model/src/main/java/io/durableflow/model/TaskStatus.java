package io.durableflow.model;

import java.util.Set;

public enum TaskStatus {
  BLOCKED,
  READY,
  LEASED,
  RUNNING,
  RETRY_WAIT,
  SUCCEEDED,
  FAILED,
  DEAD_LETTERED,
  CANCELLED;

  public boolean canTransitionTo(TaskStatus target) {
    return switch (this) {
      case BLOCKED, RETRY_WAIT -> Set.of(READY, CANCELLED).contains(target);
      case READY -> Set.of(LEASED, CANCELLED).contains(target);
      case LEASED -> Set.of(RUNNING, RETRY_WAIT, DEAD_LETTERED, CANCELLED).contains(target);
      case RUNNING ->
          Set.of(SUCCEEDED, FAILED, RETRY_WAIT, DEAD_LETTERED, CANCELLED).contains(target);
      case FAILED -> Set.of(RETRY_WAIT, DEAD_LETTERED).contains(target);
      case DEAD_LETTERED -> target == READY;
      default -> false;
    };
  }

  public void requireTransitionTo(TaskStatus target) {
    if (!canTransitionTo(target))
      throw new IllegalStateException("Illegal task transition: " + this + " -> " + target);
  }

  public boolean terminal() {
    return Set.of(SUCCEEDED, FAILED, DEAD_LETTERED, CANCELLED).contains(this);
  }

  public void requireDependencyRetry(TaskStatus target, String category) {
    if (this != CANCELLED || target != BLOCKED || !"UPSTREAM_FAILED".equals(category))
      throw new IllegalStateException("Cannot reactivate this cancelled task");
  }
}
