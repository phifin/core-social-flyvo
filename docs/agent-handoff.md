# Agent handoff — current task

## Routing

- Task: phase-1-review-fixes
- Round: 1
- State: READY_FOR_REVIEW
- Last writer: development thread
- Next owner: main reviewer
- Reviewed code commit: `4ffbc875dd16602e55ccffdd0a82ea6aae889d86`
- Proposed fix code commit: `7aa6b47510f0c51cd959b54b0d46827a7266dba0`
- Scope: fix the Phase 1 findings below, add regression tests, update phase evidence. No Phase 2 work.
- Protocol: [thread workflow](thread-workflow.md), [Git workflow](git-workflow.md).

## Implementation report received

Developer reports Phase 1 implemented locally: registration, sessions/refresh, recovery, OAuth/linking, profile, account lifecycle and PostgreSQL durable work. Seven new migrations V0002–V0008; baseline V0001 preserved. Reported 59 passing tests, Docker/HTTP smoke, OpenAPI, secret and documentation checks. Full original evidence: [Phase 1](phases/phase-1.md). These are developer-reported results except checks explicitly rerun below. Live providers, production SMTP/TLS and operational readiness remain external gates.

## Reviewer verdict

CHANGES_REQUESTED. Do not proceed to Phase 2 yet. The implementation's local completion claim is not review acceptance. Review covered identity commands/provider callback, HTTP/cookie/rate-limit boundary, profile/account guards, persistence and durable worker paths, with focus on security transitions. This is not a full production security audit. Findings below are supported by source/control-flow inspection; the precise new concurrency interleavings have not yet been executed as regression tests.

### R1 — P1: reset issuance trusts an email lookup made before the account guard

- Location: `src/main/java/com/flyvo/socialcore/identity/application/Authentication.java`, `requestReset`, lines 344–350 at reviewed commit.
- Failure: reset request reads old email -> user ID and credential, then waits for the exclusive account guard. A concurrent `completeEmail` can change that account's email and invalidate existing reset proofs first. Once reset acquires the guard, it checks only account status, creates a NEW valid reset proof and sends it to the old email captured before locking. `reset` subsequently accepts that proof by user ID. An old-mailbox holder can regain access despite the committed recovery-email change.
- Required outcome: under the account guard, re-read current recovery email/ownership and credential eligibility before issuing proof/mail; stale lookup must yield the normal generic response with no usable proof sent to the old address. Keep email-change/reset invalidation atomic and preserve existing lock ordering.
- Regression: deterministic latch/barrier PostgreSQL test forcing lookup -> email-change commit -> reset guarded section. Assert no usable old-address proof, no takeover, and valid reset for the new address still works. No sleep-based race test.
- Disposition: OPEN.

### R2 — P1: OAuth callback uses a stale identity binding after acquiring the guard

- Location: `src/main/java/com/flyvo/socialcore/identity/application/OAuthAuthentication.java`, callback lines 126–133 and 165–167; `Authentication.unlink` holds the exclusive account guard.
- Failure: callback reads `existing` before acquiring the account guard. Unlink can then remove that binding and commit. LOGIN takes its guard but grants a fresh session from the stale `existing`; REAUTH similarly updates authentication freshness from the stale binding after `auth.current`. Removing a login method therefore does not serialize correctly with new authentication through that method. LINK can also report success from a binding removed during its guard wait.
- Required outcome: after acquiring the relevant guard, re-read the provider/issuer/subject binding and verify current owner (and binding identity where needed) before grant/reauth/link result. If removed/reassigned, fail safely or require a new flow; never follow a stale owner or acquire arbitrary second account locks out of order. Preserve unique-claim conflict handling and audit behavior.
- Regression: deterministic PostgreSQL interleavings for unlink committing between first binding lookup and guard acquisition for LOGIN and REAUTH; neither may authenticate using the removed binding. Cover same-account LINK raced with unlink so it cannot report a binding that does not exist. Preserve ordinary login/link and concurrent unique-claim tests.
- Disposition: OPEN.

### R3 — P2: public OAuth GETs bypass local abuse limits

- Location: `src/main/java/com/flyvo/socialcore/identity/infrastructure/security/BrowserBoundaryFilter.java`, lines 56–58; `OAuthController.authorize` and `OAuthAuthentication.start`.
- Failure: rate limiting runs only inside the unsafe-method branch. With a provider enabled, anonymous authorize GETs can insert unlimited OAuth transaction rows; valid callbacks can drive external exchanges without passing the local auth limiter. Expiry/cleanup does not bound incoming work, and deployment-edge limits are not part of the runnable local protection.
- Required outcome: bounded per-IP OAuth initiation/callback limits before state insertion/provider calls, independent of unsafe-method Origin/CSRF handling. Keep top-level provider callbacks functional, preserve health behavior, return consistent 429/Retry-After, and document chosen tunable limits. No Redis needed.
- Regression: enabled fake-provider HTTP tests exceeding authorize/callback limits, verifying bounded persisted transactions/provider calls, refill using a controllable clock and independent-IP behavior.
- Disposition: OPEN.

