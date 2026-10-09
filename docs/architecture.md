# Architecture and repository structure

Status: accepted design baseline, 2026-10-08. Read with [ADRs](adr/README.md). This is a plan, not an assertion of implemented behavior.

## Style and scope

Use a domain-oriented modular monolith: one build, deployment artifact, PostgreSQL database and local transaction manager. Package boundaries and architecture tests enforce ownership. Extraction is possible later, but local ACID transactions and selected published SQL read contracts intentionally take advantage of today's monolith. Extraction will require replacing those contracts; it is not a configuration switch.

Do not build a reusable framework or plugin engine. Reusability means neutral concepts, cohesive modules, documented policies, configuration at actual seams, and readable code that another team can fork. A community is an authorization scope, not a SaaS tenant. A separate tenant hierarchy, genealogy, chat, billing, federation and end-to-end encryption are outside this baseline.

Use Java 21, the existing Boot 4.1.1 baseline, Maven Wrapper, Spring MVC, Spring Security, JPA/Hibernate, PostgreSQL 18 and Flyway. Resolve actual dependency compatibility in Phase 0; do not copy Boot 3 starter names blindly. Java 21 is compatible with the documented Boot baseline. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

## Module ownership

| Module | Owns | Published contracts (illustrative names, required responsibilities) |
|---|---|---|
| `account` | Stable user ID, account status/lifecycle, platform role | `AccountCommands`, `AccountQueries`, current status facts; no passwords/profile |
| `identity` | Local credential, verified login email, external identity, sessions, OAuth, verification/recovery; self-account closure orchestration | Authentication HTTP endpoints, `SessionQueries`, authenticated principal construction |
| `profile` | Handle, display name, bio, avatar, profile privacy, social preferences | `ProfileCommands`, viewer-aware `ProfileQueries` and batched summaries |
| `community` | Community settings, membership, owner transfer, invitations, join requests, bans | `CommunityCommands`, `MembershipQueries`, permission facts and write guards |
| `graph` | Follow, friend request, friendship, block, mute | `GraphCommands`, `RelationshipQueries`, interaction guards |
| `content` | Post, audience, revisions, mentions, hashtags, two-level comments and reactions | `ContentCommands`, `ContentQueries`, moderation commands, authorized media resolution |
| `media` | Upload, validation, variants, binding and deletion lifecycle | `MediaCommands`, `MediaQueries`; object storage and processor ports |
| `feed` | Candidate orchestration, ranking, cursors; later inbox projections | `FeedQueries`; no independent visibility authority |
| `notification` | Recipient inbox, semantic aggregation, read state, delivery preferences, SSE | `NotificationQueries`, read commands and event consumers |
| `search` | Search documents/projections, ranking and search API | `SearchQueries`; rechecks source authorization |
| `moderation` | Reports, case resolution, attributable moderation actions/evidence | `ReportCommands`, `ModerationCommands`; invokes owning module commands |
| `platform` | Technical security adapters, errors, clock/IDs, outbox/jobs, mail transport, audit, telemetry, idempotency | Narrow technical helpers; no domain policy or generic business service |

Comments/reactions start as internal subdomains of `content`. They require identical post visibility and atomic target checks; separate modules would add cycles and coordination without meaningful independence. `identity` and `account` remain separate: a banned user does not disappear when a provider identity is removed.

## Allowed compile-time dependencies

Arrows mean “may call published API of.” All modules can use the small `platform.api` contracts; platform implementation never imports business modules. Authentication adapters that need account/session APIs live in identity infrastructure, not platform.

```mermaid
flowchart TD
  identity --> account
  identity --> profile
  identity --> community
  profile --> account
  profile --> graph
  profile --> media
  graph --> account
  community --> account
  media --> account
  content --> account
  content --> graph
  content --> community
  content --> media
  content --> profile
  feed --> content
  feed --> graph
  feed --> community
  feed --> profile
  notification --> content
  notification --> community
  notification --> graph
  notification --> profile
  notification --> account
  search --> content
  search --> community
  search --> profile
  search --> account
  moderation --> content
  moderation --> community
  moderation --> account
```

