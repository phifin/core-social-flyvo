# Agent handoff — Phases 7–8

## Routing

- Task: phase-7-8-notifications-search-moderation-ops
- Round: 2
- State: READY_FOR_REVIEW — round2 stabilization verified; P78-R1 awaits main disposition
- Last writer: development, 2026-10-17 — fix d2a8e3c2b5942f1019ba2a48157da6ce3a5ed662
- Next owner: main orchestrator/reviewer — independently review P78-R1 and package
- New chat: `Develop Phases 7–8 — Notifications, Search & Moderation Ops`
- Accepted baseline code: `32c1b1eb8609eeb8c61278e29efbedba14008db9`.
- Main acceptance commit: `6dc06d6`.
- Prior findings P56-B1/R1/R2: resolved by main. Full history/evidence is preserved
  in [accepted Phases 5–6](handoffs/phase-5-6-interactions-reports-feeds.md).
- Assigned scope: [full Phases 7–8 package](work-packages/phase-7-8.md), A → B → C → D.
- Main accepted [ADR-015](adr/015-notification-stream-authentication.md): narrow
  notification-to-identity API proof for SSE lifecycle; implemented in B, not phase acceptance.
- Main is orchestrator/reviewer only and exclusively performs origin pushes.
  Development must not push, spawn agents, merge, release or deploy.

## Assignment

Read the complete linked package and required contracts before implementation.
Inspect status, actual baseline and existing APIs. The initial activation allocated
from V0027 after V0026; actual code now has V0027–V0032. Preserve all applied
V0001–V0032; next migration is V0033, verify actual HEAD before any later change.

Implement sequentially in this single new chat: durable envelope + semantic inbox,
authenticated SSE + full Phase 7 internal gate, then PostgreSQL search and scoped
moderation operations + final package verification. Do not hand off per small slice.
Commit verified slices; passing an internal gate allows continuing only the assigned
package, not self-acceptance. No Phase 9 implementation. A concrete unresolved
contract/resource blocker must include evidence and a decision proposal.

Deliver separate Phase 7/8 evidence, exact tests and lag/resource measurements,
schema/API/security/operations/README updates. Commit code first; then a separate
mailbox commit with actual hashes, limitations and READY_FOR_REVIEW to main.

## Carried-forward limitations and regression gates

- Main independently verified 260 tests and 3,200/3,200 benchmark requests at the
  accepted baseline. Source/body coherence, current privacy, source-selective
  feed work, short owner read transactions and all prior tests remain mandatory.
- Dense feed p95 still misses 500ms (main 1.7–2.7s); no production capacity/SLA
  claim. Do not raise pool/timeouts, hide failures or add authorization caches.
  Rerun the heavy pool10/50 benchmark if owner query/transaction/resource changes
  affect feeds, as specified in the package.
- Storage/provider/decoder/key/retention/admin gateway and public-launch gates
  remain separate. ADR-015 does not authorize credential access or identity SQL.
- SQL view consumer allowlist remains content-only; notification/search call
  owner APIs. Existing worker receipts/fencing/atomicity remain authoritative.
- Two exact historical secret fingerprints only; no scanner weakening/history
  rewrite. Full-history scan plus synthetic positive control remains required.

## Implementation report

### Main remote checkpoint — 2026-10-14

Main performed normal fast-forward push to
`origin` = `https://github.com/phifin/core-social-flyvo.git`, branch `master`,
from `03f8a98` to `ff2e6f75c0e4ed20d4e34c494ab837aeef3be032` and verified that
exact remote hash with ls-remote. This delivers accepted Phases 5–6 plus the
documentation-only Phase 7/8 assignment/ADR, not implemented Phase 7/8.
Final pre-push full-history pinned redacted scan: 47 commits, no leaks; staged
coordination patch and changed-document links pass. Archive preservation was
checked against acceptance commit, allowing only relative-link adjustment.
Remote CI result has not been checked; local verification is recorded in the
accepted archive. No deployment/release or dev-thread push is authorized.

Development inspected clean `master` at `14c983b`, accepted executable baseline
`32c1b1e`, and migrations V0001–V0026. Docker Engine 29.8.2 is available. The
shell defaults to Java 8; verification will explicitly select the installed
`/usr/lib/jvm/java-21-openjdk-amd64`. No dependency or runtime version change.

