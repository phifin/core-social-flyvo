# Phase 7 — semantic notifications and authenticated SSE

Status: round2 stabilization implemented/locally verified, **READY_FOR_REVIEW**.
Main's round1 CHANGES_REQUESTED/P78-R1 remains open for independent review; the
developer does not resolve the finding or accept either phase. See the
[mailbox](../agent-handoff.md) and round2 evidence below.

Historical delivery: implemented; internal gate passed2026-10-15. This is slice B
evidence within the activated [Phases 7–8 package](../work-packages/phase-7-8.md),
not a handoff or authorization for Phase9. A commits: `beebb0a` (envelope) and
`ecb11f6` (inbox). Verified B hash will be recorded in the fixed mailbox after
the code commit. Main alone reviews/accepts phases.

## Delivered scope

V0027 adds semantic groups, actor contributions, serialized versioned source
state, per-kind preferences and recipient watermark. All seven kinds are routed
through current content/graph/community owner proofs, batched account/graph facts
and authorized profile attribution. Mentions use the existing explicit post
relation; comments have no mention relation and handles are never inferred.
Self is suppressed; routing precedence is MENTION > REPLY > COMMENT. Current
preferences, actor/account state, block/mute, target visibility and request/invite
expiry apply even with delivery stopped. A replacement friendship cannot revive
the notification for a different, earlier request.

Reaction identity/version survives type changes/delete/reactivate across UTC
hours. Type change does not create unread activity. Reordered deletes/creates and
reactivation before its preceding delete are tested. Read/bulk read use observed
versions and leave concurrent activity unread. Receipt, effects and fenced ack
remain atomic in the existing worker. The immutable envelope preserves stored
schema/source versions, sequences, correlation and nullable historical causation;
no historical metadata is fabricated.

REST uses bounded keyset scans and encrypted viewer/size/cutoff cursors. Unread
and bulk read refuse oversized exact scans with503 rather than returning partial
counts. Current eligible actor count and at most three summaries are returned;
no stored body/rendered message or unfiltered fallback. Recipient IDs are never
client-selected. Retention is90 days for groups,30-day accepted replay, at least
120 days for inactive unreferenced ordering; active ordering survives group purge.
No security-email preference, social push/email, foreign SQL consumer or broker.

SSE uses ADR-015's narrow verified actor/expiry and current-session APIs. Actual
exp, not skew allowance, bounds lifetime. Five-second current checks plus a
three-second deadline include queueing/unavailability. Default2/user and100/global,
four shared check threads, one timer and two-frame nonblocking queues. Frames
contain generic invalidations/random IDs/comments only. Periodic current-source
repair covers policy changes that do not change the notification watermark.
Disconnect/lost hints cannot lose the durable REST inbox. Source/account policy
is never cached positively, and sockets retain no DB transaction/connection.

See [notification contract](../notifications.md), [operations](../../ops/notifications.md),
[plan/complete intermediate failures](../work-packages/phase-7-8-plan.md) and
[OpenAPI](../api/openapi.yaml) for exact bounds, routes and failure codes.

## Verification and measured limits

Environment: Java21.0.12.1, Boot4.1.1, Tomcat11.0.27 patch, Docker29.8.2,
PostgreSQL18.6 pinned digest; i7-1165G7/8 logical CPUs, about7.5GiB RAM/4GiB swap,
shared local development host. No production capacity/SLA claim. Existing pool
sizes and acquisition/statement timeouts are unchanged.

Checkpoint `.local/evidence/p78/phase7-targeted.log`:110 PASS (14 fast +96 PG),
no errors/skips. Includes all19 feed regressions,18 foundation,30 authentication,
3 envelope,1 shutdown,15 inbox and10 stream tests before the later regressions.
Subsequent full native-abort verify:298 PASS (54 fast +244 integration), no
failures/errors/skips. It includes16 inbox,12 HTTP stream executions (three slow
reader repetitions), standalone real open-stream shutdown, native-buffer deadline
and late-error unit regressions, fresh/upgrade27 migrations with prior checksums
and data preserved, OpenAPI, architecture and all accepted Phases1–6 tests.

