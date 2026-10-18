# Agent handoff — Phase 9A-I implementation

## Routing

- Task: phase-9a-i-erasure-foundation-identity
- Round: 1
- State: CHANGES_REQUESTED — review round 1; local synthetic only, not accepted
- Last writer: main reviewer, 2026-10-18
- Next owner: existing Phase9A-I development chat; fix both findings below
- Reuse chat: `Develop Phase 9A-I — Erasure Foundation & Identity`
- Accepted executable baseline: `d2a8e3c2b5942f1019ba2a48157da6ce3a5ed662`
- Accepted design: original `ac82f4f`, main decision/refinements `eab4da6`
- Actual implementation commit: `ed125b719ed47d5cb97a33157ad3e49977587376`
- Activation/assignment: `edb3aad`; this handoff is committed separately afterward
- Design archive: [Phase9A design review](handoffs/phase-9a-erasure-recovery-design.md).
- Prior executable review: [Phases7–8](handoffs/phase-7-8-notifications-search-moderation-ops.md).
- Main is docs/orchestrator/reviewer only; main alone may push origin.
- No spawned agents, concurrent writers, merge, release or deployment.

## Binding assignment and scope

Read AGENTS, thread/git workflows, README, implementation handoff, architecture,
roadmap9A, security, relevant subsystem docs, the [inventory](work-packages/phase-9a-data-inventory.md),
[ADR-017 including C01–C05](adr/017-account-erasure-and-recovery.md) and
[plan I.1–I.3 / T01–T20](work-packages/phase-9a-plan.md). Inspect actual APIs/jobs/
migrations and git status. Set IN_PROGRESS and write the concrete scoped plan.

Implement **all9A-I** sequentially in this single chat, with internal gates:

1. I.1 account request/prepared intent/step/resource state, fixed12-step roster,
   bounded reconciliation, current audited ADMIN progress/resume, version/audit/
   completion denials. Missing later owner handlers remain WAITING_DEPENDENCY;
   no unknown-kind jobs, fake DONE or completion based on enabled consumers.
2. I.2 independent PREPARED receipt before successful close, durable COMMITTED
   export/chunk/receipt mechanics, nonrecursive certificate model and safe scoped
   technical cancellation. Real durable isolated synthetic archive adapter/tests;
   no production authority claim or silent in-memory/runtime fallback. Network
   effects occur outside business locks; retry/fencing/DEAD stay authoritative.
3. I.3 identity proof/session/credential/mail cancellation and cleanup, profile/
   preferences/avatar release, fresh-proof new UUID, guarded email/provider claim
   drain and database-enforced handle reservation/reuse. Existing module DAG only.

C01 moderation pending-case semantics must be preserved in the protocol; its owner
implementation remains II. C02 certificate excludes its own final receipt/step.
C03 prevents dispatching unimplemented owners. C04 names/versions the synthetic
policy, disables destructive maintenance on existing/default environments and
fails strict close closed without the configured verified archive. C05 requires
resource export proof before losing its last owner enumeration mapping.

No9A-I account may reach DELETED: later owner proofs are not implemented.
Do not implement II/III moderation/source/media purge/projection/restore handlers,
scaffold fake ones, or claim end-to-end erasure. Narrow existing media release
API use by profile is in I scope; full media erasure is not.

## Migration, activation and data boundaries

V0001–V0032 unchanged; actual nextV0033. V0033–V0036 in the plan are tentative,
allocate only necessary real migrations after inspecting HEAD. No empty placeholders.
Account lifecycle/step APIs may not call higher-module internals; platform stays
technical, with no account business imports or foreign SQL consumer.

Use explicit isolated synthetic Testcontainers/local fixtures only. Do not enable
new destructive jobs/reconciliation against the existing developer Compose data,
.local data, real accounts/buckets/backups or a production endpoint. Do not perform
a real restore, select/configure production services, contact providers or request
credentials. U01–U06 remain open. Handle/retention defaults are local engineering
policy, not production consent or legal compliance. No Phase9B.

## Verification and delivery gates

