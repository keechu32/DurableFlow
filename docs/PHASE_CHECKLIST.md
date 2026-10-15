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
- [ ] Verify PostgreSQL integration and full Compose startup on a Docker-enabled machine.
- [ ] Complete all Phase 0 verification; see VERIFICATION.md for actual results.

Phase 0 implementation is present; completion remains conditional on passing the outstanding container checks. No Phase 1 work has begun.

## Subsequent phases (explicit `continue` required)

- [ ] Phase 1: domain state machines, versioned DAG definitions and idempotent control plane.
- [ ] Phase 2: atomic scheduling, workers, safe handlers and cancellation.
- [ ] Phase 3: leases, recovery, retry semantics and leader failover.
- [ ] Phase 4: distributed telemetry, operations, authentication and threat model.
- [ ] Phase 5: reproducible faults and measured benchmarks.
- [ ] Phase 6: public release preparation, audit and fresh-clone verification.
