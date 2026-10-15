# 0005: Exclude Kafka, Redis, Kubernetes and Raft initially

Status: Accepted

Date: 2026-10-15

## Context

Additional infrastructure would increase failure modes and operational burden before measurable need is established.

## Decision

Use only PostgreSQL coordination and Docker Compose initially. Do not add Kafka, Redis, Kubernetes or an application-level Raft implementation.

## Consequences

Fewer moving parts support reproducible failure tests. PostgreSQL availability bounds engine availability and scale. Revisit only with measurements and a concrete requirement; deployment orchestration and distributed consensus are separate concerns.