Concrete execution plan: [development plan](work-packages/phase-7-8-plan.md).
Order is A → B → full Phase 7 gate → C → D → full package gate. Each coherent
verified slice is committed locally; no intermediate owner handoff. No findings
have been disposed of, no phase accepted and no remote operation authorized here.

Historical internal checkpoint (not a handoff): A commits `beebb0a`/`ecb11f6`;
B code `af0742750cd1f1b00a02a1afcd6aa3d80d13db74`. The full Phase7 internal gate
passed298 tests (54 fast +244 PG executions), zero failures/errors/skips and no
forced JVM exit. Spotless, architecture/OpenAPI, fresh/upgrade27 checksums/data
and pinned staged secret scan pass. [Phase7 evidence](phases/phase-7.md) preserves
all earlier failed native teardown/shutdown gates, actual fixes and measurements.
Single-instance SSE uses verified identity proof/current checks, aligned Tomcat
11.0.27 plus native backpressure abort, and early graceful-shutdown lifecycle.
That checkpoint allowed development C → D only; it did not accept either phase.

### C delivered and D lifecycle prerequisite — 2026-10-16

- A envelope `beebb0a`; semantic inbox `ecb11f6`.
- B `af0742750cd1f1b00a02a1afcd6aa3d80d13db74`, Phase7 internal full gate298 PASS
  before C. [Phase7 evidence](phases/phase-7.md) retains prior failed attempts/fixes.
- C `79da0ca692845a55e80cc7f67d6a4e5663845864`, source-authorized search/rebuilds,
  full317 PASS (55 fast +262 integration), zero failures/errors/skips/forced exit.
  V0028 and all earlier checksum/data/permission/feed/worker regressions pass.
- D account-lifecycle prerequisite **only**:
  `cd51ecae3d92a402fa8c70b1102501cafb857ffa`. V0029 account/session epochs;
  guarded/versioned ADMIN owner suspend/reinstate, self/peer ADMIN denial,
  current epoch checks so reinstatement cannot revive old sessions. No reverse
  identity dependency, community/content/role mutation or new HTTP endpoint.

