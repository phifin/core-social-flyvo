# Implementation roadmap

Execute one coherent phase at a time. This roadmap is ordered by prerequisites, not a promise that every phase has equal duration. Within a phase deliver vertical slices and keep `verify` passing. Do not scaffold empty future modules/entities or implement later infrastructure prematurely. All phases inherit [testing requirements](testing-strategy.md), [AGENTS.md](../AGENTS.md) and [handoff rules](implementation-handoff.md).

Record the phase's actual migrations, module APIs, test commands/results, measured limits and remaining external gates in `docs/phases/phase-N.md`. A phase is not complete while its required acceptance tests fail or are skipped. External provider/storage smoke checks may remain explicitly deployment-blocking even after local implementation passes; never mislabel that as fully production-ready.

## Phase 0 — Buildable architecture foundation

Implementation status: [Phases 0–3](phases/phase-3.md) delivered locally, Phase 3 accepted after independent main review. [Phase 4](phases/phase-4.md) is accepted. [Phase 5](phases/phase-5.md) comments/reactions/reports and [Phase 6](phases/phase-6.md) chronological feeds are accepted by main on 2026-10-14; exact verification/benchmark evidence is recorded separately. Reports follow accepted [ADR-014](adr/014-report-user-target-proof.md). [Phase 7](phases/phase-7.md) and [Phase 8](phases/phase-8.md) are accepted by main on2026-10-17. Phase9A-I is accepted; [II](phases/phase-9a-ii.md) is accepted at G2 on2026-10-24, executable `8df0b6b`, isolated synthetic scope only. III remains unactivated.
III/restore and production remain unimplemented/unapproved. Current routing/review state is in the [mailbox](agent-handoff.md). External provider/production readiness is separately gated.

**Goal:** establish a reproducible repository that enforces the accepted design before business code exists.

**Dependencies:** blueprint only; preserve existing user edits. **Modules:** application bootstrap, platform, architecture test package. **Features:** rename artifact/application/package to social-core/com.flyvo.socialcore; retain Java 21 and verify Boot 4.1.1/Maven wrapper compatibility; Spring MVC, validation, security deny-by-default shell, Data JPA, PostgreSQL driver, Flyway PostgreSQL support, Actuator. Configure OSIV off, Hibernate validate, UTC, typed configuration, request ID and Problem Details conventions. No invented business endpoints.

**Database changes:** initial Flyway migration creates platform schema and minimal foundation metadata only as actually needed; prove migrations run and Hibernate validates against PostgreSQL. Future tables are not created as empty promises. **Infrastructure:** Docker Compose PostgreSQL 18 with pinned patch/image, non-root application Dockerfile, Maven Enforcer/Spotless, Surefire/Failsafe, Testcontainers, ArchUnit, GitHub Actions. Provide `.env.example` and exact local commands; no Redis/object storage.

**Tests required:** context starts with real PostgreSQL, health/readiness, security rejects unknown protected routes, request ID/error serialization, all boundary rules and test pipeline. Verify architecture rules detect a deliberately forbidden dependency via test fixture (do not leave a violation in source).

**Acceptance:** clean checkout plus documented Docker/JDK prerequisites can run `./mvnw verify`; CI invokes the same checks; no auto-DDL, H2 or secrets; formatter detects drift; startup errors explain missing required configuration. README distinguishes runnable foundation from unimplemented product.

**Edges:** unavailable Docker must fail integration checks, not skip; timezone defaults cannot change persisted instants; bootstrap package scan remains correct after rename; production health details protected. Verify supported JUnit Jupiter version and document intentional Boot-managed JUnit 6 deviation rather than quietly pinning incompatible JUnit 5.

## Phase 1 — Secure accounts and durable authentication

**Goal:** a user can prove an email, create an account, sign in locally or via configured providers, manage sessions and recover safely.

