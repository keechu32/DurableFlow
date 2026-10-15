package io.durableflow.persistence;

import org.springframework.transaction.support.*;

public final class AfterCommit {
  private AfterCommit() {}

  public static void run(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("A transaction is required");
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }
}