Historical implemented-code verification before P78-D1 adjudication:

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`
  **323 PASS =55 fast +268 integration**, zero failures/errors/skips, no forced
  fork exit,5:02, finishing2026-10-16 18:32:55+07. Log
  `.local/evidence/p78/d-lifecycle-full.log`; targeted81 PASS precedes the extra
  legacy-suspension upgrade fixture. [Phase8 evidence](phases/phase-8.md) contains
  exact test matrix, resource measurements and earlier C failures, not just totals.
- Fresh29 and upgrade28 preserve all prior checksums/data; existing ACTIVE sessions
  keep epoch0, already SUSPENDED accounts start at1 so their old sessions stay
  denied. All22 reports,19 feeds,34 auth and prior scope/privacy/worker/media tests
  pass. Independent audit/outbox failure rolls status/version/epoch/effects back;
  verified-password login loses to concurrent suspension before the guard.
- Native SSE suspend→immediate reinstate closes old session in5098ms (<15s),
  replayed stream401, native async/servlet allocations0. Other closure/resource
  assertions unchanged; full-suite search lag max20.151612654s on10 posts/one owner,
  actual default scheduler;50-result skew fixture6 SQL queries. No SLA/capacity claim.
- Spotless/Enforcer/architecture/OpenAPI pass. Pinned v8.28.0 redacted gitleaks:
  implemented code history **53 commits, no leaks**, staged code patch no leaks;
  synthetic positive control detects1 expected generic-api-key outside git and
  passes. Logs `.local/evidence/p78/d-history-code-scan.log`,
  `d-lifecycle-staged-scan.log`, `d-secret-positive-control.log`.
  The two exact historical fingerprints remain unchanged, no history rewrite.
- Blocker-only documentation: changed local links and git diff whitespace pass;
  its separate staged patch passes the same pinned redacted scanner
  (`.local/evidence/p78/d-blocker-docs-staged-scan.log`). No executable change
  follows the successful323-test run; no redundant suite is claimed for these docs.

### Full package delivery — READY_FOR_REVIEW, 2026-10-16

Final D code: **`bbb85004454ca77be2af2df43bc4133490ab8bea`**. Earlier A/B/C/D
prerequisite commits above remain in history, with the full Phase7 gate298 passed
**before** C. Main's design decision152a7c9 is implemented, not self-adjudicated.
No new blocker and no Phase9/remote write/agent/deployment work.

D delivers lossless V0030 private payload split, V0031 assignment/hold/history/
retention controls, verified bounded legacy encryption, irreversible bounded
redaction, scoped filtering/assignment/history/evidence and account moderation
HTTP. Protected current-ADMIN metrics reuse the existing registry; real static
12-panel dashboard and migration/retry/key-lifetime runbooks are delivered.
All docs/API/schema/domain/security/operations are updated; see [Phase8 evidence](phases/phase-8.md),
[plan](work-packages/phase-7-8-plan.md), [runbook](../ops/moderation.md).

Response to main's P78-D1 implementation conditions (design resolution preserved
below, main alone reviews/accepts implementation):

- Action metadata/platform audit guards remain unconditional/unchanged. Report
  identity stays immutable; one-to-one payload/tombstone is deferred-enforced.
  No substitution, parent reassignment, DELETE/reinsert or post-redaction restore.
  V0001–V0029 bytes/checksums unchanged; only new V0030/V0031 migrations.
- SQL proves counts/complete values before old columns drop; controlled writer
  stop documented, no mixed old/new binaries. New writes are encrypted only.
  205 legacy rows converge100/100/5 after full application decrypt/JSON comparison;
  crypto/verification failures retain data. DB proves transitions, not external-key
  semantics or resistance to database owner/superuser. No network under locks.
- DB-time365-day eligibility, unresolved/current-hold exclusion, explicit audited
  scope/version/expiry controls and one common root for linked actions. Both
  hold-wins/cleanup-wins races tested; assignment never releases holds.
  NONE→STARTED→DONE irreversible; late hold fails,206 payloads converge100/100/6;
  DONE requires no remaining private bytes. Keys retained while any live reference.
- Raw-SQL regressions target actual new storage plus metadata, not removed columns.
  Real audit/next-job/fenced-ack failures roll back root/payload/history/continuation
  and committed-only metrics;102-payload worker retry converges. Scope is proved
  before any payload access/root lock, including legacy history. Account status/
  epoch/action/audit/outbox rollback and old-session denial remain synchronous.

Final checks on the same executable source:

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`:
 **340 PASS (57 fast +283 PostgreSQL)**,0 failures/errors/skips, no forced JVM
 exit,5:19, ending20:07:05+07. Log
 `.local/evidence/p78/package-full-verify-scope-final.log`. Targeted scope gate50
 PASS precedes it; older339/323/317/298 runs are separate historical evidence.
 Spotless/Enforcer/OpenAPI/7 architecture/3 persistence tests pass; fresh31 and
 upgrade29 preserve data/checksums/guard definitions. Reports23/auth34/feeds19/
 foundation21 and prior privacy/media/worker regressions retained. Failed earlier
 compile/fixture/native/resource attempts remain in ignored logs, not hidden.
- Actual search default1s scheduler10-post/one-owner notification+search burst:
 completion20,264ms/max lag20.131551974s;50-result/10k-ballast query6 SQL. Native
 SSE suspend→reinstate5060ms, ordinary revocations5083–5100ms, outage8064ms,
 slow readers203–259ms/queue≤2, native resources0, open-stream shutdown17ms.
- Real app/media Compose image build/startup/bootstrap and HTTP readiness/
 liveness UP. Isolated `social-p78-gate` project/PG volume/Silo data bind preserves
 original data/keys; media enabled, private bucket listing403, anonymous admin
 metrics401/actuator Prometheus401. Both app/Silo UID1000,read-only root FS,
 1GiB/2CPU. Real PG31 migrations/0 legacy rows. Logs `compose-final-{silo,bootstrap,app,stop}.log`.
 Task app stopped before dependencies after probes; recoverable volumes/data
 remain, no deletion. Running-container status was not substituted for readiness.
- Exact committed FeedBenchmarkTool,1million posts/50 readers, pools10/50:
 **3200 attempts/3200 successes/0 failures**; each pool500 first+500 steady+
 6×100 sparse requests.15 prerequisite tests+1 heavy tool PASS,4:51, finishing
 20:20:31+07. Log `package-feed-benchmark.log`. Dense p95 pool10 first2259.538/
 steady1884.616ms; pool50 first1898.888/steady1411.877ms. Sparse p95 and hardware/
 JVM/DB/SQL/plan/outcome details are in Phase8. Initial500ms target still unmet,
 not a hidden error or a production capacity/SLA claim; no pool/timeout/cache
 relaxation. Task Compose was stopped before benchmark.
