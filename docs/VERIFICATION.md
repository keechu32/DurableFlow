# Phase 0 verification

Recorded on 2026-10-15. These are local observations, not CI results or benchmark measurements.

## Environment

- macOS 26.7.1, ARM64.
- Oracle JDK 21.0.10; Maven 3.9.16.
- Spring Boot 4.1.1; JUnit Jupiter 5.14.4; Testcontainers 2.0.5.
- No Docker executable or alternative container runtime was found.
- The environment's configured proxy could not resolve. Dependency downloads succeeded with proxy environment variables unset and network-enabled execution. No project proxy settings were committed.

## Executed checks

| Check | Observed result |
| --- | --- |
| `mvn -B -ntp spotless:apply` | Passed; Java source formatted |
| `mvn -B -ntp -DskipITs verify` | Passed across all seven reactor entries; application jars and all integration-test sources compiled |
| Database-unavailable unit test | 1 test passed; startup fails when PostgreSQL cannot complete its handshake |
| SpotBugs, included in `verify` | No findings in application modules |
| Full `mvn -B -ntp verify` | Failed during Testcontainers setup: `Could not find a valid Docker environment`; integration test bodies did not execute |
| `bash -n scripts/smoke-test.sh` | Passed shell syntax check |
| Ruby YAML parsing of Compose, CI and four application/shared configurations | Passed; syntax validation only |
| Source whitespace and local Markdown link checks | Passed after documentation was completed |
| Full Compose image build/startup and HTTP smoke checks | Not executed: Docker absent |

Actual Maven runs also supplied `-Dmaven.repo.local=/tmp/durableflow-m2`, with proxy environment variables unset for network access. This kept dependency downloads outside the repository. The successful `-DskipITs` run is explicitly partial and is not evidence that PostgreSQL integration passed. The full-suite failure was observed before the final removal of unused test libraries; the final host-only build recompiled the JUnit 5 harness successfully.

## Container checks implemented, not yet observed locally

`ApplicationFoundationIT` checks all three application contexts against PostgreSQL 17.6:

- Successful migration, health, liveness and readiness over HTTP.
- Hidden sensitive actuator endpoints and health details.
- UTC JDBC sessions and repeat migration without reapplication.
- Database constraints on the bootstrap metadata marker.
- Concurrent migration attempts apply a version only once.
- Checksum drift prevents migration.

The GitHub Actions workflow runs the complete Maven verification, builds all Compose application images, waits for PostgreSQL and application readiness, then checks all nine health URLs. Its credentials and database storage are ephemeral. No workflow run has been triggered or observed here.

## Outstanding verification

On a Docker-enabled machine:

```sh
mvn -B -ntp verify
# Then follow README to generate .env:
docker compose config --quiet
docker compose up --build --wait --wait-timeout 240
bash scripts/smoke-test.sh
docker compose down
```

Keep Phase 0's container-verification checklist open until these checks pass. No push, publish, deployment or external infrastructure creation has occurred. The requested SSH URL was configured as the local origin remote only.

## Phase 1 update (2026-10-15)

The local runtime gap is resolved: Colima 0.10.3, Docker Engine 29.5.2 (4 CPUs, 6 GiB configured), Compose 5.6.0. Direct Docker Hub CDN downloads encountered an enterprise gateway certificate failure; Google's public Docker Hub mirror succeeded with TLS verification intact. Configuration and public certificates stayed outside the repository.

`mvn spotless:apply verify` passed: **21 tests, zero failures/errors/skips**, including real PostgreSQL and HTTP integration checks. The complete Compose build/startup passed, as did all nine smoke probes. Earlier failed attempts remain recorded above and in the Phase 1 report; they are superseded by these observed successful runs. No benchmark measurement is claimed.

## Phase 2 update (2026-10-15)

`mvn spotless:apply verify` passed with **29 tests, zero failures/errors/skips**. New PostgreSQL tests exercise 16 competing claims, wrong-owner result rejection, capability filtering, three simultaneous worker identities, prerequisite timestamps, fail-fast propagation, cancellation and graceful draining. Handler tests include the known SHA-256 vector, restricted JSON projection and rejection of shell execution. The foundation health test now passes `--server.port=0` explicitly, fixing a configuration-precedence bug that could route probes to an existing Compose process.

The Phase 2 Compose stack started PostgreSQL, API, scheduler and **three healthy workers**. All 15 health/liveness/readiness smoke probes passed. `python3 durableflow-examples/run.py` submitted the four-task diamond and observed PENDING → RUNNING → SUCCEEDED through the live REST API. This is a functional demonstration, not a throughput measurement.

## Phase 3 update (2026-10-15)

