# Implementation plan

The owner authorized completing Phases 1–6 and committing/pushing each phase on 2026-10-15. This supersedes the earlier per-phase stop instruction. No cloud resources, spending or deployment are authorized.

1. **Phase 1:** explicit state machines and bounded DAG validation; immutable definition versions; transactional submission, JSONB idempotency and cancellation; REST problems, pagination and OpenAPI; domain/concurrency/API tests.
2. **Phase 2:** atomic PostgreSQL claims; worker capabilities and safe handlers; dependency reconciliation, cancellation and draining; three-worker Compose demo; multi-worker tests.
3. **Phase 3:** fenced leases, attempts, heartbeats, timeouts, retries and jitter; dead-letter APIs; advisory-lock scheduler leadership; deterministic crash-window tests.
4. **Phase 4:** authentication/roles, limits, audit and sanitized errors; OTLP traces, metrics, dashboards and threat model; security/telemetry tests.
5. **Phase 5:** executable fault scenarios and benchmark tooling; run and preserve raw observations and machine metadata; publish summaries only after successful measurement.
6. **Phase 6:** MIT license, templates, quick start, terminal demo, troubleshooting, release audit and fresh-clone checks; release-readiness report and interview material.

For every phase: inspect current changes, implement, format, run meaningful tests, update README/ARCHITECTURE/checklist and verification evidence, then commit and push. Missing infrastructure or failed checks remain explicit; do not claim unobserved outcomes.

Local verification setup: attempt a local Colima container runtime so PostgreSQL integration, Compose startup and fault tests can actually run. No remote infrastructure is used.
