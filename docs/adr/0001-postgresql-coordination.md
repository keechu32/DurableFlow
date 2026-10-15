# 0001: PostgreSQL for persistence and coordination

Status: Accepted

Date: 2026-10-15

## Context

Durable state and coordination must agree across process crashes without operating a separate message broker.

## Decision

Use PostgreSQL as the source of truth, with explicit JDBC transactions, constraints, atomic claims and row locks. Flyway owns schema evolution. Use database time for lease boundaries.

## Consequences

Atomic state changes and familiar tools keep the initial design explainable. Database outages stop progress, polling adds load, and the database can become the throughput bottleneck. Benchmark before adding infrastructure. H2 is not a substitute for PostgreSQL locking tests.
