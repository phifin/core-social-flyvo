# Phase 1 — Secure accounts and durable authentication

Date: 2026-10-09. Implementation status: complete locally and verified by developer.
Review status: READY_FOR_REVIEW; development proposes R1–R3 fixed after regression
verification. Findings remain OPEN until the main reviewer closes them; this is
not phase acceptance. See [current handoff](../agent-handoff.md) for disposition.
Scope stops at Phase 1. Live-provider/production readiness is separately blocked
by the gates below; no push, deployment or external email/provider traffic.

## Delivered behavior

- Two-step, browser-bound local registration: generic 202, 30-minute intent,
  no preinstalled password/email reservation, mailbox-owner completion creating
  ACTIVE account + verified unique email + credential/provider binding + exactly
  one default profile and preference row in one transaction.
- Argon2id (19 MiB, two iterations, p=1), bounded four-hash concurrency, dummy work
  for unknown login, credential-version recheck after hashing and opportunistic
  rehash. Password policy is 15–128 Unicode characters with a maintained local
  common-password snapshot; no composition rules or silent truncation.
- Five-minute RS256 JWTs, exact issuer/audience/algorithm/kid validation, retained
  public verification keys, no profile/email/role claims, and current indexed
  PostgreSQL account/session checks for every Bearer request. Revocation and
  suspension deny immediately; DB outage returns 503.
- Independent browser sessions; seven-day idle/thirty-day absolute refresh
  families, retained single-use generation hashes, strict replay family revocation
  and audit committed before 401. No replay grace/idempotency cache. Current/all
  logout, bounded own-session listing and own-session revocation.
- Single-use reset revoking all sessions, recent reauth, password addition/change,
  new-mailbox proof and unique email change, old-address notification, provider
  identity listing/link/unlink retaining a usable method. OAuth-only reset cannot
  add a password; password addition requires explicit provider reauthentication.
  Email/password changes invalidate pending reset proofs.
- Google code exchange using Spring Security's client, S256 PKCE/nonce, signed
  provider JWT/JWKS verification and issuer/audience/azp/expiry checks. Facebook
  code/debug-token/user-info adapter validates configured app/user/both expiries,
  tolerates missing email and remains disabled pending official-contract approval.
- One-use persisted OAuth state bound to provider/intent/browser/initiating session,
  consumed before network calls. No auto-link by email, even verified. Fresh
  subject onboarding is a limited encrypted ten-minute capability, followed by
  our own mailbox proof; collisions/races do not create partial accounts. Callback
  redirects contain only fixed frontend result markers; link/reauth starts return
  authorizationUrl for frontend navigation.
- Basic profile PUBLIC/AUTHENTICATED/PRIVATE visibility, current target/actor checks,
  own versioned edits and typed locale/timezone/default-audience preferences.
  FRIENDS profile scope, graph and avatar/media remain deferred to their phases.
- Audited account suspension and deletion-request primitives; self-deletion has
  recent reauth + If-Match and immediate session revocation. No erasure claim.
- Deny-by-default route allowlist, readable CSRF cookie plus exact Origin for every
  unsafe API call, strict host-only refresh cookie and separate OAuth Lax binding,
  exact CORS and sanitized filter Problem Details. Unknown JSON fields rejected,
  16-KiB bodies including chunked transport, per-IP/account/user local rate limits
  and atomic DB per-address issuance windows.
- Atomic audit/outbox/direct mail jobs, encrypted expiring mail payloads, scheduled
  PostgreSQL claims, SKIP LOCKED leases, generation/owner/expiry fencing, bounded
  retry/DEAD handling, receipts with DB effects, deterministic mail Message-ID,
  cleanup and queue/failure metrics. No @Async-only delivery, Redis or broker.

## Migrations and boundaries