- Executable SHA-256 manifest `final-source-sha256.txt` remains identical across
 full340/Compose/benchmark and precommit; only evidence/status docs follow full
 verification. Pinned redacted gitleaks v8.28.0 history **56 commits including
 bbb8500**, no leaks (`package-history-code.log`); staged code patch no leaks
 (`package-code-staged-scan.log`). Synthetic positive control detects1 expected
 outside-git generic-api-key and passes (`package-secret-positive-control.log`).
 Both exact historical fingerprints unchanged; no scanner weakening/rewrite.
 Mailbox-only patch passes diff/link/staged scan separately
 (`package-mailbox-staged-scan.log`); final post-mailbox history scan is retained
 in `package-history-final.log`, with no self-reference to this documentation
 commit's not-yet-created hash.

Evidence directory: ignored `.local/evidence/p78` (mode700). Java21.0.12.1,
Boot4.1.1/aligned Tomcat11.0.27, pinned PG18.6, Docker29.8.2, i7-1165G7/8 logical
CPUs/7.5GiB RAM/4GiB swap/shared desktop. No executable changes after final gates;
this handoff is a separate documentation commit referencing the real D code.

Limitations: dense feed p95<500ms remains unmet. Production write-stop/large-data
migration capacity, external provider/storage/decoder proof, shared-key lifecycle,
backup/restore-erasure and audit metadata retention, protected admin/metrics
gateway/public-launch policy remain separately gated. Engineering holds are not
legal holds, and no online key rotation/Phase9 erasure is claimed. No phase
accepted, push, remote CI result, release or deployment. Main should independently
review the **complete** package and respond with findings/acceptance in this mailbox.

## Main decision P78-D1 — RESOLVED (design only), 2026-10-16

Inspected actual HEAD `e3a20a1`, code `cd51ecae3d92a402fa8c70b1102501cafb857ffa`,
V0010/V0023 triggers, raw-SQL regression expectations and the D retention contract.
The conflict is real: current immutable private columns cannot support the assigned
redaction without a deliberate storage boundary. Main **accepts ADR-016's owned
payload split and explicit engineering holds**, with its added implementation
conditions. No runtime trigger exemption, privileged worker-role invention or
platform audit mutation is approved. No executable change was made by main.

Cases: 365 days after terminal resolved_at, excluding unresolved/currently held.
Standalone actions: created_at +365 days, scoped hold. Holds require current scoped
staff/global ADMIN, optimistic version, mandatory audit and explicit expiry at most
365 days per decision; renewal is explicit. This is not a legal-hold product.
Action metadata/report identity remain immutable. Lossless migration, one-way
payload transitions, verified encryption of legacy data, no resurrection, common
root locking and bounded multi-pass redaction are mandatory. Read ADR-016 in full.

Resume **all remaining D** in `Develop Phases 7–8 — Notifications, Search & Moderation Ops`:
assignment/filtering, attributable history/queue reads, payload migration/encryption,
holds/retention, separate account moderation HTTP operations, dashboards/runbooks.
Update the concrete plan, allocate after actual max V0029 (currently V0030), then
implement coherent verified slices without another small-slice handoff. Preserve
prior checksums and A/B/C/current lifecycle regressions. Run complete final package
checks, including real app/media Compose startup, full verify, pool10/50 benchmark
with all 3,200 requests successful, OpenAPI/architecture, migration and pinned
history/positive-control scans. Retain failures and latency limitations honestly.

This is blocker adjudication, **not a full code review or Phase 7/8 acceptance**.
The developer's 323-test report remains developer evidence; main did not rerun the
suite for this docs-only decision. Main checks documentation links/diff/secret scan.
No push while the package remains incomplete/unreviewed. Commit verified code,
then a separate real-hash mailbox READY_FOR_REVIEW. No agents or Phase9.

### Historical development request P78-D1 — resolved by the decision above

The following request is preserved verbatim as historical evidence of the original
blocker, not current routing/status. ADR-016 is now main-accepted for implementation,
D is implemented, and current delivery is READY_FOR_REVIEW as recorded above.

