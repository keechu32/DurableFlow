# Reproducible local faults

From the repository root, after generating development configuration:

```sh
python scripts/init-env.py
docker compose build
python fault-tests/run.py
```

Requires Docker Desktop/Linux containers or another Linux-container engine, Compose 2.24.4+ and Python 3. The duplicate-completion persistence test uses host Maven/JDK 21 when available; otherwise on Windows it runs in the repository's Maven 3.9.16/Java 21 Docker image with the Docker socket mounted for Testcontainers. Each run creates a unique disposable Compose project, database volume and loopback port, with two schedulers and three workers. The ordinary demo project and its data are untouched. The harness removes only its uniquely named experiment project and volumes after recording outcomes; never store real data in an experiment project. `--keep-stack` keeps it for inspection.

Each run creates a unique UTC directory under `fault-tests/results/`. Every scenario records initial state, injected failure, expected invariant, actual result and pass/fail, with elapsed time. The report also records host/engine resources, Docker/Compose details, Java/PostgreSQL/project versions and image IDs. Failed and interrupted runs are retained with explicit status. No scenario is marked passed until its executable assertions succeed.

| Scenario | Mechanism | Assertion |
| --- | --- | --- |
| Worker termination | Find the lease owner through worker metadata; SIGKILL that container | A different attempt completes; no two current ownership records |
| Scheduler leader termination | Find advisory-lock session's container; SIGKILL it | Standby owns the lock and a dependent task completes |
| PostgreSQL restart | Restart only experiment database during a running task | State survives and progress resumes |
| Worker database isolation | Disconnect owning worker's Docker network, then reconnect | Other workers recover its lease; reconnect does not corrupt state |
| Slow stale completion | Pause owning worker until a replacement completes, then unpause | Old attempt reports STALE/ABANDONED and cannot change task version |
| Concurrent duplicate submission | 32 requests with concurrency 16 and one key | One execution and one task set |
| Concurrent duplicate completion | Run actual worker persistence-protocol integration test with 16 reports | Equivalent results accepted, conflicting output rejected, version unchanged |
| Retry exhaustion | Five-second safe delay with a one-second timeout, two attempts | Task DEAD_LETTERED, workflow FAILED, attempt two |
| Graceful scaling | Resize workers 3 → 1 → 4 → 3 while work exists | Every workflow succeeds; any abandoned claim is recovered |

Workers use the database persistence protocol directly; result reporting is not a public HTTP endpoint. The duplicate-completion scenario invokes `RecoveryIT.duplicateSuccessIsIdempotentEvenAfterWorkflowCompletion` against a Testcontainers database and records its exit status. Set `DURABLEFLOW_MAVEN_REPO` only if using a nonstandard local Maven cache.

Waits poll persisted state, Docker health or actual stale-result logs with deadlines. The three-second fault-test lease is an explicit override; runtime defaults remain ten seconds. Delay is a bounded built-in handler, not injected shell/script execution. Recovery time includes expiry, backoff, re-execution and observation; it is not a universal failover SLA.