| New migration | Owned state |
|---|---|
| V0002 account_accounts | Account lifecycle/role/version |
| V0003 platform_durable_work | Append-only audit, jobs/encrypted payload, outbox/deliveries/receipts |
| V0004 identity_authentication | Unique emails/credentials/providers, intents/issuance windows, sessions/generations, one-time tokens/OAuth transactions |
| V0005 profile_initial_profiles | One profile/preferences per account, no media FK |
| V0006 identity_external_onboarding | Limited encrypted provider onboarding capability |
| V0007 identity_state_constraints | OAuth initiator/hash, email/subject, expiry/successor and durable-lease constraints |
| V0008 identity_onboarding_reauthentication | Onboarding sessions require explicit fresh proof for sensitive actions |

V0001 is unchanged. Fresh migrations and an actual V0001-only upgrade are tested,
including checksum validation; JPA validates account/profile/preference mappings.
Identity snapshots use owned parameterized JDBC with explicit guarded versions,
joining the same local transaction manager. Foreign FKs reference scalar IDs;
there are no foreign repositories/entities, SQL views or future module tables.

Published contracts: account `AccountCommands` (createVerified/requestDeletion/
suspend) and `AccountQueries` (facts/transaction-required shared or exclusive
lifecycle guard); profile `ProfileCommands.provision` and viewer-aware
`ProfileQueries.visible`; identity `SessionQueries.requireCurrent`; platform
`ActorContext`, `Secrets`, `DurableWork`, `EventConsumer`,
sanitized `Failure` and strong `ResourceVersions`. Producer API event records:
UserRegistered, AccountStatusChanged, AccountDeletionRequested, ProfileUpdated.

Application commands own transactions; module commands join REQUIRED/MANDATORY.
Global account-before-session lock order is preserved. Password hashing and OAuth
exchange happen before guarded commits, SMTP between claim/ack transactions.
Authentication adapters live in identity infrastructure and check every Bearer
request before constructing a trusted ActorContext. Profile independently checks
current account facts/guards and its own policy, without any reverse callback into
identity or permission-granting projection. No generic auth-response idempotency
table/cache is introduced.

## Verification evidence

The following table records the original implementation checks, not reruns on
the review-fix commit. Current round results are recorded separately below.

