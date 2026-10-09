# social-core

A reusable Java backend core for social networks and communities. The core has no family, university, alumni, employer, or other application-specific domain concepts.

**Current status: Phase 0 foundation implemented; social features are not implemented yet.** The application is named `social-core`, with root package `com.flyvo.socialcore`. The checkout directory may remain `fly-social`. See [Phase 0 evidence](docs/phases/phase-0.md). Architecture documents describe the target system; only completed phase records describe delivered capabilities.

## Start here

1. [Architecture and repository structure](docs/architecture.md): boundaries, dependency graph, transactions, conventions.
2. [Domain model](docs/domain-model.md) and [database design](docs/database-design.md): ownership, lifecycles, constraints and query contracts.
3. [Security](docs/security.md) and [API conventions](docs/api-conventions.md): authentication, privacy, permissions and resources.
4. [Feed](docs/feed.md), [media](docs/media.md), [events and jobs](docs/events-and-jobs.md), and [search and caching](docs/search-and-caching.md): subsystem designs.
5. [Testing](docs/testing-strategy.md), [operations](docs/operations.md), and [ADRs](docs/adr/README.md): verification, operation and decision rationale.
6. [Phased roadmap](docs/roadmap.md) and [HANDOFF TO IMPLEMENTATION MODEL](docs/implementation-handoff.md): execution instructions.

[AGENTS.md](AGENTS.md) applies to subsequent implementation. [Blueprint coverage](docs/blueprint-index.md) maps the requested deliverables to their authoritative documents.

## Baseline

One Maven Spring Boot application, Java 21, PostgreSQL, Flyway, Spring Data JPA/Hibernate, Spring MVC REST, Docker Compose, Testcontainers and ArchUnit. Public API prefix: `/api/v1`. Production is a modular monolith. An HTTP process and a worker process may later run from the same artifact.

The build pins Java 21, Boot 4.1.1 and Maven Wrapper 3.9.16. PostgreSQL 18.6 is pinned by digest in Compose and Testcontainers. Use Boot-managed dependency versions, including Hibernate, Flyway, Jackson and JUnit. No Redis, message broker or dedicated search engine is required initially.