That full run still forced its JVM to exit at30s. The dump locates Hikari shutdown
waiting for cached test pools whose Testcontainers DBs had already stopped,
not a Tomcat stream leak. The three new cached test contexts now evict after
class. Final `.local/evidence/p78/phase7-gate-final.log` passed all298 tests in
5:59, without forced JVM exit. Spotless, Enforcer, OpenAPI and architecture pass.
The intervening `phase7-gate.log` compile failure was an incorrect test annotation
import, corrected before this final run; it is not a passing checkpoint.

Measured on the final298-test run:

| Case | Observed |
|---|---:|
| Logout / own revoke / logout-all / suspension | 5,029 /5,076 /5,083 /5,093ms |
| Actual token expiry | 2,417ms after connection; retained DB connections0 |
| Paused real PostgreSQL | fail-closed8,074ms; ordinary REST recovers after unpause |
| Slow reader, three repetitions | 363 /239 /223ms; queue≤2, native counters0 |
| Context close with live five-minute token | 54ms (five-second regression bound) |
|100 reactions /25 posts /4 actors, worker drained per command | max lag17.352ms; total1,791ms |

The last workload measures synchronous DB projection/drain costs, **not**
scheduler burst throughput or a production delivery SLA. SSE checks are bounded
fail-closed, not a promise every stream remains open at configured maximum load.
An50-group inbox regression asserts one batch owner proof, not one call per row.
Exact unread/bulk scan exhaustion is a named denied case, not silently truncated.

## Failures retained and fixes

Earlier full verifies failed native slow-reader teardown despite app streams0.
An application late-error regression first failed (native complete wanted twice,
observed once); terminal release is now idempotent but later native error cycles
still complete. This alone did not fix native resources.

IntelliJ conditional logpoint captured Tomcat11.0.24 recycling `COMPLETING`,
error `NONE`, through ConnectionHandler.release, with native async count1 and
zero sockets/waiting processors. The compatible11.0.27 patch handles the late
write-notification/completion race; patch-only verification still failed1/3 slow
readers. A stream-only existing-container abort hook now prevents blocked final
flush before backpressured completion, serialized against terminal callbacks.
Native buffered work keeps the same unsent deadline even with an empty Java queue.
Missing adapter fails SSE503; ordinary REST remains independent. Container changes
need an equivalent verified adapter, not a silent portable-completion assumption.

The standalone open-stream shutdown regression failed its5s bound before moving
SmartLifecycle stop ahead of Tomcat's graceful drain, then passed18–29ms. It owns
its context explicitly; latest final run54ms under heavier host contention. Manually closing a Spring TestContext-managed context
inside a test conflicts with restart callbacks. Every native HTTP test asserts
both async request count0 and servlet allocations0, not just the application map.
All agent debugger probes/sessions are removed; user breakpoints are unchanged.

Logs in ignored `.local/evidence/p78/` preserve failed full gates, failed unit/
shutdown regressions, patch-only failure, focused native-abort PASS and final
full-suite attempts. Final package evidence must additionally record app/media
Compose, heavy pool10/50 feed benchmark and current-history/staged secret scans.
No push, phase acceptance, deployment or Phase9 work is authorized.

## Round 2 stabilization — P78-R1, 2026-10-17

Review baseline5012a83/codebbb8500. The finding remains **OPEN for main**;
development proposes the following correction, not a resolution/phase acceptance.
Real fix commit and final routing are recorded in the mailbox after code commit.

Regression-first PostgreSQL run `r2-initial-activity-before.log`:3 failures,0
errors/skips. Both delayed LIKE→LOVE orders and CommentUpdated-before-Created
commit both notification receipts but return an empty inbox. This is actual
producer/worker reproduction, distinct from main's original code inspection.