This is not a handoff after a small task or a phase completion claim. D cannot
finish under the current immutable-private-payload contract without selecting a
new retention security boundary. Proposed [ADR-016](adr/016-moderation-private-payload-retention.md)
records evidence, concrete recommended migration/policy, alternative and tests.

Evidence: V0010 moderation_action_immutable unconditionally rejects UPDATE/DELETE,
including private reason/before_state/after_state; V0023's report identity guard
rejects evidence/key changes and requires nonempty ciphertext. Accepted raw-SQL
immutability regressions still pass in the latest full suite. D nevertheless
requires365-day private payload redaction, explicit bounded holds and retained
attributable metadata, without an application audit-trigger bypass. The existing
"controlled owner-role process" blueprint does not select an executable
moderation exception. Development must not silently redefine accepted immutability
or invent a privileged runtime role/hold policy.

Decision requested: main approve ADR-016's owned private-payload split and explicit
engineering holds (current scoped staff/global ADMIN; versioned audited controls;
explicit expiry≤365 days per decision; renewal explicit, not automatic), or specify
the separately privileged owner-role alternative and its exact worker boundary.
Recommended split keeps action metadata and platform audit triggers unconditional,
migrates legacy private data losslessly, encrypts new action details, and permits
only verified legacy encryption/eligible irreversible payload redaction.
ADR remains **Proposed, not accepted or implemented**. No legal compliance claim.

No moderation payload/trigger/hold/cleanup change has been made. Assignment,
filtering, attributable history/queue reads, holds/retention, separate account
moderation HTTP operations and dashboards remain unfinished. Full app/media
Compose startup, committed pool10/50 benchmark all3200-success and final post-D
package gates remain required, not waived or reported as run. Existing unmet
feed p95<500ms and external launch limitations above remain unchanged.

After main's decision, the user returns to this development chat; reread mailbox,
verify HEAD, set IN_PROGRESS, implement the remaining D and run all package gates.
Only then create real-hash READY_FOR_REVIEW handoff. Current state is deliberately
BLOCKED, **not READY_FOR_REVIEW**; neither phase accepted. No push, agents or Phase9.

## Review findings

### Main round 1 — CHANGES_REQUESTED

Reviewed actual code `bbb85004454ca77be2af2df43bc4133490ab8bea`, handoff `46dec7d`,
against accepted Phases 5–6 baseline. Working tree/index initially clean. Inspected
event delivery envelope/receipt transaction, notification projection/read paths,
SSE auth/lifecycle, search source authorization/versioned rebuild, account epoch
changes and moderation migration/holds/crypto/cleanup boundaries with their tests.
P78-D1 remains design-resolved under ADR-016; no new architecture decision is
needed for the finding below. Neither phase is accepted and Phase9 stays inactive.

Independent main checks on the delivered executable baseline:

- Java21 `./mvnw -B -ntp verify`: PASS, 340 tests (57 fast + 283 integration),
  zero failures/errors/skips, exit0, 7m50s, finished 2026-10-17 01:06:30 +07.
  Log: `/tmp/social-p78-main-verify.log`; no forced-exit warning found. This
  passing suite does not cover the initial-activity ordering gap below.
- Final-source manifest: all 302 files match. Existing migrations unchanged;
  delivered schema additions are V0027–V0031.
- Compose app/media profile config validation: PASS. Main did not repeat actual
  Compose startup or the heavy pool10/50 benchmark this review round; those
  remain developer evidence, not independent main measurements.
- Pinned redacted Gitleaks history through `46dec7d`: 57 commits, no leaks;
  external synthetic positive control PASS. Historical allowlist unchanged.
- Review-only edits are mailbox and Phase7/8 records; no executable changes,
  acceptance, push, deployment or new phase activation.

#### P78-R1 — P2 OPEN: source updates can permanently consume initial notification activity

Anchors: `NotificationProjector.java:139–142` (reaction source-version equality
and newActivity), `:125–126` (comment/post update activity), `JdbcInbox.java:47–53`
(version rejection and activity), `:64` (requires old group or new activity),
`:117–127` (persists latest source version even without a group).

This is a **code-inspection finding**, not a claimed independent runtime
reproduction. Concrete normal-delay path, with active/eligible distinct actor and
recipient, enabled preferences and no initial notification/source_state:

1. Actor creates LIKE v0 then replaces it with LOVE v1 before the worker runs.
2. Deliver v0 first. Current source is v1, so projector sets active=false at
   line140; JdbcInbox stores source_state v0 with notification_id=null.