For each internal capability, run relevant real-PG tests and keep failures.
Final I gate: targeted lifecycle/auth/admin/worker/identity/profile/handle/race/
archive/rollback regressions; Java21 formatting and full ./mvnw verify;
fresh/upgrade32/new migration prefix/checksums/constraints; OpenAPI/architecture;
real isolated app/media Compose startup/security probes; pinned redacted staged/
full-history secret scans and synthetic positive control, exact ignores unchanged.
Run unchanged committed heavy feed pool10/50 benchmark (all3200 successes) because
shared worker/cancellation/resource paths change. Do not relax pools/timeouts,
skip Docker tests or report only successful retries. Record executable manifest,
commands, environment, timings/outcomes and outstanding owner steps.

Test at least lost/duplicate PREPARED/COMMITTED receipts, current auth/version/
ownership recheck after export, no network under guards, proof/vector mismatch,
missing owner waiting without retry storms, >100-row paged credential chains,
old callback/reset/registration/mail versus release, concurrent handle claim/
rename/reservation/expiry, mandatory audit/checkpoint/fenced-ack rollback and
ten-attempt DEAD plus audited versioned resume. Synthetic archive persistence/
integrity faults must be real assertions, not mocks always returning success.

Deliver implemented API/schema/security/events/owner/runbook docs and
docs/phases/phase-9a-i.md. Commit verified internal slices without handing off each
small module. Finish I.1→I.2→I.3, then separate real-code-hash mailbox commit with
READY_FOR_REVIEW, results/limitations and C01–C05 response. A concrete incompatible
contract/blocker needs evidence and proposed decision; do not improvise.
Development never pushes or self-accepts. Main reviews before II activation.

## Carried limitations

Prior final independent suite388 PASS; first-run Phase4 fixture commit timeout
remains an unproven-cause operability follow-up, not fixed by this design.
Dense feed p95≈2.02–3.00s remains above500ms. Public launch, actual production
ledger/backup/key/provider/storage/admin/legal policy and full erasure remain gated.
No new executable code or application test run was produced by this main review.

## Review findings

No implementation finding yet. D01–D10 accepted only as qualified by ADR-017
C01–C05; U01–U06 remain external/product gates. Prior findings stay in archives.

## Development plan (I.1 → I.2 → I.3)

1. Inspect existing lifecycle/worker/proof/claim guards; add account-owned V0033
   request/roster/resource state and bounded owner APIs, strict negative completion
   gate, audited current-ADMIN progress/resume and targeted real-PG regressions.
2. Add an explicitly enabled/versioned synthetic policy and durable isolated
   archive adapter, conservative PREPARED/COMMITTED exports and verified resource
   receipts; add platform opaque scoped cancellation with job-first fencing.
   Verify integrity failures, crash/retry/DEAD/rollback and no unknown owner jobs.
3. Implement bounded identity cleanup/proof and claim drain plus profile/avatar/
   preferences cleanup and database-arbitrated handle reservations; verify stale
   callback/mail/reset negatives and fresh-proof registration with a new UUID.
4. Complete full Java21/migration/API/architecture/isolated Compose/security and
   unchanged pool10/50 feed benchmark gates. Preserve failed attempts, update
   implemented docs and phase evidence, commit verified slices, then separate
   real-code-hash READY_FOR_REVIEW handoff with C01–C05 responses.

No II/III handlers, DELETED transition, real-data jobs, restore, agents or push.

## Development delivery — all I.1 → I.2 → I.3

Code/docs commit `ed125b719ed47d5cb97a33157ad3e49977587376` contains the complete
assigned package, not a handoff after an individual module. Main has not reviewed
or accepted this implementation. [Phase evidence](phases/phase-9a-i.md),
[implemented protocol](account-erasure.md) and [operator runbook](../ops/erasure.md)
contain the detailed contract, full distributions, failures and operational limits.