**Dependencies:** Phase 0. **Modules:** account, identity, initial profile, platform mail/audit/outbox/jobs. **Features:** two-step local registration with password selected at completion; default profile provisioning; local login; access JWT + DB session check; strict refresh rotation/replay revocation; logout/current/all sessions; verification/reset/email change/password change; Google/Facebook adapters; explicit link/unlink with reauth; safe collision UX. Own/basic profiles support PUBLIC/AUTHENTICATED/PRIVATE now; FRIENDS settings are enabled in Phase 3. Account suspend/deletion-request primitives deny access; full erasure is Phase 9A.

**Database changes:** account, login email, credential, external identity, registration intent, sessions/token generations, OAuth transaction, one-time token, profile/preferences, platform audit, jobs/encrypted mail payload, outbox/deliveries/receipts/idempotency tables as used. Initial profile contains no media FK. **Infrastructure:** local Mailpit SMTP sink, cryptographic signing/encryption key configuration, provider HTTP adapters/fake server tests, small PostgreSQL worker with leases/retry/dead handling, bounded local rate limits. Production email/provider credentials remain external configuration. No broker/Redis.

**Tests required:** all identity collision table scenarios; token signature/issuer/audience/expiry; CSRF/cookie/CORS; expiry boundaries; concurrent refresh replay revokes the committed family; duplicate email/provider callback; no account pre-hijack; last-method unlink denied; email delivery retry/secret redaction; outbox rollback/duplicate/lease recovery; account suspension invalidates an unexpired JWT.

**Acceptance:** local verification/reset flow works through captured email, two browsers have independent revocable sessions, matching provider email never auto-links, every account has exactly one initial profile, duplicate callback creates no partial user, logout blocks next authenticated request. Provider adapters have deterministic contract tests; document real provider setup/approval smoke-test gates. All auth responses/cache/logs exclude secrets appropriately.

**Edges:** missing Facebook email, changed Google email, expired callback, existing OAuth-only email during local registration, lost refresh response forces re-login, failure response must not roll back revocation, unknown emails resist enumeration. Required provider validation is verified against current official docs before provider enablement; unavailable credentials do not justify fake production success.

## Phase 2 — Communities, membership and scoped authority

Local implementation: [evidence](phases/phase-2.md), [subsystem contract](community.md).
Main reviewer alone accepts this phase.

**Goal:** public/private groups with working membership lifecycle and enforceable staff hierarchy.

**Dependencies:** Phase 1 active verified users and audit. **Modules:** community, account queries, initial moderation commands for member sanctions, platform. **Features:** create/read/edit/archive community, minimal private discovery card, open join/request/invite-only policies, existing-user invitations, request review, leave/removal, role changes, owner transfer, member suspend/ban/unban. Introduce CommunityPermissionPolicy and explicit shared write/exclusive transition guards.

**Database changes:** communities, memberships, invitations, join requests, deferred owner FK/constraint trigger; moderation.action for attributable sanctions, published community API views. **Infrastructure:** scheduled invite expiry jobs using existing worker; no new service.

**Tests required:** full role matrix; duplicate join/accept; changed join mode; banned-user acceptance; request/invitation collision; invitation expiry without cleanup; cross-community IDOR; sole-owner leave/removal/transfer race; commit-time trigger; shared guards versus member revocation.

**Acceptance:** both visibility types and all join modes have API/integration coverage; exactly one effective OWNER for every ACTIVE community; owner transfer and membership updates commit atomically; staff cannot promote themselves or sanction peers/superiors; archive blocks mutations but preserves authorized history; no endpoint reads another module's repository.

**Edges:** PRIVATE+OPEN is allowed with content membership gate; expired PENDING row replaced under lock; owner account deletion requires transfer/archive; suspended member cannot act until expiry even if status still ACTIVE; unban does not silently rejoin. Invitation grants only MEMBER. Member-level moderation is delivered now, not postponed until search.

## Phase 3 — Social graph and reusable privacy facts