This is an allowlist, not a requirement to inject every API. `identity -> profile` is solely initial profile provisioning in registration; `identity -> community` is solely the ownership-blocker check during self-account closure. `identity.application.CloseAccount` performs recent reauthentication, takes the account lifecycle guard, checks active-community ownership through the community API, and invokes account's deletion-request command in one transaction. Account itself never depends on community/identity. Search may consume account lifecycle events for erasure. A module must not call back into a module above it, including through callbacks disguised as ports. Event types live in the publisher's `api.event`; consumers import those types in their application layer. Account status changes need no reverse call into identity: authentication always checks current account status. Owner-module deletion jobs are driven by account lifecycle events.

No module imports another module's JPA entities, domain objects, application implementations or repositories. A module API uses immutable records, enums and scalar IDs, never `Page`, `Specification`, lazy collections, `EntityManager` or Spring HTTP types. Java UUID is sufficient initially; introduce typed IDs only where repeated confusion proves useful.

Content may call `ProfileQueries.attributionSummaries` only after authorizing the containing content. This narrow internal contract returns ID/display name for attribution even when the full profile is private; avatar/bio/graph access still uses profile policy. It is not a public user-directory endpoint. Feed/notification consumers follow the same source-before-attribution ordering.

## Within a module

`web -> application -> domain`; application also uses owned ports and other modules' APIs. Infrastructure implements owned ports. Domain may use JDK and Jakarta Persistence annotations as a pragmatic exception: a behavior-bearing aggregate may be its JPA model. It may not use Spring services, SecurityContext, HTTP, Jackson, repositories or another module's internals. Separate persistence/domain models only for a demonstrated mismatch; do not map everything twice mechanically.

Repository ports belong to `application.port` and return owned aggregates/projections. Spring Data repositories and adapters stay in `infrastructure.persistence`. Create an interface only for an actual boundary (persistence, provider, storage, clock, asynchronous dispatch), or an exported module API. Use concrete application use cases named for capabilities, such as `PublishPost` or `TransferCommunityOwnership`; do not make one class per trivial getter.

Exported `api` contracts describe commands/queries and events. They are distinct from HTTP DTOs in `web`. Keep constructor injection, explicit mutation methods, small records for values/DTOs, package-private implementations where possible. No Lombok by default, no field injection, generic BaseService/BaseController, automatic entity serialization, hidden cascade across aggregates, or `ServiceImpl` ceremony.

## Transactions and consistency

One application command owns the transaction. Published synchronous commands join `REQUIRED`; do not use `REQUIRES_NEW` to hide partial business success. Multiple aggregates may participate when a real invariant needs it: registration (account + identity + initial profile), accepting membership, attaching media, and executing a moderation action with its audit record. No network/object-storage calls while holding business locks. Prepare outside a transaction, perform a short guarded commit, schedule external effects durably.

Default isolation is PostgreSQL READ COMMITTED plus constraints and deliberate locks. Reads use current authoritative database facts, batched in a short read transaction. Do not claim serializable snapshots across pages or revoke bytes already delivered. For strict conflicting mutations use shared/exclusive guards described in [database design](database-design.md). Permission is evaluated again inside the write transaction.

Synchronous: authenticate, authorize, validate state, enforce uniqueness, change membership/graph/content, bind media, persist outbox and mandatory audit. Asynchronous: email delivery, image processing, notification projection, search indexing, cleanup, analytics and future feed fan-out. An in-memory after-commit event may wake a worker, but never be the only durable record of required work.

## Read contracts without an accidental distributed join

Batch module APIs are the default. High-cardinality feed/content queries cannot ship every followed user ID into Java or do a per-row membership call. Owners therefore publish a small, versioned set of **read-only SQL views** in `account_api`, `graph_api`, `community_api` schemas. Content query infrastructure may join these documented views; it must never join their private base tables. Views expose only IDs, status and relationship facts, never credentials or profile PII. They reflect committed base state without async projection lag.