- I.1: account-owned durable request/intents/fixed12-step roster, bounded scalar
  checkpoints and historical scan/integrity blockers; current audited ADMIN
  progress/resume with CSRF/If-Match/no-store, mandatory rollback and negative
  app/database completion guards. Community/graph/content/moderation/media/
  notification/search/completion remain8 WAITING_DEPENDENCY rows with no handlers
  or unknown-kind jobs. An empty account still cannot become DELETED.
- I.2: real restricted fsynced synthetic archive with immutable record/digest retry,
  verified PREPARED before close, full current authority/version/ownership recheck,
  COMMITTED/resource receipts and manifest chain. External effects stay outside
  business locks; owner state/checkpoint/audit/continuation/fenced ack are atomic.
  Nonrecursive11-prerequisite certificate model is tested, not successful finalization.
  Scoped job-first mail cancellation preserves error/attempt history and requires
  actual durable SMTP invocation-end evidence; expiry is not drain. Ten attempts
  mean DEAD; audited versioned resume preserves cumulative failures/history.
- I.3: bounded token/registration/OAuth/refresh/session removal, externally receipted
  last proof mappings, technical/50m anonymous-proof drain before email/provider
  claim release, fresh mailbox/provider proof and new UUID. Profile/preferences
  cleanup, avatar receipt before source release, blank versioned absence and
  DB-arbitrated30d handle reservation without former-user redirect are implemented.
  Actual object purge and projection/moderation/source erasure remain II.

No new module edge/dependency/SQL consumer, no historical migration edit, no
pool/timeout/lease/benchmark/secret-ignore relaxation. V0033–V0037 are concrete
owner migrations. V0037 is an I.3 correction for observed bulk-creation advisory
lock exhaustion: new profile creation uses the same unique canonical claim without
one advisory lock per row; bounded rename/reserve/expiry retain sorted keys. It
does not activate II. Main must reallocate future tentative migration numbers.
Default maintenance stays off; strict valid close fails503 without explicitly
configured verified isolated synthetic policy/archive. No runtime memory fallback.

## Actual verification and executable identity

Java21.0.12.1/Docker29.9.0, repository-pinned PG18.6/Silo. Evidence root:
`/home/vonhuphi/social-p9a-evidence.5qQBe3/`. All destructive fixture effects were
only in new isolated Testcontainers or project `social-p9a-delivery`;
no developer `.local`, old Compose volume, real account/bucket/backup or provider
was used. No backup restore was performed.

- Final executable/config/API345-file manifest SHA-256
  `ca69385e8d076d4884f4c66d27b0a2d218df7cd63ef1573ad65e98ce4f0ee488`;
  runtime-only288-file digest
  `c0f31ccd1bb53787b5cc4b5888527ff7f88ac7c6d9c95a7725615b693dd781d6`.
  Manifest rechecked unchanged before code commit; only handoff docs change afterward.
- Final targeted13 fast+25 IT PASS/48.242s, covering22 erasure tests and3 migration
  tests. Final full suite additionally checks the race loser's exact SQLSTATE23505.
  Real archive corruption/missing/restart/lost ack, close authority changes after
  append,205-row chains, claim/handle races, receipt mapping barriers, audit/lease
  rollback,10-attempt DEAD/resume and no owner dispatch/completion are assertions.
- Java21 `./mvnw -B -ntp verify`:62 fast+356 IT=418 PASS,0 failures/errors/skips,
  6m04s,2026-10-18 12:13:48+07 (`verify-final.log`). Formatting, architecture,
  OpenAPI, real-PG SQL-view/policy parity and old lifecycle/auth/media suites pass.
  Fresh/32→each33–37/checksums/retained states, failed-V36 atomic rollback then
  forward apply and20,050-profile/no-advisory-lock regression pass.
- Unchanged heavy benchmark command from assignment:3200/3200 successes across
  all16 pool10/50 scenarios,0 failures,5m14s,2026-10-18 12:19:07+07
  (`benchmark-final.log`).1M posts/10k follows/50 readers retained; raw SQL/plans,
  success-only p50/p95/p99, connection waits/JVM and full distributions retained.
  Dense p95 remains1.419–3.081s;500ms target and R02 remain open, not waived.
