package io.durableflow.model;

import java.util.Set;

public enum WorkflowStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLING,
  CANCELLED;

  public boolean canTransitionTo(WorkflowStatus target) {
    return switch (this) {
      case PENDING -> Set.of(RUNNING, CANCELLING).contains(target);
      case RUNNING -> Set.of(SUCCEEDED, FAILED, CANCELLING).contains(target);
      case CANCELLING -> target == CANCELLED;
      default -> false;
    };
  }

  public void requireTransitionTo(WorkflowStatus target) {
    if (!canTransitionTo(target))
      throw new IllegalStateException("Illegal workflow transition: " + this + " -> " + target);
  }

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}