V0032 adds independent activity_decided_version; V0001–V0031 unchanged. Ordinary
updates advance current source ordering but never synthesize activity. A real
Created or reaction null→type event can settle pending/new activity even behind
newer ordering, with current owner/policy/preferences/account/block/mute proof.
Decision-through current snapshot coalesces superseded historical transitions;
source denial and deletion close origins, a later genuine reactivation remains
new, and pruning retains decisions. A missing group FK is never eligibility.
Refresh current content once after sorted recipient guards to avoid pre-lock
snapshot loss/regression under concurrent workers. Existing events/owner APIs/
worker/fencing/pools/timeouts/REST shape are unchanged. Concrete algorithm and
schema rationale were written in the [plan](../work-packages/phase-7-8-plan.md)
before implementation; [inbox contract](../notifications.md) and
[rollout/recovery](../../ops/notifications.md) describe the exact behavior.

Legacy limitation: old NULL-FK rows cannot distinguish suppressed/pruned activity
from already-acknowledged initial loss. Upgrade conservatively closes **all** old
rows through their stored source version, preserving privacy/retention; it does
not guess, reset receipts or repair historical losses automatically. Stopped old
writers are required for V0032; no mixed-version rolling-writer claim.

### Real producer/worker lifecycle and order matrix

Every notification row below uses real content/graph/community producers and the
registered receipt/effect/fenced worker on pinned PostgreSQL18.6. Spies only inject
failures/latches and call real methods; no mocked successful projection/SQL.

| Case | Named regression / asserted outcome |
|---|---|
| Initial LIKE→LOVE before dispatch | `initialReactionSurvivesTypeChangeBeforeFirstDispatchInEitherOrder`,2 orders; both receipts,1 group/actor/activity/unread, current LOVE; receipt-free replay stays read |
| CommentUpdated before Created | `commentUpdateBeforeCreatePreservesExactlyOneInitialActivity`; update leaves explicit pending decision, real origin creates canonical create-event group once; later edit/replay leaves read |
| Unmaterialized create/delete/reactivate/type burst | `currentReactionBurstCoalescesOneActivityInAllTwentyFourOrders`,all24 permutations; one current-hour contribution/activity, current SUPPORT/version3, no inflation after all receipts removed/replayed |
| Read group then cross-hour delete/reactivate/type | `readGroupGetsExactlyOneCrossHourReactivationDespiteNewerTypeUpdate`,all6 permutations; exactly one new-hour activity/unread, old contribution inactive; stale delete/replay harmless |
| Suppressed pending origin | `deniedUpdateClosesPendingOriginAndLaterTypeOnlyUpdateCannotCatchUp`,preference/mute/block/inactive actor; denial closes origin, re-enable/update/replay stays empty; genuinely new activation succeeds |
| Inactive recipient | `inactiveRecipientReceiptsDoNotTurnLaterTypeUpdatesIntoActivity`; denied-time receipts acknowledged without activity, later type-only update cannot create catch-up |
| Private parent and reply routes | `privateReplyUpdateCannotCreateCatchUpAfterParentBecomesPublicAgain`; no catch-up after restored audience, genuinely fresh reply works. `replyUpdateBeforeCreatePreservesScopedRecipientsAndPrecedence`,2 route variants; COMMENT/REPLY precedence and self-suppression retained |
| Delete/removal/restore | Existing delete-before-create test plus `deletedCommentUpdateAndCreateNeverMaterializeActivity`; terminal source remains empty. `removalBeforeInitialCreateClosesOriginEvenWhenRestorationIsAlreadyCurrent` uses real reports/actions/source events; stale initial origin stays closed |
| Late-origin retry and fence | `lateOriginFailureRollsBackPendingDecisionReceiptAndEffectsThenRetrySucceeds`,failure/expired lease; pending state, watermark, receipt/group/contribution roll back; generation2/attempt2 succeeds once, receipt-free replay no inflation |
| Concurrent workers/source snapshots | `delayedOriginRefreshesCurrentSnapshotAfterConcurrentUpdateWinsRecipientGuard`; producer changes source while origin pauses pre-guard, newer worker wins, late origin refreshes and creates1 current LOVE/version1 activity |
| Retention and event expiry | `retentionKeepsOrderingAndRejectsDeadReplayPastHorizon`; exact1 bounded group prune, decision survives NULL FK, new type/replayed old origin stays empty, genuinely new activation works. `expiredPendingOriginCannotBorrowRecentTypeUpdateToCreateActivity`; >30-day origin cannot borrow a recent update |
| Existing inbox/read/privacy/resource matrix | Prior all-seven-kind routing, request/invite expiry/replacement, multi-actor blocked/muted/inactive filtering, read/bulk races, recipient HTTP/IDOR/cursor/scan ceilings and source batch proof retained |

