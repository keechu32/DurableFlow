# Delivery semantics and failure windows

Task execution is **at least once**. PostgreSQL transactions protect engine state, not arbitrary external effects. Each claim allocates a monotonically increasing attempt and a lease tied to a unique worker. Every start/completion checks the owner, attempt, unexpired database-time lease and current workflow state. Row versions guard task updates. Attempt history retains outcomes so an equivalent duplicate report returns success without changing task state or retry timing; conflicting or stale success reports are rejected.

## Failure windows

| Window | Expected behavior | Deterministic integration coverage |
| --- | --- | --- |
| Worker crashes before beginning execution | LEASED expires, attempt is recorded EXPIRED, work waits for backoff then becomes READY | `RecoveryIT.crashBeforeStartAndCrashDuringExecutionAreRecovered` |
| Worker crashes during execution | RUNNING expires; another worker may execute a new attempt | Same test, started branch |
| Side effect completes, worker crashes before reporting success | Effect may repeat; stable task key permits destination-side deduplication | `lostCompletionAfterEffectCanRepeatButDestinationKeyDeduplicates` |
| Slow worker continues past lease expiration | Heartbeat and completion are rejected; its external effect may still occur | `expiredSlowWorkerCannotExtendOrCommitAndTimeoutIsCategorized` |
| Scheduler crashes during transition | Uncommitted PostgreSQL transaction rolls back; next reconciliation recomputes eligibility | `schedulerTransactionCrashDoesNotExposePartialUnblock` |
| Duplicate result report | Equivalent recorded result returns success with no version change; conflicting output is rejected | `duplicateSuccessIsIdempotentEvenAfterWorkflowCompletion` |
| Database connection fails during completion | Commit may be ambiguous to the worker; same report can be retried, otherwise recovery eventually re-executes | `databaseConnectionLossDuringCompletionRollsBackAndCanBeReportedAgain` |
| Two schedulers compete | One session advisory lock owner performs maintenance; termination releases the lock and standby acquires it | `competingSchedulersElectOneAndLeadershipTransfersAfterBackendDeath` |

Tests advance persisted deadlines instead of waiting for lease durations. Connection-termination and transaction-abort tests use actual PostgreSQL sessions. Runtime kill/restart scenarios are added by the fault tooling in Phase 5.

## Handler idempotency contract

`Lease.idempotencyKey()` is `durableflow:task:{taskExecutionId}` and stays constant across attempts, worker changes and manual retry. Handler context also supplies workflow ID, task ID, attempt and worker ID. Do not derive an effect key from the attempt or worker. Supply this key to the destination's idempotency mechanism, or make a destination transaction insert a unique effect record together with its business update. For example:

```sql
INSERT INTO applied_effects(idempotency_key) VALUES (:stable_task_key)
ON CONFLICT DO NOTHING;
-- Apply the business update in the SAME destination transaction only if the insert won.
```

A separate marker committed before or after a business update is insufficient. A destination without idempotency or a transactional effect record can observe duplicate effects. The engine cannot compensate for that automatically. Built-in examples are pure transformations and bounded delay, avoiding unsafe external effects.

## Retry and cancellation rules

`INVALID_INPUT` is non-retryable. `HANDLER_ERROR`, `LEASE_EXPIRED` and `TIMEOUT` are retryable. Backoff starts at 100 ms, doubles per attempt, applies bounded ±20% jitter and has a hard 30-second ceiling. The per-task definition sets 1–20 maximum attempts and a 1–3,600 second execution timeout. Heartbeats cannot extend the timeout. A failed/non-retryable or exhausted task becomes DEAD_LETTERED; the workflow fails and cancels remaining unfinished work.

Manual retry only accepts a dead-lettered task. It repeats the original input, grants another `max_attempts` budget, and preserves attempt numbering/history. If other dead letters remain, the workflow stays failed until they are also retried. When none remain, it resumes RUNNING and reactivates only tasks cancelled with `UPSTREAM_FAILED`. A user-cancelled workflow never reopens. Manual retry is useful after an operational issue or handler fix; a malformed immutable input normally needs a new execution with corrected input.

Cancellation is idempotent and terminal. It atomically fences outstanding tasks and records active attempts as cancelled. It cannot undo completed effects. Work started before cancellation can race with interruption; its later engine-state result is rejected.

## Leadership and availability

Scheduler leadership uses PostgreSQL session advisory lock `741938251`. The leader reserves one JDBC connection, keeps it outside transactions and explicitly unlocks before returning it to the pool. Broken connections are discarded. A standby retries each scheduler tick. Claims and results remain guarded by row locks and ownership checks independently of leadership. Database unavailability stops progress; JDBC connection and socket timeouts bound disconnected operations. PostgreSQL high availability and backups remain deployment responsibilities.
