# Phase 3 report

Implemented persistent attempt history, database-time lease recovery, execution deadlines, bounded exponential retry jitter, retry categories, dead letters and manual retry, stable handler idempotency context, optimistic task version guards, idempotent result reporting and session-advisory-lock scheduler leadership. Cancellation records active attempts and remains idempotent.

Every result checks attempt identity before mutating current state. The attempt history lets an equivalent duplicate succeed after workflow completion without reapplying transitions. Expired workers cannot renew or commit. Manual retry preserves history, grants a fresh budget and reactivates only prerequisite-failure cancellations; user cancellation stays terminal. Maintenance leadership is independent of atomic claim correctness.

Changed migration V4, persistence/domain recovery code, scheduler leadership, retry/list APIs, bounded JDBC timeouts, deterministic model/integration tests and delivery-semantics documentation.

Validation: `mvn spotless:apply` and `mvn verify` passed with 41 tests and zero failures/errors/skips. Coverage includes all eight documented failure windows using real PostgreSQL sessions, controlled persisted deadlines, transaction abort, backend termination, concurrent duplicate reports and destination-side deduplication. The upgraded Compose database migrated successfully, all 15 health probes passed, and the live diamond completed successfully.

Limitations: external effects may repeat; handler authors own destination idempotency. PostgreSQL is the availability boundary. Attempt/idempotency history has no automatic retention. Authentication and telemetry hardening arrive in Phase 4. No owner decision is required.
