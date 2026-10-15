# Security and threat model

DurableFlow is a local, single-trust-domain demonstration, not a production hosting service. API access is authenticated with two distinct high-entropy environment-provided bearer tokens. The operator role publishes/submits/queries; the admin role additionally cancels, manually retries and reads audit events. There are no individual accounts or tenant boundaries. Rotate tokens by regenerating local configuration and recreating the API; changing database initialization credentials does not update existing PostgreSQL accounts.

`python3 scripts/init-env.py` generates ignored secrets with private host-directory permissions. No default runtime passwords or API tokens exist in source. Test-only credentials are explicitly named as such. The API stores token digests for constant-time comparison, disables password/form/session authentication, and accepts credentials only through the Authorization header. CSRF protection is disabled because authentication is stateless bearer-header based; cookie authentication is not enabled. Outside loopback, TLS termination and managed identity integration are required before deployment.

## Trust boundaries and threats

| Threat | Implemented mitigation | Residual risk / deployment responsibility |
| --- | --- | --- |
| Malicious workflow payloads | Strict JSON, duplicate-key rejection, no scalar coercion, bounded nesting/string/number lengths; prepared SQL; immutable JSON snapshots | Authorized readers can query stored payloads; avoid embedding secrets |
| Enormous DAG denial | 1 MiB streamed/fixed request limit, 1,000 tasks, 10,000 edges, bounded identifiers/attempts/timeouts, bounded HTTP headers/timeouts | No per-tenant quotas or global admission budget; apply edge rate limiting before public exposure |
| Replay | Submission key uniqueness and semantic JSONB equivalence; idempotent cancellation and duplicate result reporting | A stolen bearer token remains usable until rotated; definition publication intentionally creates versions |
| Idempotency-key abuse | Authentication, 128-character bounded keys and indexed uniqueness; conflicting reuse rejected | Global key namespace; no retention or per-principal storage quotas yet |
| Worker impersonation | Workers are trusted processes on an internal Docker network, have unique UUIDs, registered capabilities and attempt/lease fencing; no public worker API | A database credential grants engine trust; future deployments need distinct roles and stronger workload identity |
| Unauthorized cancel/retry | Admin-only Spring Security rules; no query/body token authentication; transactional audit with controlled actor IDs | Shared admin credentials do not identify individual people; use an identity provider for real teams |
| Secret leakage in logs | No payload/header logging; sanitized error text; fixed identifier/status fields, ECS secret-field exclusions; failure-log regression test | Debugging third-party libraries can expose data if operators enable unsafe log settings |
| Arbitrary code execution | Four compiled allowlisted handlers; no shell, scripting, dynamic loading, unrestricted HTTP or polymorphic JSON typing | Adding a custom handler is a trusted code/deployment change, not an API action |
| Database credential compromise | Credentials outside Git/images; loopback-only host port; transactional constraints and append-only audit trigger | Initial Compose shares an owner credential, uses local-network plaintext JDBC and provides no encryption/HA/backups; compromise defeats engine integrity |
| Sensitive telemetry | W3C context plus whitelisted identifiers only; no payloads, exception messages or Authorization headers; bounded metric labels | Workflow/task IDs reveal activity patterns; protect collector, Prometheus and retained trace files |

Health probes expose aggregate status only. Sensitive actuator endpoints are denied. API metrics and OpenAPI require operator/admin credentials. Internal scheduler/worker health and metrics are unauthenticated on the trusted development network; worker ports are not host published. The scheduler and Prometheus UI bind to loopback. Collector OTLP has no host-published port. Docker access itself is privileged and must be trusted.

## Auditing and consistency

Cancellation and manual retry write append-only `audit_events` in the same transaction as state changes. Repeated cancellation does not create another action. `GET /api/v1/audit-events` is admin-only and paginated. Audit events store controlled actor/action values and target IDs, with UTC timestamps and no request payload. UPDATE/DELETE are rejected by a trigger; a database owner can bypass that trigger, so this is not tamper-proof external audit storage.

Request errors are stable problem responses with sanitized details. Handler exceptions expose a bounded category and fixed redacted message. Never pass a raw exception or task input to logging or span attributes.

## Dependency scanning

CI builds executable jars and uses Trivy for HIGH/CRITICAL vulnerability and secret scanning. Offline dependency identification scans resolved nested jars without extra Maven Central lookup traffic; vulnerability/Java index databases still update normally. The local scan initially found advisories in Boot 4.1.1's managed Tomcat 11.0.24 and Jackson 2.21.5/3.1.5. The root POM pins patched maintenance lines (Tomcat 11.0.26, Jackson 2.21.7/3.1.7) and the complete test suite verifies compatibility. No vulnerability ignore rules are used. Scan evidence and any remaining findings are recorded in VERIFICATION.md.

References: [Tomcat 11 security advisories](https://tomcat.apache.org/security-11.html), [Jackson 3.1.7 fixes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7), [Spring Security authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).

Report suspected vulnerabilities privately to the repository owner through GitHub's available security-reporting channel; do not include live tokens or sensitive payloads in public issues. This project does not promise a response-time SLA.