Local implementation: [evidence](phases/phase-3.md), [graph contract](graph.md).
Independent main reviewer alone accepts this phase.

**Goal:** distinct follow/friend/block/mute behavior with current authorization facts other features can rely on.

**Dependencies:** Phase 1; implement after Phase 2 to reuse guard/transaction conventions. **Modules:** graph, profile, account, platform. **Features:** follow/unfollow, friend requests/accept/decline/cancel/expire, unfriend, block/unblock, mute/unmute; own graph lists and permitted profile graph views; FRIENDS profile scope; account/graph published SQL views and batched relationship APIs.

**Database changes:** five distinct graph tables plus friend requests, unique canonical-pair constraints, reverse indexes, event version/tombstone support where needed; read-only views. **Infrastructure:** no new service; scheduled expiry via existing worker.

**Tests required:** reverse-direction requests, parallel accept, repeated follow/delete, follow/block races, shared interaction guard vs block, canonical UUID ordering parity with PostgreSQL, no self-relations, muted user can still be visited, block hides profiles/graph edges in both directions. Assert emitted facts only for effective changes.

**Acceptance:** block commits removal of both follows, friendship and pending requests atomically; unblock restores nothing; friendship does not imply following; opposite request returns existing/conflict and never accepts silently; relationship list pagination is deterministic; source views expose no PII.

**Edges:** existing relations to suspended users are filtered live; repeated DELETE is idempotent; email/provider identity plays no role in graph IDs; private profiles do not imply follow-approval workflow; constrain all mutations to same pair lock namespace.

## Phase 4 — Publishable posts with secure image attachments

**Goal:** a usable private/public content capability with images, source authorization and basic staff removal from its first release.

**Dependencies:** Phases 1–3. **Modules:** content (post/audience/revisions), media, profile avatar, community/graph/account APIs, moderation, platform workers. **Features:** personal/community posts, plain text/link, CUSTOM recipients, mentions/hashtags, edits/history, explicit audience edits, owner soft deletion, community remove/restore; full image upload/finalize/process/status/bind pipeline; avatar pipeline. Implement `ContentAccessPolicy`, SQL candidate/hydration building blocks and guarded content writes. A basic paginated post query supports verification before full home feed.

**Database changes:** post/recipient/revision/media/mention/hashtag tables, media asset/variant/binding, profile avatar FK, relevant indexes and moderation action references. **Infrastructure:** S3 adapter, Compose/Testcontainers PGSTY Silo pinned by [ADR-013](adr/013-local-object-storage-distribution.md) with versioning/private buckets, bounded image processor and durable cleanup jobs; storage quotas and short-lived URL signing. No video/documents/CDN requirement.

**Tests required:** complete audience policy matrix including author after community departure; SQL/object policy parity; current audience applies to history/media; blocked access through every path; versioned edit conflicts; idempotent create; nonempty content invariant; post removal blocks URLs; malformed/oversized media; overwrite-after-finalize; attach/cleanup races; partial variant failure/lease fencing; no arbitrary key signing.

**Acceptance:** a user uploads, waits for READY and atomically publishes with owned images; another eligible user can request a variant URL while an outsider cannot; storage failure never exposes quarantine; changing private audience affects the next read; public community posts and private community posts obey inherited scope; moderator can remove but cannot edit author body; orphan cleanup is observable and retryable.

**Edges:** ten-media atomic binding failure, same media in two concurrent posts, expired upload, duplicate finalize different version, stale processor output, avatar replacement cleanup, archived community, custom recipient later blocked, re-encoded image strips location metadata. Link URL is displayed as text and is not fetched by backend.

## Phase 5 — Conversations, reactions and report workflow

**Goal:** safe social interaction and practical abuse handling around existing content.

