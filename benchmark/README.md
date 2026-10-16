# Benchmark tooling

```sh
python scripts/init-env.py
docker compose build
python -m unittest discover -s benchmark -p 'test_*.py'
python benchmark/run.py --samples 1000 --concurrency 16 --payload-bytes 256 --warmup-seconds 5
```

Uses a unique disposable Compose project, dynamically selected loopback port and fresh database/application instances for every 1/2/4/8-worker configuration. Cleanup removes only the run's uniquely named project and volumes; no ordinary demo data is removed. Requires Docker Desktop/Linux containers or another Linux-container engine, Compose 2.24.4+, and Python 3. On Windows, the duplicate-completion Maven test runs in the repository's Maven 3.9.16/Java 21 Docker image through the Docker socket; a host JDK/Maven installation is not required. The input includes an integer zero-delay plus exactly the requested number of ASCII padding bytes; the complete JSON byte count is recorded. Task execution is sequential within each worker, with fixed request concurrency across configurations.

Raw data lives under unique UTC `raw/` directories: runtime/machine metadata and source/image fingerprints; every warm-up and measured submission response/error; current task timestamps/statuses; calculated per-task latencies; Docker CPU/memory and PostgreSQL connection samples; retry/duplicate/dead-letter counts; and a killed-worker recovery probe. Tokens and payload contents are not written to results. Failures and Ctrl+C interruptions retain their artifacts and exit nonzero with explicit terminal status. Warm-up dispatch and drain share a fixed thread pool so the same per-thread HTTP keep-alive connections survive across batches; the runner closes every registered connection when the profile completes. The summary is separate; BENCHMARKS.md is created only after all profiles and the recovery probe succeed.

Scheduling latency uses database-clock RUNNING minus available-at. Execution overhead uses completed minus RUNNING for the zero-delay handler, including dispatch, transactions, serialization and persistence. The summary states throughput denominators explicitly and uses nearest-rank p50/p95/p99 over all successful measured tasks. Retries are reported; timestamps refer to the final successful attempt and attempt history is consulted for additional started executions. The three basic statistics tests plus a failed-response artifact test protect the measurement code.

The warm-up dispatch period is followed by full drain, so warm-up tasks never inflate measured completions. Status polling and diagnostics impose real overhead and are held consistent. Source fingerprints and dirty-tree status describe the actual measured engine rather than assuming a release tag. Six Python tests cover summary statistics, connection reuse and failure-artifact preservation. Exploratory single runs cannot establish confidence intervals or linear scalability; repeat on an idle machine for capacity work.
