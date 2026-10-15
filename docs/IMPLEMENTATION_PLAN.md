# Implementation plan

## Phase 0

1. Inspect repository and toolchain — done: empty repository; Java 21/Maven available; Docker absent.
2. Establish Java 21 Maven reactor, formatting and static checks — done.
3. Add API, scheduler, worker applications and shared PostgreSQL/Flyway infrastructure — done.
4. Add health, migration and startup-failure tests; container smoke checks in CI — implemented; container execution blocked locally.
5. Document topology, trust boundaries, decisions, contributor setup and verification evidence — done.
6. Stop before Phase 1 — no later-phase implementation started.

The host-only build passed. Full Phase 0 verification remains pending on a Docker-enabled machine; see [evidence](VERIFICATION.md). Later phases require explicit `continue`.