3. Deliver v1. It is active but previousType=LIKE, so newActivity=false. With no
   old group, line64 skips group/contribution creation, yet latest_source_version
   becomes1 and active=true. Both real delivery receipts can commit successfully.
4. No later event is required. REST inbox/unread stay empty; replay of v0 or v1 is
   rejected by latest_source_version. This loses initial social activity even
   with in-order delivery, merely because the worker lagged behind a type change.

Delivering v1 before v0 loses it as well. A related path exists for comments:
deliver CommentUpdated v1 before CommentCreated v0 for an otherwise eligible
visible comment. The update has newActivity=false and no old group, advances
source_state, then the create is rejected. Existing tests materialize the initial
reaction before type changes, or cover delete/reactivation orders; they do not
cover these missing-initial-activity paths. Source-version dedup must prevent
resurrection/inflation, not silently acknowledge away the original activity.

Required outcome: preserve exactly one initial eligible activity/contribution
through delayed/reordered updates while retaining current source authority,
stable source versions, no new unread for a type-only update to an already
materialized group, and no resurrection after deletion or retention. Do not fix
this by treating every update as a new notification, disabling version checks,
requiring globally ordered delivery, or creating activity from a denied source.
Distinguish ordering state from whether initial activity has been materialized;
record the concrete algorithm/schema rationale before implementation. Preserve
preference/self/block/mute and source-expiry semantics rather than guessing from
notification_id=null alone (it can also mean suppressed or pruned state).

Required real-PG worker regressions: LIKE→LOVE before any dispatch with v0→v1
and v1→v0; CommentUpdated-before-CommentCreated; retry/duplicate delivery and
source-state/receipt rollback; delete-before-create still invisible; existing
read group + type update still read; cross-hour reactivation still one new
activity. Test privacy/preference suppression and retention against any new
reconciliation logic. Use real producer events and receipt/effect/fenced worker,
not only direct repository Change construction or mocked successful delivery.

### Next development assignment

Reuse `Develop Phases 7–8 — Notifications, Search & Moderation Ops`. Set
IN_PROGRESS, round2, retain this finding and actual code baseline. Deliver one
coherent stabilization pass: regression-first P78-R1 correction, the related
lifecycle/order matrix, and complete package regression/evidence refresh. Preserve
V0001–V0031; allocate V0032+ only if needed after checking actual maximum. Do not
implement fixes in main or start a new phase. No push/agents/self-acceptance.

Run targeted notification/worker/SSE and full verify with Java21, formatting,
OpenAPI/architecture, migration checks, Compose config, pinned history scan and
positive control. Re-run actual Compose startup for changed runtime wiring/schema;
re-run the committed pool10/50 benchmark if changed shared owners/worker/resource
paths affect feeds. Retain the package's earlier successful heavy evidence but
never present old runs as new final-source measurements. Commit verified fixes,
then separate real-hash READY_FOR_REVIEW mailbox responding to P78-R1.

## Development round2 response — P78-R1, READY_FOR_REVIEW

Fix code: **`d2a8e3c2b5942f1019ba2a48157da6ce3a5ed662`**, based on main5012a83 /
prior executablebbb8500. P78-R1 remains **OPEN for main**; this is a proposed
correction with evidence, not developer resolution/phase acceptance. Main's
original finding, review evidence and P78-D1 design adjudication remain unchanged.

### Regression-first reproduction and selected correction

Before production changes,3 real-PG producer/worker regressions fail exactly as
reported: delayed LIKE→LOVE v0→v1, reordered v1→v0, CommentUpdated-before-Created.
Both actual notification receipts commit, yet inbox is empty (size1 expected),
no test errors/skips. Log `.local/evidence/p78/r2-initial-activity-before.log`.

The [written plan](work-packages/phase-7-8-plan.md) selected the algorithm before
implementation: new V0032 adds activity_decided_version independently of current
source ordering. An eligible ordinary update can advance ordering while leaving
the real origin pending; it never synthesizes activity. Only unexpired Created/
reaction null→type evidence may settle a new/undecided activity, even behind newer
ordering, with unchanged current source/route/account/preferences/block/mute proof.
Decision-through current snapshot coalesces superseded activity, denial/deletion
closes old origins, and a newer genuine reactivation still creates one new-hour
activity. Pruning preserves decisions; NULL group FK is not eligibility.

