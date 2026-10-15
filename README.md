# DurableFlow

A small PostgreSQL-backed durable DAG workflow engine, built to make distributed execution and its failure modes understandable and testable.

**Status: Phase 4 operations and security.** Durable DAG execution and recovery now include role-based API authentication, audit, strict input limits, OpenTelemetry traces and Prometheus metrics. Phase 5 tooling is in progress and paused at the owner’s request; nine fault scenarios passed, but no measured benchmark profile completed. See [resume notes](docs/WORK_STATUS.md). No performance claims are made.

Repository: `git@github.com:keechu32/DurableFlow.git`.

## Local startup

Prerequisites: Docker Engine/Desktop with Compose v2 supporting `up --wait`, about 4 GB available memory, and ports 5432, 8080–8081 and 9090 free. Container builds supply Java 21 and Maven. For host builds, install JDK 21 and Maven 3.9.9 or newer (3.x).

Create local configuration (never commit `.env`):

```sh
python3 scripts/init-env.py
docker compose up --build --wait --wait-timeout 240
curl --fail http://localhost:8080/actuator/health
bash scripts/smoke-test.sh
```

The smoke script also requires Python 3 and curl on the host. API: localhost:8080; scheduler health: localhost:8081; worker health: internal port 8080. PostgreSQL listens on localhost:5432. All published ports bind to loopback. Credentials come from your ignored `.env`. Changing database credentials after the volume is initialized does not change existing PostgreSQL accounts.

```sh
docker compose down                 # keep database data
# Destructive, optional clean reset: docker compose down --volumes
```

Health endpoints: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`. Readiness includes database connectivity; liveness represents process health. Migration failure prevents startup. Health details are hidden and sensitive actuator endpoints are not exposed. OpenAPI: `/v3/api-docs`; interactive documentation: `/swagger-ui.html`.

## Build and verification

```sh
mvn -B -ntp verify                 # formatting + unit tests + SpotBugs + Testcontainers
mvn -B -ntp spotless:apply         # apply Java formatting
```

Docker must be running for integration tests; they fail rather than silently skip when it is absent. CI uses ephemeral Testcontainers PostgreSQL, then builds and starts the complete Compose stack and probes every process. No manually provisioned database is needed.

For an explicitly incomplete host-only check when Docker is unavailable:

```sh
mvn -B -ntp -DskipITs verify
```

This still runs the database-unavailable startup test but does not prove successful PostgreSQL startup. See [verification evidence](docs/VERIFICATION.md) for commands actually run and limitations.

## Layout and design

| Module | Responsibility |
| --- | --- |
| `durableflow-model` | Dependency-free state machines and DAG validation |
| `durableflow-observability` | Shared trace SDK and bounded metrics |
| `durableflow-persistence` | Shared JDBC configuration and immutable Flyway migrations |
| `durableflow-api` | Public control-plane application |
| `durableflow-scheduler` | Coordination application |
| `durableflow-worker` | Execution application |
| `durableflow-integration-tests` | Cross-process foundation and PostgreSQL checks |

See [architecture](ARCHITECTURE.md), [ADRs](docs/adr/README.md), [phase checklist](docs/PHASE_CHECKLIST.md), and [contribution guide](CONTRIBUTING.md).

## Delivery semantics and roadmap

The target contract is **at least once**: a task may run again after a crash or lease loss. Side-effecting handlers must use a stable task idempotency key with the destination system. PostgreSQL transactions cannot make arbitrary external effects exactly once.

The remaining phases add atomic claims and safe handlers, recovery and retries, telemetry and security, fault tests and measurements, then release preparation. The owner has authorized completing the remaining phases. The API now requires operator/admin bearer tokens; cancellation and manual retry require admin. Use this development deployment locally only.

## Troubleshooting

- Docker unavailable: install/start a compatible container runtime before `verify` or Compose startup.
- Port occupied: stop the conflicting service or change the host port in Compose and the smoke script.
- Database authentication fails: use the credentials that initialized the volume; reset it only if its data is disposable.
- Flyway validation fails: do not edit applied migrations; add a new versioned migration.
- Maven download fails: check access to Maven Central and your proxy configuration; do not disable TLS verification.
- An application is unhealthy: run `docker compose ps` and `docker compose logs api scheduler worker postgres`. Startup may take longer on the first image build.

## Control-plane example

```sh
set -a; . ./.env; set +a  # load the generated local development configuration
curl --fail -H "Authorization: Bearer $DURABLEFLOW_OPERATOR_TOKEN" -H 'Content-Type: application/json' --data-binary @durableflow-examples/diamond.json http://localhost:8080/api/v1/workflow-definitions
# Use definition_id from the response and version 1:
curl --fail -H "Authorization: Bearer $DURABLEFLOW_OPERATOR_TOKEN" -H 'Content-Type: application/json' -d '{"definitionId":"REPLACE_WITH_DEFINITION_UUID","version":1,"idempotencyKey":"example-1","input":{}}' http://localhost:8080/api/v1/workflow-executions
```

Definitions with the same name publish a new immutable version. Repeating an equivalent submission reuses the execution; conflicting key reuse returns 409. Query `/api/v1/workflow-executions/{id}` and `/api/v1/workflow-executions/{id}/tasks?limit=50&offset=0`. Cancel via `POST /api/v1/workflow-executions/{id}/cancel`. Errors use `application/problem+json` with a stable `code` field. Input bounds and semantics are described in ARCHITECTURE.md.

## Execute a DAG

```sh
python3 durableflow-examples/run.py
docker compose logs -f worker scheduler
```

Compose starts three worker replicas. Scale with `docker compose up -d --scale worker=4`. Workers do not publish host ports. Inspect health with `docker compose ps` or `docker compose exec --index 1 worker curl --fail http://localhost:8080/actuator/health/readiness`. A permanent failure cancels all unfinished work; cancellation fences later results but cannot undo an already performed effect. Unknown task types remain queued until a compatible worker exists. See ARCHITECTURE.md for transaction and shutdown boundaries.

## Recovery and dead letters

The scheduler recovers expired leases automatically. Kill a worker during a `delay` task and another worker will claim a new attempt after lease expiry/backoff. Run two schedulers with `docker compose up -d --scale scheduler=2` only after removing the scheduler host-port mapping or using the fault-test override added later; one database session owns maintenance leadership. Task claims stay safe independently of leadership.

Query `GET /api/v1/dead-letter-tasks?limit=50&offset=0`; retry with `POST /api/v1/task-executions/{id}/retry`. Retries preserve task identity and immutable input. A user-cancelled workflow cannot be reopened. See [delivery semantics and failure windows](docs/DELIVERY_SEMANTICS.md) before writing side-effecting handlers.

## Operations and security

Prometheus: http://localhost:9090. OpenAPI and API metrics require a bearer token. `scripts/init-env.py` creates distinct operator/admin tokens and the Prometheus secret file without printing them. The Python example reads the local operator token automatically. See [operations](docs/OPERATIONS.md) for metrics, trace inspection and dashboards, and [security/threat model](docs/SECURITY.md) for trust boundaries and remaining deployment requirements.
