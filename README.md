# social-core

A reusable Java backend core for social networks and communities. The core has no family, university, alumni, employer, or other application-specific domain concepts.

**Current status: Phases 1–4 accepted; Phase 5 interactions/reports implemented pending verification/review.** Phase 6 feeds are not implemented yet. Production readiness remains separately gated. The application is `social-core`, root package `com.flyvo.socialcore`; the checkout may remain `fly-social`. See [Phase 5 evidence](docs/phases/phase-5.md), [Phase 4 evidence](docs/phases/phase-4.md), [content](docs/content.md), [moderation](docs/moderation.md), [community](docs/community.md), [graph](docs/graph.md) and [media](docs/media.md). Architecture documents describe the target; phase records describe delivery.

## Start here

Phase 4 exposes bounded post publication/query/history, current-policy variant access
and versioned avatar replacement. [Local storage operations](ops/media.md) explains
enabling the real Silo pipeline; there is no fake storage fallback.

Phase 5's content slice adds bounded roots/replies/tombstones, versioned own edits/
deletion, typed stable reactions and viewer-filtered counts. Current parent privacy
and community write membership apply to every path; [OpenAPI](docs/api/openapi.yaml)
documents the exact surface. V0022 adds interactions and V0023 adds reports without
changing V0001–V0021. Reports support all four owner-proven target types, encrypted
private evidence, scoped staff/global ADMIN queues and atomic actions/resolution.
Feeds are not advertised as complete.

For sequential development/review threads, read the fixed [agent handoff](docs/agent-handoff.md) and [thread workflow](docs/thread-workflow.md).

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
bash ops/local/generate-keys.sh
docker compose up -d --wait postgres mailpit
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The local profile uses `jdbc:postgresql://localhost:5432/social_core`, user `social_core`, and an explicitly local-only password matching Compose. No local profile is selected automatically. To customize Compose, copy `.env.example` to `.env`; `.env` is ignored by git and is read by Compose, **not** by Spring Boot. When changing the database port/user/password for a host-run app, set matching `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables. Production/default profile has no credential fallback and fails validation if these values are missing.

To build and run the entire local stack in containers:

```bash
./mvnw verify
APP_UID="$(id -u)" APP_GID="$(id -g)" docker compose --profile app up --build -d --wait
curl --fail --retry 10 --retry-connrefused --retry-delay 1 -i http://localhost:8080/actuator/health/readiness
```

The app service is opt-in; the image defaults to UID/GID 10001. Local Compose can use the non-root developer UID/GID above to read its mode-0600 mounted keys. It retains a read-only root filesystem and `/tmp` tmpfs. Ports bind to localhost; `POSTGRES_PORT`, `APP_PORT`, `SMTP_PORT` and `MAILPIT_PORT` customize ports. PostgreSQL data uses a named volume. `docker compose --profile app down` preserves it; add `--volumes` only when intentionally discarding data.

Local keys live in ignored `.local/secrets`, survive restarts and must be retained with the local database. The generator never replaces existing keys. Production requires externally mounted signing, mail-encryption and limiter keys; see [authentication operations](ops/identity.md). Ephemeral keys are for tests. Mailpit's inbox is at `http://localhost:8025`; the local frontend origin defaults to `http://localhost:3000`, configurable with `FRONTEND_ORIGIN`. This backend does not implement a frontend.

Browser flow: fetch `GET /api/v1/auth/csrf` with credentials; send its `X-XSRF-TOKEN` header, cookie and exact Origin on unsafe requests. Request `/auth/registrations` with email only. The captured email contains a fragment-bound one-use proof; complete `/auth/registrations/complete` with proof and a newly chosen password in the initiating browser. Keep the returned access token in memory; refresh is a host-only HttpOnly cookie. Serialize refresh across tabs: concurrent/lost-response retries revoke the whole family. Auth endpoints, own-session management and basic profile/privacy routes are in [OpenAPI](docs/api/openapi.yaml).

