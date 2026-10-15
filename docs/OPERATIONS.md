# Operations

## Local lifecycle

Generate configuration with `python3 scripts/init-env.py`, then `docker compose up --build --wait --wait-timeout 240`. Java application containers have 384 MiB memory limits and heap sizing appropriate to that limit. Compose starts three workers; use `docker compose up -d --scale worker=N` to change the count. A graceful stop waits ten seconds for task execution and then abandons the lease for automatic recovery. Inspect `docker compose ps` and `docker compose logs worker scheduler api`.

PostgreSQL is the source of truth. The named data volume survives `docker compose down`; `down --volumes` is destructive and only appropriate for disposable development data. Backups and production database availability are not provided. Use new versioned migrations; never clean/baseline or edit applied ones. PostgreSQL 17 is required for tested locking semantics.

## Metrics

Prometheus UI: http://localhost:9090. API metrics require the operator token; Compose mounts only that token into Prometheus as a file. Scheduler and worker targets use Docker DNS discovery, so replica changes are discovered without editing target lists. Import `ops/dashboards/durableflow.json` into an existing Grafana instance with a Prometheus data source if desired; no Grafana service/default password is installed.

| Metric | Meaning |
| --- | --- |
| `durableflow_workflows_submitted_total` | New durable submissions, excluding idempotent replays |
| `durableflow_workflows_completed_total{status}` | Committed workflow terminal transitions (manual reopen can add a later terminal transition) |
| `durableflow_tasks_claimed_total` | Committed claims |
| `durableflow_tasks_completed_total{status}` | Committed attempt outcomes, including RETRY_WAIT and DEAD_LETTERED |
| `durableflow_active_leases` | Globally valid leased/running task count |
| `durableflow_lease_expirations_total{status}` | Expired leases processed by recovery, categorized by cause |
| `durableflow_retry_attempts_total` | Claims after the first attempt, including manual retry |
| `durableflow_dead_lettered_total` | Entries into dead-letter state |
| `durableflow_scheduling_latency_seconds` | Available-at to RUNNING, histogram |
| `durableflow_execution_duration_seconds` | Worker handler/result duration, histogram |
| `durableflow_queue_depth{status}` | Global persisted task count by state; filter READY/BLOCKED/RETRY_WAIT for pending work |
| `durableflow_worker_heartbeat_age_seconds` | Maximum age among ONLINE workers |

Counters are process-local and reset on process restart; Prometheus retains observed samples. Database gauges are global and exposed by every process: aggregate them with `max`, **not sum**. Transition counters run only after commit; rolled-back actions do not increment them. Timers represent observed attempts, including abandoned/failed ones. Metrics contain no workflow/task/worker IDs as labels, preventing unbounded cardinality.

## Tracing and logs

OpenTelemetry spans accept and propagate W3C `traceparent`. Submission persists that context; worker execution and scheduler completion reuse the trace ID across processes. Span/log fields whitelist workflow/task/attempt/worker identifiers. ECS JSON includes `trace_id` and `span_id`. Payloads and credentials are excluded.

Compose enables OTLP gRPC export to `collector:4317`; standalone/test contexts default to no export. The collector has bounded memory and a private named trace volume, rotates JSON trace files at 10 MiB, and keeps two backups. Telemetry is best effort and never controls durable task progress. To inspect a trace without an external service:

```sh
docker cp "$(docker compose ps -q collector)":/var/lib/otel/durableflow-traces.json /tmp/durableflow-traces.json
python3 scripts/inspect-traces.py /tmp/durableflow-traces.json
```

Trace files survive container recreation in the named volume and are removed by `down --volumes`. A one-shot initializer sets the volume owner; the collector runs as UID 10001. Logs/traces contain identifiers even though task data is omitted; protect them accordingly. For longer retention, configure an authenticated OTLP backend outside this local demo.

## Dead letters and audit

Query dead letters as operator. Retry and cancellation require the admin token. A manual retry reuses the immutable task input and stable effect key, grants another configured attempt budget and retains monotonic history. Inspect `GET /api/v1/task-executions/{id}/attempts?limit=50&offset=0`. Audit history is available to admin at `/api/v1/audit-events`.

Watch growing READY depth (unsupported capability or insufficient workers), old online heartbeats (stuck worker), lease expirations (pause/network/timeout), dead letters (permanent/exhausted work), and scheduling latency. Database failure makes readiness fail and stops progress while liveness remains independent. The elected scheduler resumes recovery after database availability returns. Do not use health alone as evidence of useful task progress.