`mvn spotless:apply` and full `mvn verify` passed: **41 tests, zero failures/errors/skips**. Tests terminate actual PostgreSQL sessions during completion and leadership ownership, abort an uncommitted transition, advance persisted deadlines for recovery/timeout, race duplicate reports and exercise retry exhaustion/manual retry. [Delivery semantics](DELIVERY_SEMANTICS.md) maps all eight failure windows to executable tests. The existing Compose database migrated to V4, all 15 probes passed and the diamond example reached SUCCEEDED again.

## Phase 4 update (2026-10-15)

Full Maven verification passed: **49 tests, zero failures/errors/skips**. The live authenticated diamond completed; API, scheduler and all three worker Prometheus targets were UP. Collector JSON contains a single workflow trace across three separate application containers; see [trace evidence](evidence/phase-4-trace.json). Request-size, strict JSON, role, audit, failure-log redaction and in-memory exporter tests also passed.

Trivy 0.75.0 initially reported HIGH/CRITICAL Tomcat/Jackson advisories. Maintenance overrides (Tomcat 11.0.26; Jackson 2.21.7/3.1.7) were tested and rescanned. Final source/resolved-jar scan: **zero HIGH/CRITICAL vulnerabilities and zero secrets**, with no CVE ignore rules. [Security scan summary](evidence/phase-4-security.json) records the exact command and raw-report hash. Offline dependency identification avoids rate-limited Maven lookups; it does not disable vulnerability-index lookup. The collector uses bounded named storage after tmpfs inspection failed; all final operational services started successfully.

## Phase 5 update (2026-10-16, Windows)

Docker Desktop Linux-container mode was available (Engine 29.8.2, 12 CPUs, 7.72 GiB memory; Compose 5.6.0). Python 3.11.9 was installed on the host. Java 21.0.12.1 and Maven 3.9.16 were supplied through the repository's Docker build image; the host Java 17 and absent Maven did not alter the project toolchain.

| Check | Observed result |
| --- | --- |
| `docker compose build` | Passed for API, scheduler and worker using Java 21 |
| Docker-based `mvn -B -ntp clean verify` | Passed: 49 Java tests, formatting and SpotBugs; Testcontainers ran against Docker |
| Python benchmark tests | 6 passed; includes HTTP connection reuse across batches |
| Python syntax checks | Passed for experiment, benchmark and fault-test scripts |
| Compose config validation | Passed for base and experimental configurations |
| Fault harness, complete rerun | All nine scenarios passed; evidence in `fault-tests/results/2026-10-16T07-52-45.126264+00-00-eee973/results.json` |
| Complete benchmark | Passed at 1, 2, 4 and 8 workers (1,000 samples each, concurrency 16, 256 payload padding bytes, 5-second minimum warm-up) and recovery probe; see raw summary `benchmark/raw/2026-10-16T07-59-03.144012+00-00-3d3cea/summary.json` |

The first Windows fault attempt is retained separately with its harness `NameError`; the corrected rerun passed. Earlier benchmark failure/interruption records and the anomalous eight-worker PostgreSQL CPU sample were not discarded. The benchmark report labels this as a single exploratory run and documents that sampled peak (1,392.77%) exceeds the nominal 1,200% scale of 12 Docker CPUs. Detailed fault conditions, outcomes, machine metadata and limitations are recorded in the [Phase 5 report](phases/phase-5.md) and [benchmark summary](../BENCHMARKS.md).

## Phase 6 verification (2026-10-16, pre-clone)

- Docker-based `mvn -B -ntp clean verify` passed with 49 tests, zero failures/errors/skips, including all PostgreSQL Testcontainers integration tests, Spotless and SpotBugs.
- `python -m unittest discover -s benchmark -p 'test_*.py'` passed all six Python tests; `compileall` passed for scripts, benchmark, fault tests and examples.
- The new isolated `python fault-tests/run.py --scenario worker-kill` demonstration passed and retained its evidence at `fault-tests/results/2026-10-16T08-16-44.854545+00-00-80b4b5/results.json`.
- Docker Compose configuration for base and experimental projects passed in Phase 5. The single-scenario harness uses the same experimental configuration.
- A local Markdown-link audit found zero broken relative links. PyYAML parsed the four new GitHub issue-template/config files and the CI workflow.
- The repository's local `.env`, `.secrets/operator-token`, Maven cache and generated `target/`/Python cache outputs are ignored and are not tracked.
- `mvn dependency:analyze` completed, but reported expected false positives for Spring Boot starters and runtime/service-provider dependencies (including security/web/actuator auto-configuration, springdoc, Prometheus, JDBC/Flyway drivers and the JUnit aggregator). POMs and source usages were reviewed; no runtime dependency was removed on static analysis alone. This prototype adds no Phase 6 runtime dependencies.
- Trivy is not installed on the host and its container image was not present. The existing GitHub Actions security job scans HIGH/CRITICAL vulnerabilities and secrets; Phase 4's resolved-jar/source scan passed with zero HIGH/CRITICAL findings and zero secrets. Candidate-commit CI results and the fresh-clone setup simulation remain pending.