- Stock/resolved isolated Compose configs, real image build and private encrypted/
  versioned Silo bootstrap PASS. Actual runtime image
  `sha256:fe5a3f2f889e3320d612b7ff8c5d16be462393fef075bad0f2d38c69dca5aa90`:
  UID/GID1000,read-only root,1GiB/2CPU. Fresh `p9a_runtime` and actual V32→V37
  `p9a_upgrade` native probes PASS, separate lineage/archive per DB;32 checksums
  unchanged and seeded handle backfill retained. Readiness/liveness200, metrics401,
  Mailpit registration202/completion200, ordinary admin403, missing CSRF403,
  stale version412, receipt-backed close204, revoked session401, audited admin
  progress200/no-store/ETag. Both report `37|0|12` (versions/DELETED/steps), with
  8 later owners waiting. Running anonymous listings of both Silo buckets403.
- Sandbox app stopped first, then only its PG/Mailpit/Silo. No sandbox container
  remains running. Its named PG volume and sandbox keys/Silo data/archives in
  `/tmp/social-p9a-delivery.ugUiTz/` are retained, not erased. Fresh-prefix upgrade
  is not restore/no-resurrection proof or an old-binary rollback rehearsal.
- Pinned redacted Gitleaks8.28.0 staged64-file scan PASS; full history through the
  code commit68 commits PASS (`secret-staged-code.log`, `secret-history-code.log`).
  Exact ignore/rules unchanged. External synthetic generic-api-key positive control
  PASS (expected scanner exit1/one finding, wrapper0). No remote CI is claimed.
- Before code commit13 changed Markdown files/145 local links PASS; whitespace,
  staged diff and unchanged manifest PASS. Final mailbox checks are repeated before
  its separate commit; no redundant executable rerun for documentation-only changes.
- Final mailbox-only staged scan PASS (`secret-staged-handoff.log`); its8 local
  links and whitespace PASS. No executable file changes accompany this handoff.

## Retained failures and limitations — no successful-only report

The phase record preserves every recorded compile/fixture/targeted/full/benchmark/
probe failure and its disposition. On continuation2026-10-18 the earlier `/tmp`
logs/sandbox directories were absent; raw logs cannot be recovered or claimed
retained. Surviving prior XML was copied before rerunning gates. All new raw logs,
including failed benchmark/native probes, remain under the evidence root above.

- Earlier `full-2`:60 fast+353 IT,1 ERROR/7m48s,2026-10-17 23:46:30+07.
  Unchanged Phase4 fixture `deleteAndEarlyRejectionDeferQuotaReleaseWhileRealPutCapabilitiesRemainReusable`
  line603: S3 deleteObject SDK15000ms timeout. Post-fault memory available1136MiB,
  swap3784MiB; concurrent image build/idle sandbox correlation is **not proven
  cause**. Distinct from carried R01 JDBC commit timeout, neither claimed fixed.
  Later unchanged full passes are reported alongside, not substituted for it.
- `benchmark.log`:failed14.893s at20,050-profile seed with PG shared-lock memory
  exhaustion, before measured requests. Trace points to V0036 per-row advisory
  lock. Fixed by new V0037 plus bulk/concurrency regressions, not PG tuning or
  modifying the committed benchmark. Final full/benchmark rerun passes above.
- `probe-fresh.log`/`probe-fresh-retry.log`:client expected412/204 but received403
  after CSRF cookie rotation. Client now fetches current CSRF pairs before every
  unsafe call; original missing-CSRF negative still403. No production change to
  bypass denial. Final fresh+upgrade probes pass. Historical stopped-storage
  curl000/exit7 was sequencing error, never claimed403 policy evidence.

## C01–C05 developer responses for main disposition

- C01: moderation root/hold/redaction/review semantics remain untouched; its row
  waits for II. Existing pending-subject review/resolution stays live under ADR-016.
  Guarded subject inventory/proof invalidation/export/retention tests still need II;
  I does not claim that owner complete or impose a pending-case review ban.
