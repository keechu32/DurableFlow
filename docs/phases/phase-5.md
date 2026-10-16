# Phase 5 report: fault recovery and exploratory benchmarks

## Outcome

Phase 5 is complete. All nine executable fault scenarios passed on Windows, and a complete 1/2/4/8-worker benchmark plus a killed-worker recovery probe completed successfully. The benchmark is one exploratory local run, not a production capacity claim. Failed and interrupted runs remain preserved.

## Fault evidence

The successful run is [`results.json`](../../fault-tests/results/2026-10-16T07-52-45.126264+00-00-eee973/results.json). Each scenario records the initial state, injected failure, expected invariant, observed result, elapsed time and pass/fail. The preceding Windows harness attempt, including its `NameError`, remains in [`results.json`](../../fault-tests/results/2026-10-16T07-50-14.063048+00-00-145825/results.json); it is not merged into or hidden by the successful result.

| Scenario | Initial conditions | Injected failure | Expected invariant | Observed result | Pass |
| --- | --- | --- | --- | --- | --- |
| Worker kill | Delay task RUNNING with a valid lease | SIGKILL its owning worker | A new attempt succeeds; only one attempt has current ownership | Attempt 2 succeeded after recovery; former owner expired | Yes |
| Scheduler kill | Two schedulers compete for one advisory lock | SIGKILL elected scheduler | Standby acquires leadership and unblocks dependent work | Dependent workflow succeeded after failover | Yes |
| PostgreSQL restart | Delay task RUNNING | Restart isolated PostgreSQL container | Durable workflow completes after connectivity returns | Workflow succeeded on attempt 1 | Yes |
| Worker network isolation | Worker owns a RUNNING delay task | Disconnect lease owner from experiment network, then reconnect | Healthy worker recovers lease; reconnection is safe | Attempt 2 succeeded; prior attempt expired | Yes |
| Slow stale completion | Worker owns a RUNNING delay task | Pause owner past lease expiry until replacement completes | Expired owner cannot overwrite recovered result | Replacement succeeded; old completion was rejected as STALE | Yes |
| Duplicate submission | One immutable task definition exists | Submit 32 equivalent requests concurrently | Exactly one durable execution and task set exists | 32 requests, 16-way concurrency, one execution and one task | Yes |
| Duplicate completion | Successful attempt committed | Send 16 duplicate completion reports through persistence protocol | Equivalent reports are idempotent; conflicting output cannot mutate state | Equivalent reports accepted, conflicting output rejected, task version unchanged | Yes |
| Retry exhaustion | Safe delay exceeds its one-second timeout | Allow two attempts to exhaust | Workflow fails and task is dead-lettered at attempt 2 | Workflow `FAILED`; task `DEAD_LETTERED` at attempt 2 | Yes |
| Graceful scaling | Twelve safe delay workflows submitted | Scale workers 3 → 1 → 4 → 3 | Every workflow succeeds; abandoned claims recover | All 12 succeeded; max attempt 1 | Yes |

## Benchmark method and environment

The complete raw run is [`summary.json`](../../benchmark/raw/2026-10-16T07-59-03.144012+00-00-3d3cea/summary.json); per-profile task timestamps, timings, resource samples, metadata and image IDs are in sibling files. The run used 1,000 single-task workflows per worker count, fixed request concurrency 16, 256 bytes of payload padding, and a minimum five-second warm-up followed by complete drain. Each worker profile started a fresh database and application stack. A separate three-worker probe killed the owner of a five-second task.

| Environment item | Observed value |
| --- | --- |
| Host | Windows 10, AMD64; AMD Ryzen-family CPU; 12 logical CPUs; 17,101,787,136 bytes RAM |
| Docker | Docker Desktop Engine 29.8.2, Linux containers; 12 CPUs; 8,291,274,752 bytes available; no other local containers running |
| Compose | 5.6.0 |
| Runtime/build | Temurin Java 21.0.12.1 LTS; Maven 3.9.16 (repository builder image) |
| Project | DurableFlow `0.1.0-SNAPSHOT`; Spring Boot 4.1.1 |
| Database | PostgreSQL 17.6 |
| Application container memory | API, scheduler and worker each 402,653,184 bytes; PostgreSQL had no Compose memory limit |
| Image identity | Image references and immutable image IDs are recorded per profile in the raw summary |
| Source identity | Checkpoint `018d8704dd61b6292c5b85cb7424a30d38d26567`; benchmark metadata records dirty-tree state and engine source SHA-256 |

