# Release readiness report

## Status

**Phase 6 implementation and audit are in progress. No release is authorized or published.** The repository is a local demonstration, not a production-ready hosted service. The checklist in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) remains the release gate.

## Public presentation

Suggested repository description: **A PostgreSQL-backed durable DAG workflow engine demonstrating at-least-once execution, lease fencing, retries, and failure recovery.**

Suggested topics: `java`, `spring-boot`, `postgresql`, `workflow-engine`, `distributed-systems`, `durable-execution`, `fault-tolerance`, `testcontainers`, `open-telemetry`.

The project includes a five-minute quick start, authenticated PowerShell REST requests, a terminal diamond-DAG demo, an isolated worker-crash recovery demo, architecture diagram, delivery contract, tests, troubleshooting and explicit limitations. First-time image/dependency downloads may exceed five minutes.

## Evidence-based resume bullets

- Built a Java 21/Spring Boot durable DAG workflow engine on PostgreSQL with transactional submission, idempotency, lease fencing, retry/dead-letter handling, and at-least-once execution; verified with 49 Java tests including PostgreSQL Testcontainers integration.
- Implemented nine isolated Docker fault scenarios and a 1/2/4/8-worker benchmark harness; all nine scenarios and one 4,000-task exploratory benchmark passed on Windows, with raw evidence retained and anomalous CPU observations explicitly caveated.

These describe the observed repository and one local run only. Do not present the benchmark as production capacity or general scalability.

## Interview discussion prompts

1. Why use short PostgreSQL transactions for claims/results and never hold a transaction open while handler code runs?
2. How do owner, attempt, lease expiry and row-version checks fence a stale worker's database result?
3. What crash window still allows a duplicate external side effect, and how does a stable task idempotency key help?
4. Why does scheduler advisory-lock leadership not participate in task-claim correctness?
5. Why can a database restart preserve workflow progress while still making availability dependent on PostgreSQL?
6. Which state transitions occur atomically during cancellation, retry exhaustion, and manual retry?
7. What did the worker-kill, slow-completion and duplicate-report tests prove—and what did they not prove?
8. Why did completion throughput fall at eight workers in the recorded sample, and why is that insufficient for a causal diagnosis?

## Whiteboard tradeoffs and failure scenarios

- **PostgreSQL vs. a broker:** PostgreSQL unifies durable state, coordination and transactions for this scope; row contention and polling limit scale. A broker would not remove the need to fence task state and coordinate workflow dependencies.
- **At-least-once vs. exactly-once:** leases recover abandoned work but cannot atomically commit an arbitrary destination side effect. Idempotency or a destination-side transaction is required.
- **Leader failure:** the advisory-lock session releases on backend termination; a standby resumes maintenance, while independent claim/result fencing remains active.
- **Worker pauses after lease loss:** a replacement may execute concurrently. The old worker cannot extend or commit current state; its already-performed external effect is not revoked.
- **Database outage:** accepted committed work remains durable, but scheduling/execution progress pauses. This prototype does not include HA, backups or point-in-time recovery.
- **Cancellation race:** workflow/task locks and guarded result writes make cancellation terminal in engine state; a side effect already issued by a handler cannot be undone.
- **Benchmark anomaly:** the eight-worker PostgreSQL CPU sample exceeded the nominal Docker CPU scale; preserve it, mark it anomalous, and repeat controlled measurements before making capacity claims.

## Audit summary

| Area | Current finding |
| --- | --- |
| License | MIT license added for project-owned material; dependency licenses remain separate |
| Secrets and personal data | Local `.env`, `.secrets`, Maven cache and Python bytecode are ignored; no credentials should be added to issues or evidence |
| Generated/build output | `target/`, `.local/`, `__pycache__/`, and `.pyc` are ignored; benchmark/fault JSON is intentional evidence |
| Tests and CI | Java tests, Testcontainers, Spotless, SpotBugs, Python tests, Compose startup/smoke and Trivy scans are wired into CI; exact candidate checks are recorded in `VERIFICATION.md` |
| Dependency analysis | `mvn dependency:analyze` completed; it reports framework starters/runtime service-provider dependencies as source-unused. POM/source review confirms these provide required auto-configuration, JDBC/Flyway, OpenAPI, Prometheus, security and test-runtime behavior; no dependency was removed based on this static report |
| Local secret scan | Trivy is not installed locally and its image was absent. Ignored secret/config paths are not tracked; the existing CI Trivy job will scan the candidate commit. Phase 4's resolved-jar/source scan had zero HIGH/CRITICAL findings and zero secrets |
| Performance claims | One exploratory benchmark with an anomalous sampled CPU peak; no production capacity, SLO, or linear-scaling claim |
| Operational limitations | Single PostgreSQL node, local plaintext network, shared development tokens, no multi-tenant identity/quotas, backups, HA, retention or public deployment |

## Remaining release gate

The clean full test suite, dependency review, local link/YAML checks and initial secret/generated-file audit passed. Complete the fresh-clone setup simulation and confirm CI/security scans for the candidate commit. Review unresolved findings in [SECURITY.md](SECURITY.md), [VERIFICATION.md](VERIFICATION.md), and [BENCHMARKS.md](../BENCHMARKS.md). Do not publish a release, deploy, or provision cloud infrastructure as part of this phase.