## Developer responses

For each R1/R2/R3, append: changed files/code commit, regression test names and actual results, and any remaining limitation. Mark PROPOSED_FIXED; only main reviewer closes the finding. Do not delete original findings.

### R1 response — PROPOSED_FIXED

- Code: `7aa6b47510f0c51cd959b54b0d46827a7266dba0`; changed
  [Authentication.java](../src/main/java/com/flyvo/socialcore/identity/application/Authentication.java)
  and [AuthenticationIT.java](../src/test/java/com/flyvo/socialcore/identity/AuthenticationIT.java).
- The initial email lookup selects the account guard only. After the exclusive
  guard, issuance re-reads current recovery email and local credential eligibility
  and checks current active status before creating proof/mail. A stale address
  returns normally; HTTP keeps its generic 202. Email-change/reset invalidation
  remains in its original transaction/lock order. No schema/API changes.
- Regression: `resetIssuanceAfterRecoveryEmailChangeCannotSendOldMailboxTakeoverProof`.
  A latch pauses before the real PostgreSQL account guard after old-email lookup;
  `completeEmail` commits before resume. Assert no extra reset job or live reset
  proof, old proof cannot change the password, old-email login fails, and reset
  using the new mailbox works. The guard spy invokes real behavior; persistence
  is not mocked and there are no sleeps.
- Actual results: failed before fix (reset jobs 2 instead of 1); passed initial
  post-fix run, complete targeted AuthenticationIT and full verify. No remaining
  local limitation identified for this finding; external production gates remain.

### R2 response — PROPOSED_FIXED

- Code: `7aa6b47510f0c51cd959b54b0d46827a7266dba0`; changed
  [OAuthAuthentication.java](../src/main/java/com/flyvo/socialcore/identity/application/OAuthAuthentication.java)
  and AuthenticationIT.java.
- Callback re-reads binding after LOGIN's shared account guard or LINK/REAUTH's
  exclusive actor guard. Require the same observed binding ID/owner; reject an
  observed removed/replaced binding with `identity_conflict`, no grant/freshness
  update or success audit. LINK rejection records `IDENTITY_LINK_REJECTED` /
  `BINDING_CHANGED` in the guarded transaction. Never follow a reassigned owner
  or acquire a second account lock. Unique-claim rollback/conflict auditing and
  provider exchange outside transactions remain unchanged.
- Regressions (latches and separate committed PostgreSQL transactions):
  `oauthLoginCannotUseBindingUnlinkedBeforeItsAccountGuard`,
  `oauthReauthCannotUseBindingUnlinkedBeforeItsAccountGuard`,
  `oauthSameAccountLinkCannotReportBindingUnlinkedBeforeItsAccountGuard`,
  `oauthLoginCannotFollowReassignedOwnerOrReplacementBinding` (both new-owner and
  same-owner/new-ID cases, plus ordinary new-flow login afterward).
- Actual results: the first three failed before fix, returning `signed_in`,
  `reauthenticated`, `linked` respectively. All four pass in targeted/full suites,
  asserting committed binding/session/freshness/audit state. Existing ordinary
  provider login/link, no-email-auto-link, two-unlink and cross-account unique-
  claim tests also pass. A removed/replaced observed binding needs a new OAuth
  flow; consumed state is not replayed. Live-provider gates remain external.

### R3 response — PROPOSED_FIXED

- Code: `7aa6b47510f0c51cd959b54b0d46827a7266dba0`; changed
  [BrowserBoundaryFilter.java](../src/main/java/com/flyvo/socialcore/identity/infrastructure/security/BrowserBoundaryFilter.java),
  application.yml, added
  [OAuthAbuseIT.java](../src/test/java/com/flyvo/socialcore/identity/OAuthAbuseIT.java) and
  [BrowserBoundaryFilterTest.java](../src/test/java/com/flyvo/socialcore/identity/BrowserBoundaryFilterTest.java).
