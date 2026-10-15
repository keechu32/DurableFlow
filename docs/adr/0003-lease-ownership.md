# 0003: Lease-based worker ownership

Status: Accepted

Date: 2026-10-15

## Context

Workers can disappear or pause for an unbounded interval; process identity alone cannot prove exclusive current ownership.

## Decision

Atomically assign worker, attempt and lease expiration in PostgreSQL. Use short transactions, compatible capability filtering and guarded heartbeat/result updates. Reclaim expired leases and reject stale results. Task code runs outside transactions.

## Consequences

No lock spans handler execution. Lease expiration can overlap with an old worker still performing effects, so guarded persistence cannot prevent all duplicate external effects. Attempt identity must fence stale writes. Heartbeat cadence, lease length and timeout rules will be fixed in the execution/recovery phases.
