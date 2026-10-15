# 0004: REST public control plane

Status: Accepted

Date: 2026-10-15

## Context

Clients need straightforward submission, inspection and cancellation with an interview-friendly operational model.

## Decision

Use versioned REST endpoints under /api/v1, JSON payloads, idempotency keys and consistent problem responses. Persist accepted work before responding. Add OpenAPI documentation with the APIs in Phase 1.

## Consequences

HTTP tooling is sufficient and easy to demonstrate. Clients poll status initially; streaming and a dedicated SDK are not required. Versioning and idempotency equivalence rules must be explicit.
