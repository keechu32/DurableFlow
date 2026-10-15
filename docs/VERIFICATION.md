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
