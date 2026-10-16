# Phase checklist

## Phase 0: foundation

- [x] Inspect repository and record implementation plan.
- [x] Java 21 Maven reactor and three independent Spring Boot applications.
- [x] Formatting, toolchain enforcement and SpotBugs checks.
- [x] PostgreSQL Compose service, persistent volume and environment-supplied credentials.
- [x] Flyway bootstrap migration; validation enabled, clean disabled.
- [x] Health, liveness and database-aware readiness endpoints.
- [x] Unit failure test and Testcontainers integration tests.
- [x] CI build/test pipeline and full Compose smoke check.
- [x] Ignore rules, editor settings, README, architecture and contribution guidance.
- [x] Initial ADRs, including the adjusted module structure.
- [x] Run host-only verification: compile all modules/tests, unit failure test, formatting and SpotBugs.
- [x] Verify PostgreSQL integration and full Compose startup on a Docker-enabled machine.
- [x] Complete all Phase 0 verification; see VERIFICATION.md for actual results.

Phase 0 verification completed during Phase 1 after installing a local container runtime.

## Subsequent phases

- [x] Phase 1: domain state machines, versioned DAG definitions and idempotent control plane.
- [x] Phase 2: atomic scheduling, workers, safe handlers and cancellation.
- [x] Phase 3: leases, recovery, retry semantics and leader failover.
- [x] Phase 4: distributed telemetry, operations, authentication and threat model.
- [x] Phase 5: reproducible faults and measured benchmarks.
- [x] Phase 6: public release preparation, audit and fresh-clone verification.

Phase reports: [Phase 1](phases/phase-1.md), [Phase 2](phases/phase-2.md), [Phase 3](phases/phase-3.md), [Phase 4](phases/phase-4.md), [Phase 5](phases/phase-5.md), [Phase 6 readiness](RELEASE_READINESS.md).

Phase 5 is complete: all nine fault scenarios passed on Windows, and one complete 1/2/4/8-worker exploratory benchmark plus the worker-recovery probe passed. Failed and interrupted observations remain preserved. See the [Phase 5 report](phases/phase-5.md), [benchmark results](../BENCHMARKS.md), and [resume notes](WORK_STATUS.md).

## Phase 6 release preparation

- [x] Add a concise public description, architecture diagram, PowerShell quick start, API example, terminal demo, and isolated worker-recovery demo.
- [x] Document at-least-once delivery, testing, troubleshooting, roadmap, and known limitations.
- [x] Add MIT license, bug/feature/design issue forms, pull-request guidance, and release checklist.
- [x] Add release-readiness report with evidence-based project summary and interview/whiteboard prompts.
- [x] Complete dependency, secrets, generated-file, link, claim, test and naming audits; record scanner and CI access limitations.
- [x] Run clean full test suite, focused failure demo, and fresh-clone setup simulation; record actual outcomes.
- [x] Review and document known release limitations; no release or deployment performed.

Phase 6 is complete as a local release-preparation phase. The exact branch tip was cloned, initialized without existing credentials, started in an isolated Compose project, health-checked, and used to run the terminal DAG demo. Local full verification passed. A release is not published; see the [readiness report](RELEASE_READINESS.md) and [release checklist](RELEASE_CHECKLIST.md) for the separate future release gate.
