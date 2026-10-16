# Implementation plan

**Updated 2026-10-16.** Phases 0–5 are complete; Phase 6 release preparation is in progress. Java 21 remains the required runtime. This plan tracks project work; it does not authorize publication or deployment.

1. **Phase 1:** explicit state machines and bounded DAG validation; immutable definition versions; transactional submission, JSONB idempotency and cancellation; REST problems, pagination and OpenAPI; domain/concurrency/API tests.
2. **Phase 2:** atomic PostgreSQL claims; worker capabilities and safe handlers; dependency reconciliation, cancellation and draining; three-worker Compose demo; multi-worker tests.
3. **Phase 3:** fenced leases, attempts, heartbeats, timeouts, retries and jitter; dead-letter APIs; advisory-lock scheduler leadership; deterministic crash-window tests.
4. **Phase 4:** authentication/roles, limits, audit and sanitized errors; OTLP traces, metrics, dashboards and threat model; security/telemetry tests.
5. **Phase 5 (complete):** executable fault scenarios and benchmark tooling; run and preserve raw observations and machine metadata; publish summaries only after successful measurement. See [report](phases/phase-5.md) and [benchmark results](../BENCHMARKS.md).
6. **Phase 6 (in progress):** MIT license, bug/feature/design templates, pull-request guidance, PowerShell quick start and API examples, terminal/failure-recovery demos, troubleshooting, release checklist and readiness/interview report. Finish repository audits, exact-commit full checks and a fresh-clone setup simulation; document remaining limitations. Do not publish a release or deploy.

For every phase: inspect current changes, implement, format, run meaningful tests, update README/ARCHITECTURE/checklist and verification evidence. Missing infrastructure or failed checks remain explicit; do not claim unobserved outcomes.

Current verification environment: Windows 11 host with Docker Desktop Linux containers; Python 3.11.9; host Java 17 and no Maven. Use the repository's Java 21/Maven Docker image and Docker socket for Maven/Testcontainers checks rather than changing the project toolchain or relying on machine-specific cache paths. No remote infrastructure is used.