Commands were run with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`
(the environment's default shell Java is 8). Docker/Testcontainers checks are real,
not skipped.

| Check | Actual result |
|---|---|
| Targeted policy/provider/JWT/key/architecture and AuthenticationIT/FoundationIT runs | Passed during implementation; failures corrected before consolidation |
| `./mvnw -B -ntp spotless:apply verify` | Passed: 25 unit/architecture + 34 PostgreSQL/Mailpit integration tests = 59; zero failures/errors/skips; formatter, Enforcer/dependency convergence pass |
| `docker compose config --quiet` | Passed |
| `docker build --tag social-core:phase1 .` | Passed with pinned Java 21 images; packaging does not replace host integration tests |
| Isolated `social-core-phase1-smoke` Compose HTTP checks | Passed: container readiness, mounted persistent keys/non-root UID 1000, email verification + password reset from SMTP captures, two browsers, own-session list, logout denying next request and reset revoking the other browser |
| OpenAPI 3.1 validation (`openapi-spec-validator==0.7.2`, temporary environment) | Passed |
| Local key generator run twice + hash comparison | Passed: no overwrites, four key files mode 0600; keys ignored by Git and Docker context |
| Pinned Gitleaks 8.28.0 directory scan of tracked/task-owned source snapshot | Passed: no leaks; ignored keys/build output not included |
| Local Markdown link check / `git diff --check` | Passed: 91 file links, none broken; no whitespace errors |

Smoke uses only localhost ports 55433/18081/11026/18026, a separate Compose
project/network/volume and synthetic `example.org` mail. Its own containers and
volume are removed afterward; normal developer infrastructure is untouched.
No remote CI run, deployment or real-provider traffic is claimed.

Tests cover success/denial/expiry; browser/mailbox binding and mass assignment;
same/different-intent email races; competing provider claims and duplicate callback;
strict concurrent refresh/lost response; two independent sessions and IDOR;
reset during password verification; last-method/two-unlink races; email-change
reset-proof invalidation; provider collision/missing/changed email, reauth and state;
JWT signature/issuer/audience/expiry/alg/kid rotation; CORS/CSRF/cookie flags;
profile privacy/version/preferences; rate/body bounds; mandatory-audit rollback;
outbox rollback/duplicate receipt, worker claims/lease fencing/retry exhaustion;
consumer-effect/receipt rollback on failure or unacknowledgeable lease;
real Mailpit verification delivery/ack and external-key restart/tamper behavior.
The source session lookup uses its primary-key index with 10,000 synthetic sessions.

Initial checks found and fixed SMTP's unwanted aggregate-health dependency,
invalid-timezone errors escaping as 500 and early servlet-filter construction
before a shared EntityManager bean was available. JPA adapters now obtain Spring's
transaction-scoped shared proxy from the injected EntityManagerFactory;
AuthenticationIT starts real embedded Tomcat and verifies HTTP requests. A new
migration-count assertion was updated for V0008, not suppressed. Neither security
denial tests nor architecture rules were relaxed. OAuth claim-race denial is
audited after the failed binding transaction has fully rolled back. Replay still
commits revocation/audit when the owning account has become suspended.

## Refinements and external gates

- Bouncy Castle 1.86 is the explicit dependency needed by Spring's Argon2 encoder;
  the Boot BOM does not manage it. OAuth client/JOSE and SMTP starters serve actual
  Phase 1 boundaries. Fake provider HTTP tests use JDK HttpServer, no new mock stack.
- Initial scheduler concurrency is one workload per process; four concurrent
  password hashes. SMTP/provider timeouts are five seconds, lease 60 seconds,
  retry at most ten attempts, mail retry cap one hour and proof expiry. These are
  baseline limits, not deployment capacity/latency claims. Cleanup is bounded.
- Existing-mail guidance is durable but outwardly generic. Both stored email
  columns currently contain the canonical address. Registration/onboarding do
  not reserve email. No profile FRIENDS scope or initial media FK is enabled.
  External onboarding completion does not refresh an earlier provider proof:
  sensitive actions require an explicit bound-provider reauthentication.
- Local keys persist in ignored mounted files; test keys alone are ephemeral.
  JWT rotation supports retained verification keys. Online mail multi-key migration
  is not implemented; the documented drain/expiry/restart procedure is required.
- SMTP is at least once and may duplicate after ambiguous acceptance; proof
  consumption remains single-use. DEAD mail needs a fresh bounded proof request.
  No production projection consumer/backfill is claimed ahead of its owning phase.
- Google registration/consent/credential and real provider smoke tests remain
  external. Meta official contract pages returned 429; Facebook is disabled by
  default and startup rejects enablement without explicit recorded contract
  approval/version pin. Real Meta permissions/review/deauthorization/data-deletion
  obligations remain blocking gates; fake tests do not certify them.
- Production SMTP/domain delivery, TLS/proxy log redaction, persistent key backup/
  runtime permissions, Argon2 hardware tuning, limiter/edge and common-password
  review, restricted audit/runtime roles, operator MFA, backup restore and erasure
  require deployment-environment evidence before public launch.
- Account closure's ownership check is vacuous while no community module exists.
  Phase 2 must wire its ownership API and guarded blocker before enabling communities.
  Full erasure and audit retention approval stay at Phase 9A.

No accepted product/security/architecture decision was changed; no ADR superseded.
Meaningful refinements are documented here and in subsystem/operations docs.
Next work is main-thread re-review of R1–R3. Phase 2 requires separate assignment;
it is not implemented here. Local commit hashes are recorded in the handoff;
nothing is pushed, merged or deployed.

## Phase 1 review fixes — round 1 (2026-10-09)

Scope: R1–R3 only, based on code `4ffbc875dd16602e55ccffdd0a82ea6aae889d86`
and review mailbox commit `0ed701e`. No migrations, new dependencies, module APIs,
allowlist expansion or Phase 2 work. The account-before-session lock order,
transactional reset invalidation, callback state consumption before network,
unique-claim conflict auditing and no-email-auto-link policy remain intact.

- R1: reset's initial email lookup selects an exclusive account guard only.
  Re-read recovery email and local credential under it before creating proof/mail;
  stale lookup returns normally without issuing anything. Latch regression proves
  email change commits between lookup and guard, no new reset job/live proof,
  old proof cannot take over, and the new address can reset successfully.
- R2: after LOGIN's shared guard or LINK/REAUTH's exclusive actor guard, re-read
  the provider binding and validate owner/ID. An observed removed/replaced binding
  yields `identity_conflict`, no new session/freshness/success audit, and an audited
  LINK denial. Do not follow another owner or acquire a second account guard.
  Three latch regressions cover unlink before LOGIN/REAUTH/same-account LINK;
  another covers reassignment and same-owner replacement, then successful new flow.
- R3: separate continuously refilled authorize/callback GET buckets default to
  10/20 requests per minute/IP, shared across both providers, configurable 1–1000.
  Filter rejection precedes persistence/state consumption/provider calls and
  returns 429 Problem Details + `Retry-After: 60`. Framework path matching closes
  encoded-provider bypass. Denials preserve partial refill. Mutable-clock HTTP
  tests prove bounded rows/exchanges, valid unconsumed state after 429, independent
  IP/budgets, invalid-attempt budgeting, forwarded-IP resistance, refill, encoded
  routes and unaffected health/top-level callbacks. Startup rejects invalid limits.

Actual commands (Java 21, real PostgreSQL/Mailpit Testcontainers):
The first command ran on the initial seven-test regression set, before the
additional encoded-path, replacement-binding and configuration tests were added.

```bash
./mvnw -B -ntp spotless:apply test-compile failsafe:integration-test failsafe:verify \
  '-Dit.test=AuthenticationIT#resetIssuanceAfterRecoveryEmailChangeCannotSendOldMailboxTakeoverProof+oauthLoginCannotUseBindingUnlinkedBeforeItsAccountGuard+oauthReauthCannotUseBindingUnlinkedBeforeItsAccountGuard+oauthSameAccountLinkCannotReportBindingUnlinkedBeforeItsAccountGuard,OAuthAbuseIT'