**Dependencies:** Phase 4. **Modules:** content comment/reaction subdomains, moderation, community, graph, platform. **Features:** roots/replies with depth two, edits/deletion/tombstones, per-target reaction types/replacement/removal, batched viewer-filtered counts, reports, staff report list/evidence/action flow, report resolution, audit evidence. No limitless comment tree or separate reaction microservice.

**Database changes:** comments/root trigger, reactions with active tombstone/monotonic version for out-of-order events, reports, action/report FK, query indexes. **Infrastructure:** existing workers/audit only; no hot synchronous counter service.

**Tests required:** reply to reply normalizes root, cross-post root rejected, blocked root author with visible child, parent deleted during reply/reaction, nonmember interaction on public community, duplicate PUT/type change/remove, recreated reaction vs stale event version, report IDOR, owner/moderator conflict, restoration after author deletion, sanction hierarchy and transactional audit failure.

**Acceptance:** comment/reaction routes cannot bypass parent post visibility; unique reaction semantics hold under concurrency; comment root deletion preserves readable replies without leaking root body/author; reporter/staff cannot see unrelated cases; action + content/membership transition + audit commit or roll back together. Report evidence is private and attributable.

**Edges:** viewer-filtered reaction totals agree with actor lists, self-reaction sends no notification later, duplicate report returns existing unresolved case, deleted target remains reviewable via bounded evidence, private-community membership revoked during interaction, deadlocks have bounded safe retry behavior.

## Phase 6 — First-class chronological feeds

**Goal:** efficient, correct home/community/timeline feeds with a future ranking seam.

**Dependencies:** Phases 3–5. **Modules:** feed, content queries, profile summaries; read-only community/graph facts through approved content adapter. **Features:** source candidate union, deduplication, chronological ranker, signed scoped cursor, bounded refill/hydration, blocks/mutes/deletion/current audience filtering and query metrics. Home includes follow/friend/joined-community/direct-custom sources; timeline is personal posts only.

**Database changes:** only measured content/graph/community query indexes and published-view contract refinements; no feed inbox table initially. **Infrastructure:** no new service. Add reproducible load data/benchmark script under test tooling, not fake production seed data.

**Tests required:** no duplicate/missing stable items across equal timestamps, cursor tamper/viewer/filter mismatch, empty filtered page continuation, newly published after cutoff, block/unfollow/membership loss between pages, duplicate sources, mute scope, query-count/N+1 guards and representative EXPLAIN plans.

**Acceptance:** tests show source-authorized results across every feed; page progress works under high filter rejection; current state defeats stale candidate facts; measured dataset/load evidence records latency and scan ratio; bounded SQL avoids transporting a 10,000-follow ID list into Java. Post edits do not bump chronology.

**Edges:** high-degree follows and large communities, scanning budget reached before page fills, candidate fetched but not examined, source deleted before hydration, posts that become newly eligible mid-traversal. Document weak consistency precisely; no false snapshot guarantee.

## Phase 7 — Persistent notifications and realtime hints

**Goal:** a durable, useful inbox with correct read races and optional low-latency updates.

**Dependencies:** Phases 1–6 and stable event versions. **Modules:** notification, event publishers, source query APIs, platform. **Features:** semantic notification kinds, preferences, reaction aggregation, actor contributions, mention/reply/comment dedup precedence, activity/read versions, unread count, recipient REST inbox, authenticated SSE invalidations and reconnect refresh. Notification delivery is not allowed to widen source audience.

**Database changes:** notification/contribution/preference tables, indexes, consumer registrations/receipts, optional recipient change watermark for local polling. **Infrastructure:** SSE; existing outbox workers. Redis only if actually deploying multiple public instances (shared rate limiting plus optional pub/sub). Single-instance SSE works without it.

**Tests required:** duplicate/out-of-order deliveries, reaction update/delete across group windows, concurrent read/new activity, multiple actors with one blocked, source removed after enqueue, preferences changed before delivery, self-suppression, direct invite/request notifications, revoked/expired SSE token, disconnect/backpressure, REST repair after missed hints.

