package io.durableflow.model;

public final class FlowException extends RuntimeException {
  private final int status;
  private final String code;

  public FlowException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static FlowException invalid(String message) {
    return new FlowException(400, "invalid-request", message);
  }

  public static FlowException missing() {
    return new FlowException(404, "not-found", "The requested resource does not exist");
  }

  public static FlowException conflict(String message) {
    return new FlowException(409, "conflict", message);
  }
}