Google and Facebook are disabled until configured. New provider subjects still require our recovery-mailbox proof, and matching email never links an account. Facebook additionally requires recorded official-contract approval before enablement; live provider smoke tests and Meta review/callback requirements remain deployment gates.

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
| Implemented Phase 1–4 routes | Explicit auth/privacy/scoped-role rules in OpenAPI; current PostgreSQL account/session/graph/community/content checks |
| Unsupported GET requests | 401 without authentication, 403 with it; deny by default |
| Unsafe methods without CSRF proof | 403 Problem Details |

Health probes and explicitly implemented auth/onboarding and visible-profile routes have public entry points; unsafe API calls require CSRF and Origin even with Bearer authentication. An invalid supplied Bearer never becomes anonymous. PostgreSQL failure on authenticated requests is 503. `/actuator/prometheus` remains denied pending operational authentication. Mail outage does not fail health/readiness: jobs retry and expose queue/failure metrics. Actuator failures retain its status representation; business/filter errors use Problem Details.

Responses carry `X-Request-ID`; safe incoming IDs up to 64 ASCII characters are retained and invalid IDs are replaced. Structured JSON logs include requestId from MDC during request handling. Errors expose stable code/requestId and sanitized details, not rejected values or exception messages. JDBC connect/read timeouts are five seconds, pool acquisition five seconds, and readiness is intentionally separate from liveness.

Flyway `V0001`–`V0014` remain unchanged; `V0015`–`V0021` add post/revision/audience/media schemas, avatar and integrity guards. Hibernate validates account/profile mappings; owned JDBC adapters share the same local transaction manager. OSIV and auto-DDL remain off. Production must separate migration/runtime roles before launch. Phase 4 implements post/privacy/history/scoped moderation, upload/finalize/status/deletion, source-authorized images and avatar routes; Silo is opt-in and disabled storage fails closed. Phase 4 is accepted after independent main review. No Redis or broker is introduced.

Graph flow: PUT/DELETE `/api/v1/me/following/{user}`, `/me/blocks/{user}` and `/me/mutes/{user}` manage distinct edges. POST `/friend-requests` with userId, then the addressee accepts/declines or requester cancels through its purpose-specific route. Block atomically removes conflicting relationships; unblock restores nothing. Profile FRIENDS uses current friendship; signed-in blocks override all scopes. Lists in [OpenAPI](docs/api/openapi.yaml) use bounded pages and opaque cursors; continue until nextCursor is null, including empty filtered pages. See [graph operations](ops/graph.md).

Community flow: create PRIVATE/INVITE_ONLY by default, or choose visibility and OPEN/REQUEST/INVITE_ONLY. Staff manage invitations/requests; join grants MEMBER only. OWNER transfers atomically to an effective member or archives before leaving/closing an account. Use community ETag for settings/transfer/archive and target membership ETag for roles/removal/sanctions. Invitation expiry is enforced live and durably scheduled with the existing worker. [Community operations](ops/community.md) describes retry, bounds and launch gates.

## Troubleshooting

- `app.database` validation failure: select the local profile or provide all three DB environment variables. Do not add production password defaults.
- Port already allocated: change Compose `POSTGRES_PORT`/`APP_PORT`; update the host-run app's DB_URL to match.
- Testcontainers cannot find Docker: check `docker info`, socket permissions and supported Docker context/DOCKER_HOST. Do not skip integration tests or enable container reuse to hide a failure.
- Spotless failure: run `./mvnw spotless:apply`, review the diff, then `./mvnw verify`.
- Java version failure: use JDK 21 for both IDE Maven runner and command line.
- JDBC timeout after pausing PostgreSQL: expected; restore database connectivity and readiness recovers. Increasing pool size will not fix an unavailable database.

The original repository contained only the Boot starter. Phase 0 intentionally renames/replaces that bootstrap and its context-only test; unrelated user changes and the git index are preserved. Completed, verified tasks are committed locally under [Git workflow](docs/git-workflow.md). Only main may push reviewed checkpoints; development never pushes. Deployment is not authorized.
