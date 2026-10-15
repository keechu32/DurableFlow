# Reproducible local faults

From the repository root, after generating development configuration:

```sh
python3 scripts/init-env.py
docker compose build
python3 fault-tests/run.py
```

Requires JDK 21, Maven, Docker/Compose 2.24.4+ and Python 3. It creates only the **disposable `durableflow-faults` project**, with a private PostgreSQL volume, two schedulers and three workers. Port 18080 must be free; set `DURABLEFLOW_EXPERIMENT_PORT` to select another. The ordinary demo project is untouched. The harness removes its own experiment volumes after recording outcomes; never store real data in an experiment project. `--keep-stack` keeps it for inspection.

Each run creates a unique UTC directory under `fault-tests/results/`. Every scenario records initial state, injected failure, expected invariant, actual result and pass/fail, with elapsed time. Failed attempts are retained. No scenario is marked passed until its executable assertions succeed.

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
