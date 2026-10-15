package io.durableflow.worker;

import io.durableflow.model.Lease;
import io.durableflow.persistence.ExecutionStore;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "durableflow.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class WorkerRuntime implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(WorkerRuntime.class);
  private final ExecutionStore store;
  private final Handlers handlers;
  private final UUID id = UUID.randomUUID();
  private final int leaseSeconds;
  private final long pollMillis;
  private final Object activeGuard = new Object();
  private volatile boolean accepting;
  private volatile Lease active;
  private volatile Thread worker;
  private ScheduledExecutorService heartbeat;

  public WorkerRuntime(
      ExecutionStore store,
      Handlers handlers,
      @Value("${durableflow.lease-seconds:10}") int leaseSeconds,
      @Value("${durableflow.poll-millis:100}") long pollMillis) {
    if (leaseSeconds < 2 || leaseSeconds > 300 || pollMillis < 10 || pollMillis > 10000)
      throw new IllegalArgumentException("Invalid worker timing");
    this.store = store;
    this.handlers = handlers;
    this.leaseSeconds = leaseSeconds;
    this.pollMillis = pollMillis;
  }

  @Override
  public synchronized void start() {
    if (accepting) return;
    store.register(id, handlers.types());
    accepting = true;
    worker = new Thread(this::loop, "durableflow-worker");
    heartbeat = Executors.newSingleThreadScheduledExecutor();
    heartbeat.scheduleWithFixedDelay(this::beat, 1, 1, TimeUnit.SECONDS);
    worker.start();
  }

  private void beat() {
    try {
      store.workerHeartbeat(id);
      var lease = active;
      if (lease != null && !store.heartbeat(lease, leaseSeconds)) {
        synchronized (activeGuard) {
          if (active == lease) worker.interrupt();
        }
      }
    } catch (RuntimeException ex) {
      synchronized (activeGuard) {
        if (active != null) worker.interrupt();
      }
      LOG.warn("Worker heartbeat unavailable");
    }
  }

  private void loop() {
    try {
      while (accepting) {
        Thread.interrupted();
        try {
          var claimed = store.claim(id, handlers.types(), leaseSeconds);
          if (claimed.isEmpty()) {
            Thread.sleep(pollMillis);
            continue;
          }
          var lease = claimed.get();
          if (!accepting || !store.start(lease)) continue;
          synchronized (activeGuard) {
            active = lease;
          }
          try {
            var output = handlers.execute(lease);
            boolean committed = store.succeed(lease, output);
            log(lease, committed ? "SUCCEEDED" : "STALE");
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log(lease, "ABANDONED");
          } catch (IllegalArgumentException ex) {
            store.fail(lease, "INVALID_INPUT");
            log(lease, "FAILED");
          } catch (RuntimeException ex) {
            store.fail(lease, "HANDLER_ERROR");
            log(lease, "FAILED");
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
          LOG.warn("Worker persistence operation unavailable");
          try {
            Thread.sleep(pollMillis);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        } finally {
          synchronized (activeGuard) {
            active = null;
            Thread.interrupted();
          }
        }
      }
    } finally {
      try {
        store.workerStatus(id, "OFFLINE");
      } catch (RuntimeException ex) {
        LOG.warn("Could not record worker shutdown");
      }
    }
  }

  private void log(Lease lease, String status) {
    LOG.atInfo()
        .addKeyValue("workflow_id", lease.workflowId())
        .addKeyValue("task_id", lease.id())
        .addKeyValue("task_definition_id", lease.taskDefinitionId())
        .addKeyValue("attempt", lease.attempt())
        .addKeyValue("worker_id", id)
        .addKeyValue("status", status)
        .log("Task execution finished");
  }

  @Override
  public synchronized void stop() {
    if (!accepting) return;
    accepting = false;
    try {
      store.workerStatus(id, "DRAINING");
    } catch (RuntimeException ex) {
      LOG.warn("Could not record draining status");
    }
    try {
      if (!worker.join(java.time.Duration.ofSeconds(10))) {
        worker.interrupt();
        worker.join(1000);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      worker.interrupt();
    }
    heartbeat.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return accepting;
  }

  public UUID workerId() {
    return id;
  }
}
