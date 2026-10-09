# Agent handoff — current task

## Routing

- Task: phase-1-review-fixes
- Round: 1
- State: CHANGES_REQUESTED
- Last writer: main reviewer
- Next owner: development thread
- Reviewed code commit: `4ffbc875dd16602e55ccffdd0a82ea6aae889d86`
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

## Review checks and limitations

- Working tree was clean and HEAD matched the supplied Phase 1 commit at review start.
- Reviewer reran `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp verify`: BUILD SUCCESS, 25 unit/architecture + 34 integration = 59 tests, zero failures/errors/skips (2026-10-09). Existing tests do not exercise the exact R1/R2 interleavings or R3 GET budget.
- Workflow documentation: local relative links and `git diff --check` passed.
- No application feature code changed during this review. Docker image/smoke, Gitleaks and live-provider tests have not been rerun by reviewer; prior developer evidence remains attributed above.
- External provider/production gates remain in force regardless of local tests or review acceptance.

## Next action

Development thread: read AGENTS.md and this file, set IN_PROGRESS, fix R1–R3 only and necessary prerequisites. Add meaningful failing-before/passing-after regressions; run targeted tests then Java 21 `./mvnw -B -ntp verify`. Update phase/security/operations docs as affected. Commit fixes locally, fill developer responses and new code commit/check evidence here, set READY_FOR_REVIEW/next owner main, then commit the mailbox separately. Do not start Phase 2, push, deploy or self-approve findings.