## Measured results

All measured tasks succeeded. The four profiles recorded zero retries, zero additional started attempts and zero dead letters. The published [`BENCHMARKS.md`](../../BENCHMARKS.md) contains exact per-profile values and explains throughput denominators.

| Workers | Submissions/s | Completions/s | Scheduling p50 / p95 / p99 (ms) | Execution overhead p50 / p95 / p99 (ms) | Max DB connections |
| ---: | ---: | ---: | --- | --- | ---: |
| 1 | 914.08 | 62.88 | 8,377.23 / 14,496.74 / 14,864.95 | 3.70 / 6.98 / 10.36 | 16 |
| 2 | 876.42 | 82.98 | 6,160.31 / 10,596.56 / 10,852.92 | 4.64 / 10.92 / 15.26 | 21 |
| 4 | 703.23 | 120.74 | 3,723.96 / 6,704.92 / 6,861.51 | 5.79 / 13.74 / 19.44 | 31 |
| 8 | 470.59 | 93.96 | 2,174.69 / 8,174.17 / 8,537.82 | 8.56 / 41.48 / 342.49 | 51 |

Maximum sampled PostgreSQL CPU was 193.76%, 185.06%, 237.16%, and 1,392.77% at 1, 2, 4, and 8 workers respectively. The eight-worker sample exceeds the nominal 1,200% scale for 12 Docker CPUs and is treated as anomalous, not reliable capacity data. CPU is a maximum sampled Docker-stat value, not a time-weighted measure. Database connection counts include the sampling query.

At four workers, observed completion throughput was highest in this run; at eight it fell, and p99 execution overhead rose. This is not a scaling guarantee or capacity recommendation: it is one noisy run with status-polling/diagnostic overhead, a local host, a single PostgreSQL node and near-zero-work handlers.

The worker-kill probe succeeded on attempt 2. Lease recovery was observed after 9.594 seconds; injection-to-terminal completion took 14.657 seconds, including lease expiry, backoff, five-second re-execution and polling. This is an observation, not a deployment recovery SLA.

## Warm-up drain investigation

An earlier run failed during warm-up drain with a socket allocation error and a later run was interrupted during warm-up drain; both artifacts remain under `benchmark/raw/`. Inspection found that each warm-up dispatch batch created a new executor, preventing the same threads' keep-alive HTTP connections from surviving between batches. The runner now reuses one fixed pool through dispatch and drain, and explicitly closes registered client connections after each profile. A regression test verifies connection reuse across dispatch batches; the complete run observed no submission errors.

Warm-up was fully drained before measurement, but still generated 1,040–2,720 workflows per profile and took 29.01–95.09 seconds including drain. This is meaningful setup cost and remains a limitation.

## Validation performed

- Docker Compose built the API, scheduler and worker images using Java 21.
- Docker-based `mvn -B -ntp clean verify` passed all 49 Java tests, formatting and SpotBugs, including Testcontainers.
- Six Python benchmark tests passed; Python syntax checks and base/experimental Compose configuration validation passed.
- All nine fault scenarios passed in the successful Windows run.
- All four benchmark profiles and the worker-recovery probe passed.
- Experimental Compose projects and their volumes were cleaned by the harness; the ordinary demonstration stack was not targeted.

See the Phase 5 section in [`VERIFICATION.md`](../VERIFICATION.md) for the recorded checks.

## Limitations

The benchmark is a single exploratory run with no confidence intervals, causal bottleneck isolation, external side-effect service, multi-node database, realistic handler work or production traffic. It does not establish an SLO, capacity limit, or production readiness. Preserve and review failed, interrupted and anomalous observations rather than silently averaging them away.
