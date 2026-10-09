# Phase 0 — Foundation evidence

Date: 2026-10-08. Status: complete. Scope: repository foundation only; no Phase 1 business/authentication implementation.

## Delivered

- Java 21, existing Boot 4.1.1 and Maven Wrapper 3.9.16 retained; artifact/package renamed to `social-core` / `com.flyvo.socialcore`.
- Boot 4 MVC, Validation, Security, Data JPA, Flyway/PostgreSQL, Actuator and Micrometer Prometheus dependencies. Boot manages library versions; Enforcer verifies dependency convergence.
- Validated typed database configuration with no default-profile secrets, explicit local profile, UTC JDBC session/Hibernate settings, OSIV off and schema validation only.
- Migration `V0001__platform_foundation.sql`: creates platform namespace only. No speculative domain or event tables.
- Security shell allows only the three GET health routes; other requests denied, cookie-backed CSRF retained without server sessions, Basic/form/logout disabled, auto-generated user disabled. CSRF cookie is host-only/SameSite Strict, readable by the frontend and Secure on HTTPS. Authentication and its complete CSRF/Origin policy arrive in Phase 1.
- Request ID validation/propagation/MDC cleanup; JSON logging; shared Problem Details for filter denials and controller exceptions; validation/parser/unexpected errors redact request values and exception messages.
- Separate database-backed readiness and database-independent liveness. Bounded JDBC connect/socket timeout prevents health requests hanging indefinitely on a paused database.
- PostgreSQL 18.6 Compose service and Testcontainers image pinned to the same digest; opt-in application container, non-root UID/GID 10001, private host ports, read-only application filesystem/tmpfs. Temurin build/runtime images pinned by digest.
- Spotless/Google Java Format, Surefire/Failsafe, ArchUnit module DAG/API-only boundaries, basic SQL/migration boundary checks, GitHub Actions, Dependabot and Gitleaks secret scan.
- Implemented-only OpenAPI, local run/troubleshooting instructions and handoff status updated.

## Tests and evidence

The Maven suite includes 15 unit/architecture tests and 8 PostgreSQL integration tests; no integration tests are disabled or skipped.

| Check | Result |
|---|---|
| `./mvnw -B -ntp verify` | Passed: 23 tests, 0 failures/errors/skipped; formatting, Enforcer and dependency convergence passed |
| `docker compose config --quiet` | Passed |
| `docker build --tag social-core:phase0 .` | Passed using the pinned Temurin build/runtime images |
| Isolated Compose smoke test (`social-core-phase0-smoke`, ports 55432/18080) | Three health routes return 200 without components; request ID echoed; protected GET returns 401 Problem Details; runtime UID is 10001; UTC JSON logging; no generated security password |
| Pinned Gitleaks 8.28.0 `dir /repo --redact --no-banner` | Passed: no leaks in the current working tree |
| YAML parsing and documentation links | Checked locally; no broken file links |
| `git diff --check` | Passed; existing staged index left unchanged |

The smoke test uses an isolated Compose project and removes only its own containers/network/volume afterward. Normal developer infrastructure is not started or removed. GitHub Actions has not been executed remotely; its local equivalents are verified. Secret scanning uses directory mode locally because the starter has no committed history yet; CI scans committed history after push.

The test suite checks invalid/missing configuration, unsafe request IDs/MDC cleanup, sanitized validation/errors, a deliberate cross-module repository violation, module cycles, transport/persistence boundaries, migration version/ownership, forbidden SQL references, initial/repeated Flyway migration, real PostgreSQL version/UTC timestamp behavior, Hibernate refusal to create a missing mapped table, health privacy, default denial/CSRF and paused-database readiness/liveness recovery.

## Concrete refinements and limits

- Google Java Format 1.37.0 failed on the project's Java 21 compiler interface during verification; 1.28.0 is pinned and tested instead. No formatter check was suppressed.
- Testcontainers' image parser rejected a combined tag-plus-digest string. Tests use `postgres@sha256:...`, while Compose uses tag plus the identical digest. Runtime tests assert PostgreSQL major 18; container startup reported 18.6.
- An outage test exposed unbounded JDBC reads despite the pool timeout. Five-second driver connect/socket timeouts now bound readiness dependency checks. The regression test pauses only its own disposable container and unpauses it in a finally block.
- There is no previous released application schema to upgrade from. Phase 0 verifies empty-database migration, checksum validation and no-op remigration; real prior-release upgrade fixtures begin once a prior release exists.
- No application entities exist. A test-only mapping outside component/entity scan proves Hibernate `validate` rejects an absent table without creating it; it does not claim business mappings have been tested.
- Prometheus exporter is wired but the HTTP endpoint remains denied until operational authentication is added. Phase 0 does not add a temporary insecure credentials scheme.
- Docker packaging skips tests; host/CI `verify` is the mandatory preceding gate. CI is authored and locally equivalent commands are checked; no remote GitHub Actions run is claimed before push.
- SQL boundary checks cover conventional static schema-qualified references, not arbitrary SQL construction. No actual public SQL views exist at Phase 0.
- UTC runtime is set by Docker entrypoint and Maven run configuration; standalone jar command documents the same JVM flag. Database persistence independently enforces UTC.

## Next phase

Phase 1: secure accounts/authentication and durable email/jobs/outbox. Before parallel implementation, define shared account/profile APIs, migration allocation, registration transaction and platform durability contracts. Preserve deny-by-default routing while opening only the explicitly implemented auth endpoints. Do not add media/community/graph tables ahead of their phases.