**Acceptance:** three eligible actors can render one semantic group without stored English strings; retry does not inflate counts; unread activity is not lost in read races; currently hidden targets/actors do not appear or count; SSE contains no private post snippets, and loss never loses persisted notifications. Lag/retry/dead metrics and operator replay path are usable.

**Edges:** one recipient qualifies via several event routes, historical unreact/react with stable source identity, expired friend request invitation target, cursor reorder due to new activity (refresh semantics documented), multi-instance pub/sub loss, preferences must not disable security email.

## Phase 8 — Search and complete moderation operations

**Goal:** useful discovery and an operable, scoped review workflow.

**Dependencies:** Phases 1–7. **Modules:** search, moderation, profile/community/content export APIs, platform. **Features:** PostgreSQL search for users/communities/posts; language-neutral tokenization/name matching; source-authorized snippets; source-versioned projection/rebuild; moderation assignment/filtering/evidence access, platform suspension actions, case resolution history, retention jobs and simple operational dashboards. Moderation permission and removal already exist from earlier phases.

**Database changes:** search.document/GIN indexes, optional justified pg_trgm indexes, report assignment/query indexes, evidence retention fields, tombstone/version records for projection repair. **Infrastructure:** PostgreSQL search only; no OpenSearch/Elasticsearch. Add projection reconciliation/backfill jobs.

**Tests required:** stale index after privacy/deletion, blocked users in search, no hidden totals/highlights, query parser abuse, update/delete event reordering, live writes during rebuild, private evidence access/audit, scope/hierarchy for platform vs community actions, reinstatement does not restore author-deleted content.

**Acceptance:** eligible search results appear within measured projection-lag target (initial <30 seconds under test workload); forbidden results never appear even when worker is stopped; rebuild converges without duplicates/resurrection; all case reads and actions are attributable; no moderation escape via ordinary content APIs.

**Edges:** absent target after report, suspended account search results, accented/non-English text fixtures, expensive prefix queries, case assignment conflict, “public” community card vs private content, missed legacy events during a new-consumer backfill.

## Phase 9A — Mandatory release and operability gate

**Goal:** make completed capabilities safe to operate before external/public launch. This is required; optional scaling is separate.

**Dependencies:** all enabled product phases; private internal rollout can omit disabled capabilities with documented scope. **Modules:** all, especially platform/account owner-specific erasure jobs. **Features:** account erasure workflow and progress tracking, retention enforcement, incident/replay/recovery runbooks, secret/key rotation, dependency/security review, quotas/abuse tuning, dashboards/alerts, compatibility migration process, API documentation/examples, accessibility-relevant media alt text guidance and student onboarding exercises.

**Database changes:** account deletion workflow/task checkpoint table with unique `(user_id,module,step)` and retry/status/version; indexes/retention improvements based on measured plans, expand/contract migrations only. **Infrastructure:** production email/storage/provider setup, backup/PITR, restricted admin access/MFA gateway, metrics/error tracking and optional tracing. Redis required here only if multi-instance public API. No broker/search engine justified merely by launch.

**Tests required:** restore drill including storage/erasure reconciliation, clean install/upgrade/rollback-compatible app version, worker crash/dead-letter replay, dependency outages, key rotation, rate-limit multi-instance behavior when enabled, data deletion end-to-end, load/soak tests, security regression matrix and provider/storage smoke tests.

**Acceptance:** define and demonstrate RPO/RTO/latency/worker-lag goals on recorded infrastructure; no unexplained skipped checks; required provider approval is complete; credentials absent from git/logs; backup restore succeeds; deletion eventually reaches every owner module and removes private data from projections/media; public launch policy decisions in operations.md are resolved and recorded. README has trustworthy run commands and known limitations.

**Edges:** erasure while jobs are queued, deleted-user events replayed later, restore from pre-erasure backup, migration failure, SMTP ambiguity, storage version purge, missing encryption key recovery, owner account deletion blocked until transfer/archive. Operational evidence is required, not a claim that Testcontainers implies production readiness.

