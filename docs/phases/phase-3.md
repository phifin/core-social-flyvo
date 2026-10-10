# Phase 3 — Social graph

Implemented locally and accepted by independent main review on 2026-10-10. Baseline
`73bb08d4442a92c800d0e20c1d8835963f0c5966`; Phase 2 acceptance is preserved in
[the archived mailbox](../handoffs/phase-2-communities.md). Actual code commit is
recorded in the subsequent [handoff](../agent-handoff.md), not self-referenced here.

Independent round 1 accepted code `bfda6e856d42282eaccd2ed70a81f49c416f86b7`
with no open findings. Reviewer reran the targeted 65-test gate and full 124-test
`spotless:check verify` gate, both with zero failures/errors/skips, plus OpenAPI
and Compose validation. See the mailbox for inspection scope and limitations;
the verification section below preserves the original implementation evidence.

## Delivered scope

- Distinct follow, request/friendship, block and mute commands with current account
  checks, canonical pair guards, idempotent set operations and explicit request roles.
- Block removes both follows, friendship and pending request atomically; unblock
  restores nothing. Request expiry is authoritative before worker cleanup.
- Retained pair revisions, transactional after-state outbox facts, block audit and
  idempotent bounded-worker expiry jobs; no future consumer enabled.
- Bounded graph/request pages and batched active-account filtering; profile FRIENDS
  and bilateral block policies; profile-authorized public graph lists.
- Published graph/account views, updated OpenAPI and [graph contract](../graph.md),
  schema/security/architecture docs and [operations](../../ops/graph.md).

V0012 creates owned graph tables/constraints/indexes/views; V0013 publishes
account_api.active_users_v1; V0014 enables FRIENDS profile visibility. V0001–V0011
are unchanged. Clean migration and V11 upgrade with preserved profile/checksums
are tested. No new dependency, service, cross-module table read or architecture
test exclusion. No content/feed/notification/search/tenant implementation.

## Verification

Java 21 and real pinned PostgreSQL Testcontainers, no H2 or skipped tests:

```bash
./mvnw -B -ntp spotless:apply test \
  '-Dtest=ArchitectureTest,PersistenceBoundaryTest' \
  failsafe:integration-test failsafe:verify \
  '-Dit.test=GraphIT,FoundationIT,AuthenticationIT'
./mvnw -B -ntp spotless:apply verify
docker compose config --quiet
docker build --tag social-core:phase3 .
```

- Targeted gate: 8 unit/architecture + 56 integration = 64 pass before the final
  large-graph test was added. Final full gate: **124 tests**, 29 unit/architecture
  + 95 integration; zero failures/errors/skips. GraphIT 16, FoundationIT 11,
  AuthenticationIT 30, CommunityIT 34, OAuthAbuseIT 4.
- Formatting, Enforcer, dependency convergence, module DAG/persistence boundaries
  and transactional PostgreSQL/Mailpit/provider regression tests pass.
- Graph coverage includes accept/opposite-request/follow-block races, both shared
  interaction-guard/block orderings, rollback of source/events/jobs, terminal retry
  without regrant, duplicate/stale expiry, current actor/session/privacy denial,
  every command HTTP route, opaque cursor scope/tamper/expiry, view parity and
  10,000-edge indexed point lookup plus bounded page.
- OpenAPI 3.1 validates with isolated openapi-spec-validator 0.7.2; Compose config
  validates. Docker image `social-core:phase3` builds successfully. Markdown local
  links and whitespace checks pass. Pinned Gitleaks 8.28.0 scanned the staged
  index-exported snapshot with no leaks; ignored keys/build artifacts excluded.
  Log: `/tmp/social-core-phase3-gitleaks.log`. Only delivery evidence and the user's
  thread-role clarification were added after that scan.
- Initial failures were corrected: test clock bean collision, obsolete profile
  visibility/version expectation and an overly specific EXPLAIN assertion (both
  primary and reverse follow indexes are valid). Final verify passed afterward.
  The first concurrent Docker build copied the test before formatting completed;
  its formatting check failed. Rebuild uses the verified formatted source.
- Local logs: `/tmp/social-core-phase3-targeted.log`,
  `/tmp/social-core-phase3-verify.log`, `/tmp/social-core-phase3-docker-build.log`.
  These are temporary local evidence, not repository artifacts.

## Refinements and remaining gates

Routine refinements: 30-day requests; UUID-ascending bounded keyset pagination;
15-minute purpose/viewer/owner-scoped encrypted cursors reuse existing platform
Secrets to avoid exposing filtered candidate UUIDs; canonical unsigned UUID order
and a documented shared/exclusive pair-lock key. No accepted decision was changed
and no superseding ADR is needed. Request creation uses domain pending-pair
uniqueness, not the optional generic Idempotency-Key facility.

Short/empty filtered pages can carry a continuation; no snapshot across requests.
The local 10,000-edge query plan is not a capacity/latency guarantee. Reads have
the documented weak consistency; authorization never uses event projections.
Retention/erasure remains Phase 9A. Container HTTP smoke and remote CI have not
been rerun for this phase; HTTP boundary coverage above is MockMvc integration.
Production keys/TLS/roles/SMTP, live providers, shared rate limiting, operator MFA,
restore/load/retention and public launch gates remain. No push or deployment.
