# Implementation plan

**Resumed on 2026-10-16 from commit `018d870`.** Phases 0–4 are complete. Phase 5 is in progress; Phase 6 has not started. Java 21 remains the required runtime. Local Java 25 edits were preserved in a stash and are not applied.

The owner authorized completing Phases 1–6 and committing/pushing each phase on 2026-10-15. This supersedes the earlier per-phase stop instruction. No cloud resources, spending or deployment are authorized.

1. **Phase 1:** explicit state machines and bounded DAG validation; immutable definition versions; transactional submission, JSONB idempotency and cancellation; REST problems, pagination and OpenAPI; domain/concurrency/API tests.
2. **Phase 2:** atomic PostgreSQL claims; worker capabilities and safe handlers; dependency reconciliation, cancellation and draining; three-worker Compose demo; multi-worker tests.
3. **Phase 3:** fenced leases, attempts, heartbeats, timeouts, retries and jitter; dead-letter APIs; advisory-lock scheduler leadership; deterministic crash-window tests.
4. **Phase 4:** authentication/roles, limits, audit and sanitized errors; OTLP traces, metrics, dashboards and threat model; security/telemetry tests.
5. **Phase 5 (complete):** executable fault scenarios and benchmark tooling; run and preserve raw observations and machine metadata; publish summaries only after successful measurement. See [report](phases/phase-5.md) and [benchmark results](../BENCHMARKS.md).
6. **Phase 6:** MIT license, templates, quick start, terminal demo, troubleshooting, release audit and fresh-clone checks; release-readiness report and interview material.

For every phase: inspect current changes, implement, format, run meaningful tests, update README/ARCHITECTURE/checklist and verification evidence, then commit and push. Missing infrastructure or failed checks remain explicit; do not claim unobserved outcomes.

Current verification environment: Windows 11 host with Docker Desktop Linux containers; Python 3.11.9; host Java 17 and no Maven. Use the repository's Java 21/Maven Docker image and Docker socket for Maven/Testcontainers checks rather than changing the project toolchain or relying on machine-specific cache paths. No remote infrastructure is used.
