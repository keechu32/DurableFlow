# 0002: At-least-once execution

Status: Accepted

Date: 2026-10-15

## Context

External side effects cannot generally share a transaction with task-result persistence.

## Decision

Permit retries and duplicate execution. Require handlers with side effects to support idempotency using a stable task identity across attempts. Never claim exactly-once external effects.

## Consequences

Recovery remains possible after crashes. A completed effect can repeat if its acknowledgement is lost. Handler authors must arrange destination-side deduplication or a transactional effect record; the engine alone cannot guarantee it.