One bounded content snapshot refresh follows sorted recipient guards, preventing
an old pre-lock snapshot from losing an origin after another worker's update.
Group/contribution/decision/watermark/receipt/fenced ack remain atomic. No owner
API/SQL, producer envelope/payload, shared worker framework, pool/timeout, public
REST shape or dependency change. V0001–V0031 untouched; only new V0032. Subsystem/
schema/domain/security/OpenAPI descriptions/runbook/phase records are updated.

Legacy limitation: old NULL-FK states cannot distinguish suppression/pruning from
already-acknowledged initial loss. Migration conservatively decides all old rows
through their stored version; no automatic historical repair/receipt reset or
mixed old/new writer rollout is claimed. This preserves the required denial/
deletion/retention boundary rather than guessing from missing groups.

### Completed lifecycle/order regression matrix

All use real PostgreSQL producers/registered receipt-effect-fenced worker, not
mocked successful delivery or direct repository Change fixtures. Spies inject
faults/barriers and call real methods. Named cases live in NotificationsIT.

| Required path | Actual coverage/outcome |
|---|---|
| LIKE→LOVE before any dispatch | `initialReactionSurvivesTypeChangeBeforeFirstDispatchInEitherOrder`,both orders;2 receipts,1 activity/group/actor/unread,current LOVE; read and receipt-free replay stay read |
| Comment update before create | `commentUpdateBeforeCreatePreservesExactlyOneInitialActivity`; explicit pending decision, canonical create-event group once; later edit/replay no unread |
| Full unmaterialized lifecycle order | `currentReactionBurstCoalescesOneActivityInAllTwentyFourOrders`;all24 create/delete/reactivate/type permutations, current SUPPORT/version3, one activity; receipt-free replay cannot inflate |
| Existing read group/cross-hour reactivation | `readGroupGetsExactlyOneCrossHourReactivationDespiteNewerTypeUpdate`;all6 delete/reactivate/type orders, one new-hour activity, old contribution inactive, late delete/replay harmless; prior type-only-read test retained |
| Preference/block/mute/inactive actor | `deniedUpdateClosesPendingOriginAndLaterTypeOnlyUpdateCannotCatchUp`,4 denial modes; re-enable/update/old-origin replay stays empty, genuine new activation works |
| Inactive recipient | `inactiveRecipientReceiptsDoNotTurnLaterTypeUpdatesIntoActivity`; receipts consumed while inactive, later type-only update cannot catch up |
| Private source/reply routes/self | `privateReplyUpdateCannotCreateCatchUpAfterParentBecomesPublicAgain`; no old catch-up after restored audience. `replyUpdateBeforeCreatePreservesScopedRecipientsAndPrecedence`,2 route variants preserve COMMENT/REPLY precedence and self suppression |
| Delete/removal/restoration | Existing delete-before-create retained; `deletedCommentUpdateAndCreateNeverMaterializeActivity`; real report-driven `removalBeforeInitialCreateClosesOriginEvenWhenRestorationIsAlreadyCurrent` keeps origin closed despite current restore |
| Retry/duplicate/source state rollback | `lateOriginFailureRollsBackPendingDecisionReceiptAndEffectsThenRetrySucceeds`,exception/expired lease; pending state, watermark, receipt/group/contribution roll back, attempt2/generation2 succeeds once; source-level and receipt-level replay covered |
| Concurrent worker ordering | `delayedOriginRefreshesCurrentSnapshotAfterConcurrentUpdateWinsRecipientGuard`; newer producer+worker wins pre-guard race, old origin then uses current LOVE/version1 and creates exactly1 |
| Group retention/replay expiry | `retentionKeepsOrderingAndRejectsDeadReplayPastHorizon`; exact1 bounded prune, decision survives NULL FK, recent type/old replay cannot resurrect, genuine activation works. `expiredPendingOriginCannotBorrowRecentTypeUpdateToCreateActivity` rejects >30-day origin |
| Prior matrix preserved | All seven kinds, current actor counts/attribution, invite/request expiry/replacement, read/bulk races, HTTP/IDOR/cursors/scan ceilings and batched source proof unchanged; full prior Phase1–8 suites pass |

FoundationIT fresh32/upgrade31 preserves all31 checksums and exact old source/group
read+activity state, conservatively closes materialized/NULL-active/NULL-inactive
legacy rows, rejects negative/above-order decisions and repeats migrate idempotently.