One deliberate adjustment to the requested testing defaults: Boot 4 manages JUnit 6; retain its supported Jupiter stack (the familiar `org.junit.jupiter` programming model) instead of forcing a JUnit 5 dependency downgrade. All testing requirements otherwise apply unchanged. See [ADR-001](docs/adr/001-runtime-and-modularity.md) and [Spring Boot's testing documentation](https://docs.spring.io/spring-boot/reference/testing/index.html).

## Run locally

Prerequisites: JDK **21** (set JAVA_HOME appropriately), Docker Engine with Compose v2+ and permission to access Docker. The Maven wrapper downloads Maven/dependencies on first use. Tests require Docker; no local database installation is needed.

```bash
docker compose up -d --wait postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The local profile uses `jdbc:postgresql://localhost:5432/social_core`, user `social_core`, and an explicitly local-only password matching Compose. No local profile is selected automatically. To customize Compose, copy `.env.example` to `.env`; `.env` is ignored by git and is read by Compose, **not** by Spring Boot. When changing the database port/user/password for a host-run app, set matching `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables. Production/default profile has no credential fallback and fails validation if these values are missing.

To build and run the entire local stack in containers:

```bash
./mvnw verify
docker compose --profile app up --build -d --wait
curl --fail --retry 10 --retry-connrefused --retry-delay 1 -i http://localhost:8080/actuator/health/readiness
```

The app service is opt-in, runs as UID/GID 10001 with a read-only root filesystem, uses `/tmp` tmpfs, and connects to the Compose database internally. Published ports bind to localhost only. `POSTGRES_PORT` and `APP_PORT` customize host ports. Database data lives in a named volume. `docker compose --profile app down` stops this stack and preserves the database; add `--volumes` only when intentionally discarding local data.

Host JVM runs via the Maven plugin and the container entrypoint use UTC. For a standalone jar use `java -Duser.timezone=UTC -jar target/social-core-0.0.1-SNAPSHOT.jar` with database environment variables. Persistence independently enforces UTC even when the caller's JVM uses another timezone.

## Verify changes

```bash
./mvnw verify             # formatter, Enforcer, unit/architecture tests, real PostgreSQL ITs
./mvnw spotless:apply     # apply Google Java Format before committing
docker compose config --quiet
```

Surefire runs `*Test`; Failsafe runs `*IT`. Testcontainers starts an isolated PostgreSQL container with random host ports and removes it after tests; tests never use the Compose database. Missing/unavailable Docker causes failure rather than skipped integration tests. Maven Enforcer checks Java/Maven versions and dependency convergence. Spotless uses Google Java Format 1.28.0, verified with Java 21; formatter upgrades must be tested against this JDK.

GitHub Actions runs the same verify gate, validates Compose, builds the image after verification, scans git history for secrets, and uploads test reports. Actions and container images are pinned to immutable revisions/digests. Dependabot schedules dependency/action/container update PRs. Docker's packaging stage skips tests because it has no Docker daemon for Testcontainers; it is **not** a replacement for the host/CI `verify` gate.

## Implemented operational surface

| Request | Behavior |
|---|---|
| `GET /actuator/health` | Aggregate status only; no database/component details |
| `GET /actuator/health/liveness` | Process availability, independent of database health |
| `GET /actuator/health/readiness` | Application availability plus PostgreSQL connectivity; database loss returns 503 |
| Other GET requests | 401 Problem Details; no generated password, Basic auth or login form |
| Unsafe methods without CSRF proof | 403 Problem Details |

These three health endpoints are the only public routes. Authenticated requests are also denied by the shell until explicit Phase 1 rules replace it. `/actuator/prometheus` is configured but protected, pending an actual operational authentication mechanism; never open it publicly to make scraping convenient. Micrometer collects metrics. Business/authentication endpoints, credentials, roles, outbox and domain tables do not exist yet. [OpenAPI](docs/api/openapi.yaml) contains only implemented health routes and the error schema; Actuator health failures use its status representation, not business Problem Details.

Responses carry `X-Request-ID`; safe incoming IDs up to 64 ASCII characters are retained and invalid IDs are replaced. Structured JSON logs include requestId from MDC during request handling. Errors expose stable code/requestId and sanitized details, not rejected values or exception messages. JDBC connect/read timeouts are five seconds, pool acquisition five seconds, and readiness is intentionally separate from liveness.

Flyway `V0001__platform_foundation.sql` creates only the `platform` schema. Hibernate validates schema, OSIV is off, automatic SQL initialization is disabled, and Flyway clean is disabled. Future business tables belong to later phase migrations. The same database role runs migrations and application locally; production must separate migration/runtime roles before launch as described in [operations](docs/operations.md).

## Troubleshooting

- `app.database` validation failure: select the local profile or provide all three DB environment variables. Do not add production password defaults.
- Port already allocated: change Compose `POSTGRES_PORT`/`APP_PORT`; update the host-run app's DB_URL to match.
- Testcontainers cannot find Docker: check `docker info`, socket permissions and supported Docker context/DOCKER_HOST. Do not skip integration tests or enable container reuse to hide a failure.
- Spotless failure: run `./mvnw spotless:apply`, review the diff, then `./mvnw verify`.
- Java version failure: use JDK 21 for both IDE Maven runner and command line.
- JDBC timeout after pausing PostgreSQL: expected; restore database connectivity and readiness recovers. Increasing pool size will not fix an unavailable database.

The original repository contained only the Boot starter. Phase 0 intentionally renames/replaces that bootstrap and its context-only test; unrelated user changes and the git index are preserved. Completed, verified tasks are now committed locally under the standing authorization in [Git workflow](docs/git-workflow.md). Push and deployment require separate authorization.