- C02: certificate binds request/subject/generation/protocol/policy/roster/round/
  manifest and exactly11 current prerequisite proofs. Its own completion receipt,
  step version and final DELETED event are excluded; negative tests/app/DB guards
  pass. No successful completion/finalization is supplied in I.
- C03: fixed12 persisted rows,8 unimplemented waiting; real fenced worker does not
  dispatch missing kinds or infer DONE from disabled consumers/empty data. DEAD
  executions never gain an automatic fresh budget.
- C04: explicit `isolated-synthetic-v1`, default-off, validated isolated URL,
  configured lineage/restricted real archive, no fallback; strict missing archive
  fails close. Real durable fault tests/runtime pass. No production latest-cut,
  independent failure domain, immutable provider/anti-rollback or legal authority
  claim; U01–U06 remain open.
- C05: immutable≤100 resource chunks and verified receipts precede last identity
  proof/avatar mapping loss. Failure preserves retryable mapping and visible DEAD/
  resume history; real corruption/fencing/rollback tests pass. Source avatar
  release does not prove physical purge or future restore safety; those remain II/III.

These are development responses, **not reviewer resolution/acceptance**.

## Main review requested / next gate

### Main review round 1 — CHANGES_REQUESTED, 2026-10-18

Reviewed executable `ed125b719ed47d5cb97a33157ad3e49977587376`, delivered by
documentation-only `a9f4d78`. No implementation changes by main. Both findings
below are **code-inspection findings**, not newly reproduced runtime failures.
Existing green tests do not cover these interleavings. G1 is not accepted;
II/III remain inactive. C01–C05 developer responses remain preserved above;
there is no blanket acceptance of the proof/drain protocol.

Independent main checks on this unchanged executable:

- Java21 `./mvnw -B -ntp verify`: **418 PASS (62 fast +356 IT)**, zero
  failures/errors/skips,5m45s, finished2026-10-18 12:52:57+07. Includes formatting,
  architecture/OpenAPI and actual PostgreSQL/storage tests. Raw local log:
  `/tmp/social-p9ai-main-verify.log` (temporary, not committed).
- Developer's final345-file executable manifest rechecked: all hashes match.
  Diff against activation shows only new migrations V0033–V0037, not edits to
  V0001–V0032. Code diff whitespace and Compose app/media config checks pass.
- Pinned Gitleaks8.28.0 full history through `a9f4d78`:69 commits, no leaks;
  synthetic external positive-control wrapper passes (expected detection).
- Main did not rerun the heavy benchmark or native fresh/upgrade deployment;
  those remain the developer's attributed evidence, not independent reruns.
  No remote operation, production operation or code change in this review.

#### P9AI-R1 — P2 — Recheck onboarding expiry after lock acquisition (OPEN)

Anchors: `OAuthAuthentication.java:248–260`,
`JdbcIdentityRepository.java:425–434`, `Authentication.java:130–158`, and
`IdentityErasure.java:99–114` (all under the existing identity module).
`register` samples application time before `consumeOnboarding` executes its
UPDATE. The SQL expiry predicate uses that constant even after waiting for the
onboarding row lock. An attempt entering just before expiry can acquire the lock
just after expiry and still consume the capability; `initiate` then samples new
time and issues another full 30-minute registration intent. A short wait across
the boundary is sufficient, even within the configured lock timeout.

Required outcome: expired onboarding cannot mint a successor proof after a lock
wait. Audit the callback→onboarding→mailbox chain for the same time-of-check /
time-of-use issue and document a defensible absolute bound for the 50-minute
claim-release barrier. This review establishes the stale expiry check, not an
end-to-end reproduced post-erasure takeover. Do not claim that regression proven
without exercising the complete chain. Do not simply increase the wait constant
or weaken fresh-proof requirements. Use a consistent clock contract and either
post-lock validation or inherited absolute limits as appropriate.