New FoundationIT upgrade31→32 preserves all31 checksums and complete old source
rows/group read+activity state. Legacy materialized/NULL-active/NULL-inactive rows
all receive conservative decision-through versions; negative or above-order
decisions fail DB constraints. Fresh32/repeat migrate and all earlier upgrades pass.

### Round 2 checks and retained failures

Final targeted command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply test -Dtest=NotificationRoutesTest,NotificationStreamLifecycleTest,ArchitectureTest,PersistenceBoundaryTest,OpenApiContractTest failsafe:integration-test failsafe:verify -Dit.test=NotificationsIT,EventEnvelopeIT,FoundationIT,NotificationStreamsIT,NotificationStreamShutdownIT
```

**116 PASS =14 fast+102 PG**,0 failures/errors/skips,3:00, finishing2026-10-17
11:00:09+07; no forced fork exit. NotificationsIT63/FoundationIT22/envelope3/
native streams13/open-stream shutdown1; log `r2-lifecycle-targeted-final.log`.
SSE targeted suspend→reinstate5052ms, other revocations5076–5088ms; expiry2812ms,
outage8038ms, slow readers313/287/208ms, queued≤2/native resources0; shutdown20ms.
These are targeted measurements, not substituted for the later full-package run.

Intermediate first expanded run had one failed retention fixture: extra permutation
cases made >20 shared recipients old after global91-day advancement, so one bounded
cleanup page did not necessarily contain this fixture. It now starts91days earlier,
ages only itself and asserts exactly1 removal in one unchanged bounded pass. All
original empty-inbox/order/horizon assertions remain. No production cleanup bound,
duration, pool/timeout or security assertion weakened. Failed/red logs remain in
ignored `.local/evidence/p78` alongside initial after-fix55-test PASS.

Full `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`:
**388 PASS =57 fast+331 PostgreSQL**,0 failures/errors/skips,5:27, finishing
2026-10-17 11:05:42+07; no forced fork exit. Log `r2-full-verify.log`.
OpenAPI/7 architecture/3 persistence-boundary/Spotless/Enforcer pass. Includes all
prior Phases1–8 tests: feeds19/auth34/reports23/moderation operations13, real media/
Silo/worker/privacy/source coherence and fresh32/upgrade31 plus earlier checksums.

Full-run measured default scheduler10-post/one-owner notification+search burst:
20,312ms completion/max lag20.190289427s (<30s);50-result/10k-ballast fixture6 SQL.
SSE suspend→reinstate5089ms, logout5091/session5095/logout-all5084/suspend5089;
expiry2019ms, outage8087ms, slow readers270/228/195ms, queue≤2/native resources0;
open-stream shutdown22ms. No target/pool/timeout/security assertion change.

303-file executable SHA-256 manifest `r2-final-source-sha256.txt` passes after the
full run and actual Compose startup. Only evidence/status Markdown follows this
frozen executable source; earlier340-test/heavy evidence above/in Phase8 remains
historical, not measurements of this final round2 source.

### Refreshed Compose and scanner evidence

Both tracked app/media Compose config and isolated runtime config validate. Real
`social-p78-gate` Silo/bootstrap/app image build/postgres/mailpit startup exit0,
using the retained isolated PG/data volume and existing keys. Runtime log proves
**one migration from31 to32**, not a fresh-only install. Real column query succeeds.
App starts in8.753s; explicit HTTP readiness/liveness both UP. Compose running
status alone is not readiness (app has no image healthcheck).

Anonymous notification/admin moderation metrics/actuator Prometheus401, private
bucket listing403. Both app/Silo UID/GID1000:1000, read-only root FS,1GiB/2CPU,
MEDIA_ENABLED=true, and Silo /data resolves to the isolated evidence bind. Bootstrap
revalidates private/encrypted/versioned buckets and separated identities. Probe
script exits0 and logs actual values, not mocked responses. Task app stopped
before dependencies; volumes/data/keys remain recoverable, original storage
untouched and no task container left running before benchmark.

Logs: `r2-compose-{config,config-isolated,keys,silo,bootstrap,app,runtime,probes,stop}.log`
and `r2-source-integrity-after-compose.log` in ignored `.local/evidence/p78`.
Pinned redacted gitleaks pre-code history58 commits/no leaks; synthetic external
generic-api-key positive control detects1 expected fixture and passes, exact
historical fingerprints unchanged. Final staged/code/mailbox scans are recorded
with real hashes in the fixed mailbox after commits. No push/remote CI claim.

### Refreshed heavy benchmark — final round2 source

Unchanged committed tool/command, not a relabel of round1:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply test -Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest failsafe:integration-test failsafe:verify -Dit.test=FeedBenchmarkTool -Dfeed.benchmark=true
```

