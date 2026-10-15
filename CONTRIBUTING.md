# Contributing

Use JDK 21, Maven 3.9.9+ (3.x), Docker and Compose v2. Follow the startup instructions in [README](README.md). Run `mvn -B -ntp spotless:apply` and `mvn -B -ntp verify` before a pull request. CI also runs a Compose smoke test.

Keep model, persistence, scheduling, API and task execution responsibilities separate. Describe transaction boundaries and crash behavior when changing coordination. Prefer deterministic tests with latches, controllable clocks and bounded polling. Test real PostgreSQL semantics with Testcontainers instead of substituting H2. Integration tests end in `IT`; unit tests end in `Test`. Tests use JUnit 5 and programmatic Spring contexts. Spring Framework 7's `SpringExtension` requires JUnit 6, so do not introduce it into this harness.

Treat execution as at least once. Never add API-supplied shell commands, scripts, dynamic code loading or unrestricted HTTP handlers. Persist accepted work before returning success. Validate input and sanitize errors. Do not log payloads or credentials. New schema changes require a new Flyway migration; never rewrite an applied migration.

Document material decisions in `docs/adr/`, keep README and ARCHITECTURE accurate, and record observed verification results. Never invent benchmark numbers. Explain failed or unavailable checks in the pull request. Add dependencies only with a clear rationale.

Do not commit `.env`, private keys, build output or machine-specific settings. Publishing, pushing, deployment and infrastructure creation require the repository owner's explicit authorization. The implementation proceeds one phase at a time; see [phase checklist](docs/PHASE_CHECKLIST.md).
