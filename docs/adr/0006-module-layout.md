# 0006: Keep modules aligned with responsibilities

Status: Accepted

Date: 2026-10-15

## Context

API, scheduling, execution and database behavior need clear boundaries without empty modules for every future concern.

## Decision

Use model, persistence, API, scheduler, worker and integration-tests modules. Keep shared database settings and migrations in persistence. Add observability, examples, benchmark and fault-test directories when they contain real behavior. Use Spring Boot 4.1.1 on Java 21, Maven, Google Java Format via Spotless and SpotBugs. Override Boot's JUnit BOM to 5.14.4 to honor the requested JUnit 5 constraint; tests use standard Jupiter APIs and programmatic application contexts. Do not add Spring Framework 7's SpringExtension: it requires JUnit 6. Depend directly on Jupiter and AssertJ rather than pulling in the incompatible Spring test extension and unused mocking libraries.

## Consequences

Three deployable applications remain separately testable. Each keeps a normal jar and an exec-classified executable jar so integration tests can depend on application classes. Shared persistence means coordinated schema compatibility is required. Avoid a separate observability module until there is shared instrumentation to own.

## Compatibility references

- [Spring Boot stable releases](https://docs.spring.io/spring-boot/)
- [Spring Boot Java requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Framework 7 SpringExtension requires JUnit 6](https://www.springframework.org/spring/docs/7.0.5/javadoc-api/org/springframework/test/context/junit/jupiter/SpringExtension.html)
