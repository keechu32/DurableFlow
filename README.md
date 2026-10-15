# DurableFlow

A small PostgreSQL-backed durable DAG workflow engine, built to make distributed execution and its failure modes understandable and testable.

**Status: Phase 0 foundation.** API, scheduler and worker processes start and expose health endpoints. Workflow submission, scheduling, handlers and recovery are planned; they are not implemented yet. No performance claims or benchmark results exist.

Repository: `git@github.com:keechu32/DurableFlow.git`.

## Local startup

Prerequisites: Docker Engine/Desktop with Compose v2 supporting `up --wait`, about 4 GB available memory, and ports 5432 and 8080–8082 free. Container builds supply Java 21 and Maven. For host builds, install JDK 21 and Maven 3.9.9 or newer (3.x).

Create local configuration (never commit `.env`):

```sh
umask 077
printf 'DURABLEFLOW_DB_USER=durableflow\nDURABLEFLOW_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" > .env
docker compose up --build --wait --wait-timeout 240
curl --fail http://localhost:8080/actuator/health
bash scripts/smoke-test.sh
```

The smoke script also requires Python 3 and curl on the host. API: localhost:8080; scheduler health: localhost:8081; worker health: localhost:8082. PostgreSQL listens on localhost:5432. All published ports bind to loopback. Credentials come from your ignored `.env`. Changing database credentials after the volume is initialized does not change existing PostgreSQL accounts.

```sh
docker compose down                 # keep database data
# Destructive, optional clean reset: docker compose down --volumes
```

Health endpoints: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`. Readiness includes database connectivity; liveness represents process health. Migration failure prevents startup. Health details are hidden and sensitive actuator endpoints are not exposed. No control-plane endpoints or OpenAPI document exist until Phase 1.

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
| `durableflow-model` | Dependency-free domain module reserved for Phase 1 |
| `durableflow-persistence` | Shared JDBC configuration and immutable Flyway migrations |
| `durableflow-api` | Public control-plane application |
| `durableflow-scheduler` | Coordination application |
| `durableflow-worker` | Execution application |
| `durableflow-integration-tests` | Cross-process foundation and PostgreSQL checks |

See [architecture](ARCHITECTURE.md), [ADRs](docs/adr/README.md), [phase checklist](docs/PHASE_CHECKLIST.md), and [contribution guide](CONTRIBUTING.md).

## Delivery semantics and roadmap

The target contract is **at least once**: a task may run again after a crash or lease loss. Side-effecting handlers must use a stable task idempotency key with the destination system. PostgreSQL transactions cannot make arbitrary external effects exactly once.

The next phases add DAG validation and submission, atomic claims and safe handlers, recovery and retries, telemetry and security, fault tests and measurements, then release preparation. Each phase requires explicit authorization to continue. Authentication, role separation, trace propagation and workflow metrics are not available in Phase 0. Use this foundation locally only.

## Troubleshooting

- Docker unavailable: install/start a compatible container runtime before `verify` or Compose startup.
- Port occupied: stop the conflicting service or change the host port in Compose and the smoke script.
- Database authentication fails: use the credentials that initialized the volume; reset it only if its data is disposable.
- Flyway validation fails: do not edit applied migrations; add a new versioned migration.
- Maven download fails: check access to Maven Central and your proxy configuration; do not disable TLS verification.
- An application is unhealthy: run `docker compose ps` and `docker compose logs api scheduler worker postgres`. Startup may take longer on the first image build.
