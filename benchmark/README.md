# Benchmark tooling

```sh
python3 scripts/init-env.py
docker compose build
python3 -m unittest discover -s benchmark -p 'test_*.py'
python3 benchmark/run.py --samples 1000 --concurrency 16 --payload-bytes 256 --warmup-seconds 5
```

Uses the disposable `durableflow-benchmark` Compose project on port 18080, with a fresh database and new application instances for every 1/2/4/8-worker configuration. No ordinary demo volume is deleted. Requires Docker/Compose 2.24.4+, Python 3, JDK 21 and Maven. The input includes an integer zero-delay plus exactly the requested number of ASCII padding bytes; the complete JSON byte count is recorded. Task execution is sequential within each worker, with fixed request concurrency across configurations.

Raw data lives under unique UTC `raw/` directories: runtime/machine metadata and source/image fingerprints; every warm-up and measured submission response/error; current task timestamps/statuses; calculated per-task latencies; Docker CPU/memory and PostgreSQL connection samples; retry/duplicate/dead-letter counts; and a killed-worker recovery probe. Tokens and payload contents are not written to results. Failures retain their artifacts and exit nonzero. The summary is separate; BENCHMARKS.md is created only after all profiles and the recovery probe succeed.

Scheduling latency uses database-clock RUNNING minus available-at. Execution overhead uses completed minus RUNNING for the zero-delay handler, including dispatch, transactions, serialization and persistence. The summary states throughput denominators explicitly and uses nearest-rank p50/p95/p99 over all successful measured tasks. Retries are reported; timestamps refer to the final successful attempt and attempt history is consulted for additional started executions. The three basic statistics tests plus a failed-response artifact test protect the measurement code.

The warm-up dispatch period is followed by full drain, so warm-up tasks never inflate measured completions. Status polling and diagnostics impose real overhead and are held consistent. Source fingerprints and dirty-tree status describe the actual measured engine rather than assuming a release tag. Exploratory single runs cannot establish confidence intervals or linear scalability; repeat on an idle machine for capacity work.
