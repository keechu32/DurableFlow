package io.durableflow.model;

public enum ErrorCategory {
  INVALID_INPUT(false),
  HANDLER_ERROR(true),
  TIMEOUT(true),
  LEASE_EXPIRED(true);
  private final boolean retryable;

  ErrorCategory(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
