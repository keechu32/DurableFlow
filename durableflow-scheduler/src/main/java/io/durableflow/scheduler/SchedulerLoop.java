package io.durableflow.scheduler;

import io.durableflow.persistence.ExecutionStore;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "durableflow.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class SchedulerLoop {
  private final ExecutionStore store;
  private final Leader leader;

  public SchedulerLoop(ExecutionStore store, Leader leader) {
    this.leader = leader;
    this.store = store;
  }

  @Scheduled(fixedDelayString = "${durableflow.scheduler.poll-millis:100}")
  public void tick() {
    try {
      if (leader.elected()) {
        store.recover();
        store.reconcile();
      }
    } catch (RuntimeException ex) {
      LoggerFactory.getLogger(SchedulerLoop.class)
          .warn("Scheduler persistence operation unavailable");
    }
  }
}