## Phase 9B — Conditional scale and richer product extensions

**Goal:** address measured bottlenecks or approved features without replacing the accepted core casually.

**Dependencies:** Phase 9A and a measured problem/explicit product requirement. **Modules/database:** as justified—feed inbox/ranking session, search adapter/index, media video/document processors, optional poll subtype tables. Every extension has its own ADR and acceptance plan.

**Infrastructure candidates:** Redis candidate caching/ranked sessions, hybrid fan-out workers, separate worker deployment, OpenSearch for relevance/query load, broker for independent high-throughput consumers, CDN/private delivery proxy if required. Kafka is not selected by default; compare operational cost with a managed queue or RabbitMQ against the actual delivery/ordering needs.

**Tests required:** equivalence/shadow reads, replay/backfill/cutover/rollback, source privacy under projection lag, fan-out backlog and celebrity skew, cache loss/stampede, ranking pagination stability, media-specific hostile inputs and resource exhaustion. Service extraction requires explicit replacement of shared transactions/FKs/SQL view contracts.

**Acceptance:** baseline vs changed measurements demonstrate benefit; failure mode and rollback are documented; no weaker privacy/identity semantics; no unbounded queue growth; cost/operational ownership accepted. **Edges:** stale fan-out after membership change, duplicate delivery across broker retry, cache eviction during ranked session, dedicated search results deleted at source, hot-post locks; solve observed contention specifically, not with universal distributed locks.

## Dependency introduction matrix

| Technology | Classification / first phase | Why / gate |
|---|---|---|
| Java 21, Boot, Maven, Spring MVC/Security/JPA | Foundational, P0 | Application/runtime and consistent HTTP/persistence conventions |
| PostgreSQL 18 + Flyway | Foundational, P0 | Source of truth, constraints, real migrations |
| Testcontainers, AssertJ, ArchUnit, formatter, GitHub Actions | Foundational, P0 | Prevent drift from first implementation |
| Actuator/Micrometer | Foundational, P0 | Observe health and latency; add business metrics by phase |
| SMTP + Mailpit locally | P1 | Verification/recovery require deliverable email |
| Outbox, durable jobs, audit, idempotency | P1 | Email/external effects must survive crashes and transaction rollback |
| Spring scheduler + PostgreSQL leases | P1 | Small durable dispatcher; no separate job framework initially |
| OAuth client/provider adapters | P1 | Required login/linking; no provider tokens in domain |
| S3 SDK + PGSTY Silo locally (ADR-013) + image processor | P4 | Real image lifecycle with validation/cleanup |
| SSE | P7 | One-way inbox invalidation; REST remains authoritative |
| Redis | Before multi-instance public API, otherwise conditional | Shared rate limits; later SSE fan-out/candidate caching |
| PostgreSQL full-text/GIN | P8 | Queryable search projection; pg_trgm only with measured need |
| Backup/PITR, admin MFA gateway, production key management | P9A release gate (secrets already isolated P1) | Recoverability and operator security |
| OTel tracing / error tracker | P9A when operationally useful | Follow requests into jobs/providers; redaction required |
| JobRunr/Quartz or workflow engine | Conditional P9B | Adopt only for demonstrated scheduling/workflow complexity |
| OpenSearch/Elasticsearch | Conditional P9B | Required relevance or measured PostgreSQL search limits |
| Broker (Kafka/RabbitMQ/managed queue) | Conditional P9B | Independent consumers/throughput justify transport operations; outbox remains |
| Microservices | Separate future architecture decision | Extraction business/operational benefit outweighs distributed consistency cost |

Two students can divide work by module after agreeing on published API DTOs and phase contracts, but must integrate one phase before racing ahead. Parallel implementation is not a license to create competing policy services or independently allocate conflicting Flyway versions.
