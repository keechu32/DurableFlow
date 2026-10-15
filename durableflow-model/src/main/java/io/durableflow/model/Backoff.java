package io.durableflow.model;

public final class Backoff {
  private Backoff() {}

  /** 100ms exponential base, 20% bounded jitter, hard ceiling 30 seconds. */
  public static long milliseconds(int attempt, double random) {
    if (attempt < 1 || !Double.isFinite(random) || random < 0 || random > 1)
      throw new IllegalArgumentException("Invalid backoff input");
    long base = Math.min(30000L, 100L << Math.min(20, attempt - 1));
    return Math.min(30000L, Math.max(1L, Math.round(base * (0.8 + 0.4 * random))));
  }
}
