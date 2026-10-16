# Benchmarks

Observed local results from `benchmark/raw/2026-10-16T07-59-03.144012+00-00-3d3cea`. Raw task timestamps, per-task timings, resource samples, image IDs and runtime/machine metadata are stored separately from this summary. These are exploratory single-run measurements, not production capacity claims.

## Reproduce

```sh
python scripts/init-env.py
docker compose build
python -m unittest discover -s benchmark -p 'test_*.py'
python benchmark/run.py --samples 1000 --concurrency 16 --payload-bytes 256 --warmup-seconds 5.0
```

Requires Python 3, Docker Desktop or another Docker Engine using Linux containers, and Compose 2.24.4+. Java 21 and Maven are supplied by the application's Maven builder image; the Windows fault-test runner also has a Docker-based Maven fallback. Every run uses a unique disposable Compose project and dynamically selected loopback port, starts a fresh database for every worker count, and removes only its own volumes. Do not put real data in an experiment project. Successful, failed and interrupted raw runs remain in unique directories. BENCHMARKS.md is generated only after all four profiles and the recovery probe succeed.

## Environment and workload

- Host: Windows (reported release 10), AMD64, AMD64 Family 23 Model 113 Stepping 0, AuthenticAMD; 12 logical CPUs and 17101787136 bytes RAM.
- Docker Engine: 29.8.2, linux; Compose 5.6.0; 12 CPUs and 8291274752 bytes available to Docker. Other local containers running: 0.
- Application runtime: openjdk 21.0.12.1 2026-08-18 LTS; Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5); Spring Boot 4.1.1; project 0.1.0-SNAPSHOT; PostgreSQL 17.6. Commit, source fingerprint, image references/IDs and each container's memory limit are recorded per profile in the raw summary.
- 1000 single-task workflows per count; fixed client concurrency 16; delay handler with zero requested work and 256 padding bytes. Exact JSON input size is recorded per profile.
- Warm-up dispatch lasts at least 5.0 seconds, then all warm-up workflows drain before timed submission. The same fixed thread pool is reused for warm-up dispatch and drain so its per-thread HTTP connections persist between batches. The regression test verifies connection reuse across dispatch calls.
- Database contents and JVM instances start fresh for every profile. Java application containers have 384 MiB memory limits; PostgreSQL has no Compose memory limit. Other containers follow the Compose limits. The benchmark's CPU/memory samples and PostgreSQL connection samples are stored separately.
- Tracing, metrics, audit/security filters and normal scheduling are enabled. Diagnostic polling adds measurable load. Host workload and the idle base demo were not disabled; resource samples disclose observed database CPU/connections.

## Results

| Workers | Workflow submissions/s | Task completions/s | Scheduling p50 / p95 / p99 (ms) | Execution overhead p50 / p95 / p99 (ms) |
| --- | --- | --- | --- | --- |
| 1 | 914.08 | 62.88 | 8377.23 / 14496.74 / 14864.95 | 3.70 / 6.98 / 10.36 |
| 2 | 876.42 | 82.98 | 6160.31 / 10596.56 / 10852.92 | 4.64 / 10.92 / 15.26 |
| 4 | 703.23 | 120.74 | 3723.96 / 6704.92 / 6861.51 | 5.79 / 13.74 / 19.44 |
| 8 | 470.59 | 93.96 | 2174.69 / 8174.17 / 8537.82 | 8.56 / 41.48 / 342.49 |

| Workers | Warm-up workflows | Warm-up dispatch + drain (s) | Retried tasks (rate) | Additional started attempts | Dead letters (rate) | Max DB connections* | Max sampled PostgreSQL CPU |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2720 | 95.09 | 0 (0.00%) | 0 | 0 (0.00%) | 16 | 193.76% |
| 2 | 2240 | 72.11 | 0 (0.00%) | 0 | 0 (0.00%) | 21 | 185.06% |
| 4 | 1696 | 51.27 | 0 (0.00%) | 0 | 0 (0.00%) | 31 | 237.16% |
| 8 | 1040 | 29.01 | 0 (0.00%) | 0 | 0 (0.00%) | 51 | 1392.77% |

Percentiles use nearest rank over every successful measured task. Scheduling is database `started_at - available_at`. Execution overhead is database `completed_at - started_at` for the zero-delay handler: it includes handler dispatch, transactions, serialization and result persistence, plus the negligible zero-delay handler itself. Completion throughput uses the first measured task start through the last task completion. Submission throughput uses the first request dispatch through the last accepted response. End-to-end throughput (including scheduler terminal observation) is also in raw summaries. These denominators differ deliberately and must not be compared as identical rates.

Across all profiles there were 0 additional claim attempts, 0 additional started attempts, 0 retried tasks and 0 dead letters. A profile with any failed task cannot pass; tasks are not omitted from percentiles. *The connection count includes the benchmark's own sampling query. CPU is the maximum observed Docker stats sample, not a time-weighted average. Resource data contains sampled CPU/memory for all containers.

Docker Desktop reported a sampled PostgreSQL peak of 1392.77%, above the nominal 1200% scale for its 12 CPUs. Preserve this raw observation, but treat the peak as anomalous rather than a reliable capacity measure.

## Recovery measurement

With three workers, the runner killed the owner of a five-second delay task. The workflow succeeded on attempt 2; lease recovery was observed after 9.594 seconds and injection-to-terminal completion took **14.657 seconds**. This includes lease expiry, backoff, rerunning the five-second handler and polling. It is not a universal failover SLA. The raw recovery record retains both attempts and worker IDs.

## Warm-up drain investigation

The prior socket-allocation failure and interrupted run are retained under `benchmark/raw/`; neither is treated as a successful measurement. Inspection found that dispatch created a new executor for every warm-up batch, so thread-local keep-alive connections could not be reused across batches. Dispatch and drain now share one fixed executor, and the client explicitly closes all thread-owned connections after each profile. The focused regression test passed. This complete run observed no submission errors. Warm-up still produced 1040 to 2720 submissions and took 29.01 to 95.09 seconds including drain; warm-up work is fully drained before measurement and can therefore be a significant part of setup time.

## Interpretation and limits

The engine uses short PostgreSQL transactions and one execution slot per worker. Completion throughput rose through four workers but fell at eight; eight workers also had higher scheduling and execution p99 values than four. Additional workers increase database contention as well as parallelism; horizontal scaling is not linear in this sample. PostgreSQL's reported maximum connection count rose with worker count. Polling, JSON persistence, telemetry and status queries are part of this workload, not removed to improve the numbers. Consider the tail latencies, connection counts and CPU samples before attributing scaling behavior to worker capacity alone.

This is one run, does not isolate bottlenecks, estimate confidence intervals or extrapolate to other machines. Repeat it on an otherwise idle host before making capacity decisions. Retained unsuccessful/anomalous runs remain under `benchmark/raw/`; never average them away silently. The database is single-node, payloads are small, handlers are safe and near-zero work, and no external side-effect service is benchmarked.