15 prerequisite tests+1 heavy tool PASS,0 failures/errors/skips,5:34 total,
ending2026-10-17 11:13:36+07. Actual1,000,000 posts/50 readers, pool10 default
and comparison50. Both pools each500 first+500 steady+6×100 sparse; all16 outcome
lines independently summed: **3200 attempts/3200 successes/0 failures**. Success-only
percentiles have no failed requests to omit. Log `r2-feed-benchmark.log` retains
all outcomes/bound EXPLAIN/SQL shapes/heap/pool snapshots; no timeout/pool/worker
or all-success assertion change.

| Pool/scenario | p50 ms | p95 ms | p99 ms | SQL/attempt |
|---|---:|---:|---:|---:|
|10 first500|1122.431|2505.688|3240.393|15|
|10 steady500|1120.978|2995.735|4684.473|15|
|50 first500|1525.315|2703.720|3034.816|15|
|50 steady500|1258.228|2016.948|2333.474|15|

| Sparse scenario (100 each/pool) | Pool10 p95 ms | Pool50 p95 ms | SQL/attempt |
|---|---:|---:|---:|
|no_sources|104.756|88.813|6|
|rare_old|462.744|313.139|12|
|last_page|606.918|479.031|11|
|mostly_rejected|357.581|269.959|6|
|overlap|1838.948|1819.991|15|
|exhausted|263.911|251.997|5|

Dense each10,000 examined/10,000 returned, ratio1. Single community/timeline
samples90.321/37.943ms,20 results each, not percentile measurements. **Dense p95
500ms remains unmet**, and these local latencies are higher than round1; no claim
of production capacity, accepted latency or causal attribution from this uncontrolled
shared-host comparison. Sparse overlap and pool10 last_page also exceed500ms.

Hardware: i7-1165G7/4cores/8 logical CPUs,7.5GiB RAM/4GiB swap, shared desktop;
Java21.0.12.1, Boot4.1.1/Tomcat11.0.27, pinned PG18.6, Docker29.8.2. Immediately
before benchmark:5962MiB used/1702MiB available,3822MiB swap used; task Compose
stopped, no other task services running. JVM max2,011,168,768bytes, seed234.807s,
DB976,811,711bytes. Raw host snapshot `r2-hardware.log`. No pool increase/cache or
planner/timeout relaxation. The tool is feed regression evidence, not a sustained
mixed-worker capacity test; the scheduled notification+search workload is reported
separately above.

303-file manifest passes after full verify, Compose and benchmark; only Markdown
evidence/status changes follow. All logs are ignored under `.local/evidence/p78`
(mode700), source/API/migration/docs changes are committed, then a separate mailbox
commit references the real fix hash. P78-R1 awaits main; no phase acceptance,
push, agent, deployment or Phase9. External launch gates remain unchanged.