These are explicitly sanctioned module APIs, with SQL source ownership, compatibility tests and an allowlist in architecture tests. Only content's query adapter consumes these views initially. Other modules call `ContentQueries`. JPA is the default persistence tool; named native SQL/JDBC is appropriate for keyset queries, view joins, outbox claims, and UPSERT. Keep SQL inside an owning infrastructure adapter. Views alone do not authorize results; content applies the canonical policy. Service extraction must replace a published view with another deliberate consistency contract.

## Proposed repository tree

The files below are the **target**, not all files created by this documentation task. Keep one Maven module initially.

```text
social-core/                         # existing checkout directory may remain fly-social
├── pom.xml                          # group com.flyvo, artifact social-core
├── mvnw, mvnw.cmd, .mvn/wrapper/
├── .github/workflows/ci.yml
├── .github/dependabot.yml
├── .editorconfig
├── .env.example                     # placeholders only
├── .gitignore
├── Dockerfile                       # non-root runtime, multi-stage build
├── docker-compose.yml               # postgres; mail/media profiles added by phase
├── README.md
├── AGENTS.md
├── docs/
│   ├── blueprint-index.md
│   ├── architecture.md
│   ├── domain-model.md
│   ├── database-design.md
│   ├── api-conventions.md
│   ├── security.md
│   ├── feed.md
│   ├── media.md
│   ├── events-and-jobs.md
│   ├── search-and-caching.md
│   ├── testing-strategy.md
│   ├── operations.md
│   ├── roadmap.md
│   ├── implementation-handoff.md
│   ├── adr/                         # numbered decisions + index
│   ├── phases/                      # completion evidence, created during implementation
│   └── api/openapi.yaml             # implemented contract, grows phase by phase
├── ops/                             # runbooks/config only when introduced
│   ├── local/
│   └── dashboards/
└── src/
    ├── main/
    │   ├── java/com/flyvo/socialcore/
    │   │   ├── SocialCoreApplication.java
    │   │   ├── account/
    │   │   ├── identity/
    │   │   ├── profile/
    │   │   ├── community/
    │   │   ├── graph/
    │   │   ├── content/
    │   │   ├── media/
    │   │   ├── feed/
    │   │   ├── notification/
    │   │   ├── search/
    │   │   ├── moderation/
    │   │   └── platform/
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml
    │       └── db/migration/         # globally ordered Flyway SQL, owner in filename
    └── test/
        ├── java/com/flyvo/socialcore/
        │   ├── architecture/
        │   ├── support/             # fixtures, containers, fake providers
        │   └── <module>/            # domain, application, persistence, web tests
        └── resources/application-test.yml
```

Example content package (create only classes used by the current phase):

```text
content/
├── api/{ContentCommands,ContentQueries,PostSummary,ContentAccessFacts}.java
├── api/event/{PostCreated,CommentCreated,ReactionChanged}.java
├── web/{PostController,CommentController,ReactionController}.java
├── web/dto/{CreatePostRequest,PostResponse}.java
├── application/{PublishPost,EditPost,AddComment,ChangeReaction}.java
├── application/policy/{ContentAccessPolicy,ContentWritePolicy}.java
├── application/port/{PostRepository,CommentRepository}.java
├── domain/post/{Post,PostAudience,PostRevision}.java
├── domain/comment/Comment.java
├── domain/reaction/{Reaction,ReactionType}.java
└── infrastructure/persistence/{JpaPostRepository,PostRepositoryAdapter,ContentReadQueries}.java
```

## Architecture enforcement

ArchUnit tests enforce the dependency allowlist, no module cycles, no cross-module internals, no web imports from domain/application, no field injection, and no entity in public API/controller signatures. Platform must not depend on business modules. Package annotations or a small test-owned map identify modules; Spring Modulith is optional later, not an extra required framework now.

SQL/resource checks enforce the documented published-view allowlist and migration ownership. These checks complement review; static Java checks cannot discover arbitrary SQL strings. Contract tests verify view columns and policy parity. Ban cross-module JPA associations even when there is a database FK.