./mvnw -B -ntp spotless:apply test \
  '-Dtest=BrowserBoundaryFilterTest,ProviderContractTest,ArchitectureTest,PersistenceBoundaryTest' \
  failsafe:integration-test failsafe:verify '-Dit.test=AuthenticationIT,OAuthAbuseIT'
./mvnw -B -ntp spotless:apply verify
```

| Round-1 check | Actual result |
|---|---|
| First seven regressions on unmodified feature code | All seven FAILED on expected stale-proof/stale-binding or missing-429 assertions; zero test errors/skips after fixture wiring correction |
| Same seven after fixes | Seven passed, zero failures/errors/skips |
| Encoded-provider regression on raw-URI limiter | FAILED as expected: 303 instead of 429; passes with framework matcher |
| Targeted current provider/boundary/architecture + auth/OAuth suites | 11 unit/architecture + 34 integration passed; zero failures/errors/skips |
| Full `spotless:apply verify` | BUILD SUCCESS: 26 unit/architecture + 43 integration = **69 tests**; zero failures/errors/skips; formatting, Enforcer, dependency convergence pass |
| Updated OpenAPI 3.1 validation (`openapi-spec-validator==0.7.2`) | Passed |
| `docker compose config --quiet`, local Markdown links, `git diff --check` | Passed; no broken local links or whitespace errors |
| Pinned Gitleaks 8.28.0 scan of exact staged source snapshot | Passed, no leaks; ignored keys/build artifacts excluded |

Ten added tests cover these fixes. Original refresh replay, last-method/two-unlink,
cross-account unique claims, current account/session, CSRF/Origin and durable work
tests all pass without exclusions. Detailed test names and real code commit are
in the handoff. Docker image/Compose smoke and live providers are not rerun in this
fix round; prior evidence is historical. Provider/production gates above still
apply. Per-instance limits do not survive restart or replace shared/edge controls
for multi-instance public traffic; no Redis/broker is introduced. Main reviewer
alone resolves findings or accepts this phase.