### Final round2 verification and evidence

- Targeted notification/worker/native SSE/Foundation/API/architecture:
  **116 PASS =14 fast+102 PG**,0 failures/errors/skips,3:00, finishing11:00:09+07.
  Log `r2-lifecycle-targeted-final.log`; NotificationsIT63/Foundation22/envelope3/
  native streams13/open-stream shutdown1. Exact command in [Phase7](phases/phase-7.md).
- Java21 `./mvnw -B -ntp spotless:apply verify`: **388 PASS =57 fast+331 PG**,
  0 failures/errors/skips/forced JVM exit,5:27, finishing2026-10-17 11:05:42+07.
  Log `r2-full-verify.log`. Spotless/Enforcer/OpenAPI/7 architecture/3 persistence
  tests pass; reports23/moderation operations13/feeds19/auth34 and all prior
  media/Silo/current privacy/source coherence/worker regressions retained.
- Actual default1s scheduler,10-post/one-owner notification+search burst:
  completion20,312ms/max lag20.190289427s (<30s);50-result/10k-ballast fixture6 SQL.
  Full SSE suspend→reinstate5089ms; other revocations5084–5095ms; expiry2019ms,
  outage8087ms, slow readers270/228/195ms/queue≤2/native resources0; shutdown22ms.
- Compose config and real app/media startup/build/bootstrap/probes PASS. Retained
  isolated `social-p78-gate` volume upgrades31→32 (actual runtime log), column
  query succeeds, readiness/liveness UP. Anonymous notifications/admin metrics/
  actuator Prometheus401, bucket listing403. App/Silo UID1000/read-only/1GiB/2CPU,
  media enabled and correct isolated data mount. Logs `r2-compose-*.log` include
  probe outputs. Task app stopped before dependencies; volumes/data/keys preserved,
  no task service running before benchmark. No old/new writer mixture.
- Fresh unchanged FeedBenchmarkTool pool10/50,1million posts/50 readers:
  **3200 attempts/3200 successes/0 failures**,all16 outcome lines summed; each
  pool500 first+500 steady+6×100 sparse.15 prerequisite tests+1 tool PASS,5:34,
  ending11:13:36+07. Log `r2-feed-benchmark.log`. Dense p95 pool10 first2505.688/
  steady2995.735ms; pool50 first2703.720/steady2016.948ms. Full p50/p95/p99,
  sparse/SQL/plan/resource/hardware evidence in Phase7. **500ms remains unmet**;
  local latencies are higher than round1, no controlled causal/capacity/SLA claim.
  Pre-benchmark shared desktop:5962MiB used/1702 available,3822MiB swap used;
  Java21.0.12.1/Boot4.1.1/Tomcat11.0.27/pinned PG18.6/Docker29.8.2,
  i7-1165G7/8 logical CPUs/7.5GiB RAM/4GiB swap. No pool/timeout relaxation.
- 303-file `r2-final-source-sha256.txt` matches after full/Compose/benchmark and
  precommit. Only Markdown evidence/status follows final executable verification.
  Pinned redacted gitleaks full history **59 commits including d2a8e3c**, no leaks
  (`r2-history-code.log`); staged code scan clean (`r2-code-staged-scan.log`).
  Synthetic external positive control detects1 expected generic-api-key and passes
  (`r2-secret-positive-control.log`); both exact historical fingerprints unchanged.
  Mailbox-only diff/link/staged checks are separate; final post-mailbox history
  scan is retained in `r2-history-final.log`, without inventing this docs hash.

All logs under ignored `.local/evidence/p78` (mode700). Initial after-fix55 PASS and
failed red/expanded attempts remain. The expanded suite's one failed retention
fixture aged >20 shared recipients beyond one bounded cleanup page; it now starts
91days earlier, ages only itself and asserts exactly1 removal. No cleanup bound,
retention duration, denial/assertion, replay policy or scanner weakened.

No new blocker. Next owner main: independently review real fix hash and complete
package, retain/update P78-R1 disposition and decide phase acceptance. No developer
resolution/self-acceptance, push, agent, deployment, remote CI claim or Phase9 work.
Dense latency, ambiguous historical loss, external provider/storage/decoder/key/
backup/audit retention/admin gateway/public-launch limitations remain explicit.
