# Testing and quality gates

Test behavior and failure modes at the cheapest truthful layer. Use Boot-managed JUnit Jupiter (JUnit 6 with the current Boot 4 baseline), AssertJ, Mockito only for real boundaries, Testcontainers PostgreSQL 18, and ArchUnit. This intentionally retains the starter's supported test stack instead of forcing JUnit 5; it does not change the required quality gates. No H2, disabled Docker integration checks, or mocked repositories presented as persistence verification.

## Test layers

| Layer | What it proves | Typical examples |
|---|---|---|
| Pure domain/unit | Invariants, state transitions, normalization, ordering | audience discriminant, owner transitions, two-level comments, password input bounds, canonical-pair algorithm |
| Application | Orchestration, policy, event/audit intent | author cannot attach another user's media, mention does not widen audience, role hierarchy, current facts checked |
| Repository integration | PostgreSQL constraints, SQL semantics, transactions, Flyway | partial unique indexes, deferred owner FK/trigger, comment root trigger, UPSERT, keyset tie-breaks, SQL-view contracts |
| API integration | Actual routing/security/validation/serialization | 404 vs403, Problem Details, CSRF/CORS, ETag, no entity leakage, cookie flags, idempotency |
| Security/provider contract | Adversarial auth/identity cases | state/nonce/issuer/audience mismatch, callback replay, linking collisions, missing provider email, IDOR across all resource paths |
| Concurrency | Races with separate committed transactions | refresh reuse, block/follow, parallel friend accept, owner leave/transfer, revoke/comment, attach/cleanup |
| Worker/fault integration | Crash/retry/ordering behavior | rollback emits nothing, duplicate delivery, lease expiry, out-of-order delete/update, ambiguous email delivery, stale media worker |
| Architecture | Boundaries remain enforceable | dependency DAG, API-only module imports, no web in domain, no cross-module entities/repositories, SQL-view allowlist |

Name fast tests `*Test`; container/API/concurrency tests `*IT`. Maven Surefire runs fast tests and Failsafe runs integration tests during `verify`. ArchUnit is fast and mandatory. Testcontainers runs real migrations and Hibernate validation, using reusable singleton containers only within the test JVM; CI may not skip tests when Docker is unavailable. No tests against a developer's Compose DB. Isolate fixtures by database/schema or truncate deterministically; don't wrap all tests in a rollback transaction that hides commit-time triggers/outbox bugs.

Phase 0 implements this gate with PostgreSQL 18.6 pinned by digest. Architecture tests allow absent future layer packages so no placeholder module code is necessary; production classes are still checked, and deliberately forbidden fixtures prove the module rule rejects cross-module internals. SQL checks detect conventional schema-qualified runtime references and migration naming/version ownership. They complement review and are not a SQL parser: generated/concatenated SQL and future external query resources need corresponding contract checks when introduced. No published SQL views exist yet, so view shape/policy parity tests begin with their owning feature phases.

## Mandatory feature test checklist

For each implemented behavior, test success, invalid state, forbidden actor, inaccessible resource, missing resource, boundary sizes and relevant retry/concurrency paths. Endpoint tests use at least Alice, Bob and an unrelated Mallory; use two communities to catch scope/IDOR confusion. Business denial must assert unchanged persistence/outbox/audit where applicable, not only the HTTP status. Every bug fix includes a regression reproducing the failure before the fix.

Security tests cover anonymous, owner, permitted relation/member, outsider, blocked both directions, muted, suspended/deleted author, and revoked viewer session. Run this matrix for single resource, list, feed, search, comment, reaction summary, notification, revision history and media access—not just `GET /posts/{id}`. SQL visibility filtering must match the object policy for generated combinations of audience/community/relationship/account state. Mutation tests can later target privacy policy if ordinary coverage proves insufficient.

Use injected `Clock` and deterministic random/ID seams only where tests need them. No `Thread.sleep` to arrange races: use barriers/latches, two transaction connections and bounded timeout. Assert final committed database state and event count. Repeat an operation to prove idempotency; racing opposite operations need an allowed serial ordering, not an arbitrary chosen winner.