Regression gate: deterministic real PostgreSQL row-lock/latch cases before, at
and after expiry; rejected consumption must roll back and produce no new intent
or mail. Cover the full old-proof/claim-release boundary and fresh-proof new-UUID
success, including any callback waits. Explain clock control rather than relying
on arbitrary sleeps. Preserve existing provider/CSRF/refresh denials.

#### P9AI-R2 — P2 — Bind recovery receipts to the exact request (OPEN)

Anchors: `account/application/Erasures.java:77–101,368–373` and
`account/application/ErasureExports.java:57–79` (under
`src/main/java/com/flyvo/socialcore/`). `preparedRecovered(A)` tests only whether
any request exists for A's user. `addExport` can mix A's intent/generation with
the current request B's timestamp. COMMITTED completion/failure likewise finds
the current RECOVERY_INTENT step by user, without matching intent/request.

Failure sequence: close attempt A obtains its PREPARED receipt, then fails the
current-version/authority recheck; a later valid close B at a new account version
creates request B. A's still-queued PREPARED job runs after B, fabricating
COMMITTED(A) although A never committed. Its receipt can mark B's recovery step
DONE before B's own COMMITTED receipt is verified. A stale failure can also
affect B's current recovery state. This corrupts immutable recovery history and
the proof gating owner execution; it is not a claim that an ACTIVE account is
directly erased. Existing post-network recheck tests stop before this later
request/delayed-delivery sequence.

Required outcome: all reconciliation and export completion/failure mutations
must bind to the exact immutable request/intent, lifecycle generation and
applicable current execution/round. An unmatched PREPARED is not evidence of a
commit and must remain uncommitted (or explicitly quarantined); do not rewrite
or delete archive history to mask the issue. Preserve legitimate same-intent
historical backfill and fenced retry/rollback behavior.

Regression gate: real PostgreSQL, real worker and archive; A rejected after
append, B valid at a new version, delayed A delivery after B while B's COMMITTED
export is held/failing. Assert no COMMITTED(A), no B-DONE/proof from A, no stale
failure poisoning B, and normal progression only from B's own valid receipt.
Also cover same-intent recovery, duplicate delivery and fenced rollback.

### Next development package — both fixes, one sequential handoff

Reuse the named existing development chat. Set IN_PROGRESS, preserve each finding
verbatim and append per-ID responses/evidence. First fix request/receipt binding
(P9AI-R2), then proof expiry/bound (P9AI-R1), then run combined regression gates.
Main does not prescribe a parallel abstraction or a new dependency. Stay within
I.1–I.3 and existing owner APIs. If a schema change is necessary, allocate V0038
or later after inspecting HEAD; never edit V0001–V0037.

Run targeted tests plus formatting/full Java21 `./mvnw verify`, denied-access,
real-PG concurrency, architecture/OpenAPI and affected operational checks.
Update protocol/phase docs and executable evidence manifest. Rerun runtime or
benchmark checks when affected; distinguish new checks from prior evidence and
retain failures. Commit verified code first, then a separate mailbox commit
with its real code hash, READY_FOR_REVIEW and next owner main. Only main resolves
these IDs. No push, agents, II/III, real erasure/restore or deployment.

Review `ed125b719ed47d5cb97a33157ad3e49977587376` against ADR-017 C01–C05 and the
whole I.1–I.3 assignment. In particular disposition the implemented owner protocol,
fenced external cancellation/drain,50m proof-chain engineering bound, handle claim
creation-lock refinement/V0037 allocation and default503 close policy. Decide G1
acceptance or findings; only main may resolve IDs/activate II and reallocate its
tentative migrations. No new ADR is self-accepted, and no II/III code was implemented.

Public launch remains blocked on U01–U06, all later owner/source/media/moderation/
projection proofs/finalization, true independent latest-cut/backup/key authority,
writer quiescence, recovery exposure attestations/drills, compatible previous
Phase9 artifact/forward-fix rehearsal and target-environment performance/operations.
Unproven SMTP invocation end stays waiting; historical baseline cannot certify old
backups. No account in I can reach DELETED. No real erasure/restore, agents, push,
release, deployment or Phase9B occurred or is authorized by this handoff.