- Per-IP GET budgets precede state insertion/consumption/provider work, separate
  from unsafe Origin/CSRF rules. Defaults authorize 10/minute, callback 20/minute,
  continuously refilled and shared across providers. Invalid attempts spend the
  callback budget. Tunable capacities 1–1000 via
  `app.auth.oauth-authorize-per-minute` / `OAUTH_AUTHORIZE_PER_MINUTE` and
  `app.auth.oauth-callback-per-minute` / `OAUTH_CALLBACK_PER_MINUTE`.
  Exhaustion returns consistent 429 `RATE_LIMITED` + `Retry-After: 60`.
  Preserve partial refill on denial; match decoded framework paths so encoded
  provider names cannot bypass the limiter. Health and top-level callback GETs
  retain their existing behavior; deny-by-default allowlist is unchanged.
- Regressions with enabled Google configuration, fake provider boundary, real
  PostgreSQL, MockMvc security pipeline and controllable Clock:
  `authorizeGetIsBoundedBeforeInsertRefillsAndDoesNotTrustForwardedIp`,
  `callbackGetIsBoundedBeforeConsumptionAndExchangeWithIndependentIpAndRefill`,
  `invalidCallbacksSpendTheSameBudgetWithoutProviderCalls`,
  `encodedProviderPathCannotBypassInitiationBudget`.
  Unit regression: `rejectsInvalidOAuthBudgetsAtStartup` (both properties,
  values 0/-1/1001). Assert bounded rows/provider calls, unconsumed valid state
  after 429, independent IPs/budgets, refill including denied partial refill,
  forged forwarding header resistance, health and callback without Origin/CSRF.
- Actual results: first three failed on baseline (303 instead of 429). Additional
  encoded-route test failed with raw-URI matcher (303 instead of 429); framework
  matcher fixes it. All four integration tests and startup unit test pass in
  targeted/full suites. Limits remain per-instance/restart-resetting, not a
  multi-instance quota or edge DDoS defense. No Redis/broker introduced.

### Development checks — round 1, 2026-10-09

- Baseline verified: clean worktree/index at start; HEAD `0ed701e` is documentation
  only atop reviewed code `4ffbc87`. No newer code or unrelated changes present.
- Initial seven failing-before regressions: seven assertion failures, zero test
  errors/skips after correcting test fixture AOP spy wiring. Same seven passed
  after fixes. Additional encoded-route bypass reproduced/fixed separately.
- Targeted command (Java 21):
  `./mvnw -B -ntp spotless:apply test '-Dtest=BrowserBoundaryFilterTest,ProviderContractTest,ArchitectureTest,PersistenceBoundaryTest' failsafe:integration-test failsafe:verify '-Dit.test=AuthenticationIT,OAuthAbuseIT'`.
  BUILD SUCCESS: 11 unit/architecture + 34 integration, zero failures/errors/skips.
- Full command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`.
  BUILD SUCCESS: 26 unit/architecture + 43 integration = **69 tests**, zero failures/
  errors/skips. PostgreSQL/Mailpit Testcontainers, migration upgrade/checksum,
  architecture, formatting, Enforcer and dependency convergence ran successfully.
  Logs: `/tmp/social-core-r1-r3-before.log`, `/tmp/social-core-r1-r3-targeted.log`,
  `/tmp/social-core-r1-r3-verify.log` (local temporary artifacts, not committed).
- OpenAPI 3.1 validator 0.7.2, `docker compose config --quiet`, local Markdown
  file-link check, whitespace check and pinned Gitleaks 8.28.0 scan of exact
  staged source snapshot passed. Updated security, operations, testing strategy,
  OpenAPI and [Phase 1 evidence](phases/phase-1.md). No migrations/module APIs or
  accepted architecture changes; no ADR needed.
- Docker image/Compose HTTP smoke, remote CI and live Google/Facebook/production
  SMTP/TLS/keys/roles/backup checks were not rerun in this round. Prior implementation
  evidence is historical, not evidence on this fix commit. Existing external
  deployment-blocking gates remain in force. No push, deploy or Phase 2 work.

## Review checks and limitations

- Working tree was clean and HEAD matched the supplied Phase 1 commit at review start.
- Reviewer reran `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp verify`: BUILD SUCCESS, 25 unit/architecture + 34 integration = 59 tests, zero failures/errors/skips (2026-10-09). Existing tests do not exercise the exact R1/R2 interleavings or R3 GET budget.
- Workflow documentation: local relative links and `git diff --check` passed.
- No application feature code changed during this review. Docker image/smoke, Gitleaks and live-provider tests have not been rerun by reviewer; prior developer evidence remains attributed above.
- External provider/production gates remain in force regardless of local tests or review acceptance.

## Next action

Main reviewer: review proposed code commit `7aa6b47510f0c51cd959b54b0d46827a7266dba0`
against R1–R3 and the new deterministic regressions. Confirm checks as appropriate,
then resolve or request changes per finding; development has not self-approved
any finding or phase. This mailbox is committed separately after the code commit.
Do not start Phase 2 without a separate user assignment; no push/deploy authorized.