For media use Testcontainers PGSTY Silo pinned to the same exact artifact as local Compose in [ADR-013](adr/013-local-object-storage-distribution.md), plus malformed fixtures. Prove private/versioned/encrypted storage, signature/checksum and operation-header rejection, version-pinning against post-finalize overwrite, quota/size/decode limits, ownership, READY-only binding, cleanup fencing, storage failure and signed URL expiry. A mock S3 adapter is insufficient for presign/versioning behavior. Run a small environment-gated contract suite against production storage before release.

OAuth unit/integration tests use a local HTTP fake for token/JWKS/user-info/introspection behavior (e.g. WireMock if justified in Phase 1). Do not call live Google/Facebook in CI. Operator/provider smoke tests before release verify actual application configuration separately; credentials stay in environment secrets and tests do not send unsolicited messages.

## CI pipeline (Phase 0 onward)

GitHub Actions on PR/push: checkout with least privilege, setup pinned Java 21, Maven wrapper/cache keyed by POMs, formatting check, `./mvnw -B verify`, upload test reports on failure. Use Spotless + Google Java Format; pin formatter/plugin versions. Maven Enforcer checks Java/Maven/dependency convergence as compatible with Boot BOM. Add a small agreed compiler/static-analysis configuration; do not add multiple overlapping linters for prestige.

CI cannot skip integration tests for convenience, swallow flaky failures, autoformat unrelated code or use a floating `latest` database image. Pin GitHub Actions to immutable commits, permissions `contents:read`, never give fork PRs deployment secrets. Dependency/security scanning begins with scheduled Dependabot and a secret scanner; before public launch add SBOM and container/dependency vulnerability review with an explicit exception process. Tests pass before image publishing. No production deployment workflow is implied by this blueprint.

The CI-pinned Gitleaks 8.28.0 image scans full git history with redaction and default
rules. `.gitleaksignore` contains only the reviewed P4-R5 commit/path/rule/line
fingerprint: a historical code hash mistaken for a credential in handoff prose.
No rule/path/directory exclusion or history rewrite is used. CI also runs
`bash ops/ci/secret-scan-regression.sh`: an external temporary synthetic positive
fixture must still produce generic-api-key/exit 1; the redacted control is not
committed and is removed afterward. A clean staged-patch scan is not a substitute
for the full-history gate.

Track JaCoCo coverage as a diagnostic, especially policy/state branches; avoid vanity global percentages. Missing privacy/error branches cannot be excused by overall coverage. Performance verification records dataset distribution, hardware, concurrency, p50/p95/p99, query count, query plans and worker lag. Performance thresholds are established from measurements, then enforced for representative regressions; do not turn unstable microbenchmarks into every PR's gate.

## Definition of tested

Phase 4's [evidence](phases/phase-4.md) includes real pinned Silo security/storage,
secret-mount isolation, PostgreSQL SQL/object/current-API policy parity, post HTTP
and version/idempotency/retention, scoped moderation rollback, ten-image atomicity,
named quota/attach/cleanup/member/block races, pinned overwrite/fenced stale and
partial outputs, terminal recovery, all-version purge and avatar replacement.
Spies inject bounded failures/latches but call real repositories/storage; PostgreSQL
wait_event_type=Lock establishes conflicting mutation waits. ImageProcessorTest
executes actual isolated JPEG/PNG/static WebP decoding and hostile container/CRC/
dimension/metadata cases. PackagedImageProcessorIT repeats WebP using the built
Boot jar/child launcher. FoundationIT upgrades V0014 with retained checksums/profile;
the actual candidate SQL has a skewed 12,000-row indexed EXPLAIN and bounded hydrate
query-count regression. R3 statement-completion barriers exercise recipient removal
then a private body edit, audience-kind switches, attachment/mention replacement and
history edits after staff departure. R4 uses real reusable/expired Silo PUTs, durable
expiry/settling deferral, quota retention, deletion markers, lease-loss/crash retry,
READY quarantine-only purge and terminal storage reconciliation. A synthetic late
SDK write models an accepted-before-expiry completion beyond settling; it is not
presented as an expired URL successfully starting a new upload. The prior integration
suite remains required, not replaced.

Phase 3 GraphIT covers real PostgreSQL relationship lifecycles, denied actor/IDOR,
FRIENDS and bilateral block policy, opposite/duplicate requests, follow/block races,
both shared-guard/block orders, UUID ordering parity, no-op event counts/revisions,
live expiry/replacement/duplicate jobs, rollback, bounded inactive/blocked lists,
cursor tamper/scope/expiry, all HTTP commands/current session revocation, published
view columns and indexed point lookup on 10,000 follows. FoundationIT adds upgrade
from V0011 with checksums and retained profile. See [Phase 3 evidence](phases/phase-3.md).

Phase 2 tests both visibility types/all modes through real HTTP and application
transactions, hierarchy/self/equal/superior denial, IDOR, current revocation,
mode changes, admission collision/live expiry, If-Match and logical idempotency.
PostgreSQL barriers/latches exercise duplicate joins/accepts, request/invite,
transfer vs leave/removal/closure, creation vs closure and both shared-write guard
orders against revocation. Commit-time owner FK/trigger, audit rollback, registered
DB job crash/reclaim/fenced rollback/retry-to-DEAD and Phase 1 schema upgrade are
checked without skips. Community published-view columns and SQL/object visibility
parity are checked; a synthetic 10,000-member query plan uses membership_pkey.
Exact commands/results and measurement limits are in [Phase 2](phases/phase-2.md).

Phase 2 R1–R2 regressions reproduce renewal/prune deletion, INSERT/SELECT cleanup
empty-row failure and archive's incorrect outbox status before fixes on PostgreSQL.
Latches, real statement-boundary instrumentation and pg_blocking_pids prove renewal-
first, cleanup-between-statements and cleanup-first interleavings, without sleep or
mocked SQL/lock results. Tests assert bounded expiry cleanup, replay UUID/body conflict,
unchanged live expiry and no extra resources/events/jobs, including terminal admission.
Committed lifecycle/membership/admission payload/source/version and archive no-op
retry are checked independently of responses, with source/event rollback atomicity.

Phase 1 adds real PostgreSQL + Mailpit tests, a JDK local HTTP provider server
(no live-provider CI calls or additional mock-server dependency), signed JWT/JWKS
fixtures, separate-transaction/barrier races and an instrumented password-hashing
boundary for reset-during-login. Upgrade starts from an actual Flyway V0001-only
database and validates its unchanged checksum. Session lookup EXPLAIN uses
10,000 synthetic sessions and requires the primary-key index. Test-only keys
are ephemeral; mounted-key restart/tamper behavior has a separate real-crypto
test. No Docker test is skipped. Exact evidence is in [Phase 1](phases/phase-1.md).
AuthenticationIT also starts real embedded Tomcat and sends HTTP Bearer requests,
so servlet-startup wiring is covered beyond MockMvc. Isolated container smoke
checks exercise real cookie/CSRF rotation and SMTP-captured verification/reset.

Phase 1 review regressions pause a test thread immediately before the real
account guard via a spy on its published API, commit email change or unlink in
another transaction, then resume. Persistence/guards are not mocked: PostgreSQL
proves stale reset issuance and LOGIN/REAUTH/LINK cannot authenticate removed or
replaced bindings. Separate enabled fake-provider HTTP tests use a mutable Clock
to prove authorize/callback limits, partial refill, independent IPs/budgets,
forwarded-IP and encoded-path resistance, no state/provider work after 429, and
unaffected health/top-level callbacks. No sleep-based interleaving is used.

Passing unit tests alone does not complete a persistence/API phase. A phase is done when its acceptance scenarios pass against PostgreSQL, migrations work from empty and prior schema, architecture tests pass, relevant container/provider contracts pass, and known external prerequisites are explicitly reported. Record exact commands/results and any blocked checks in `docs/phases/phase-N.md`. Never claim unrun tests passed.
