# Phases 7–8 development plan

Baseline: clean master `14c983b`, executable baseline `32c1b1e`, V0001–V0026.
Owner: development. This records implementation choices, not phase acceptance.

## Round 2 stabilization — P78-R1, baseline5012a83

1. Reproduce initial reaction loss through real producers and the receipt/effect/
   fenced worker on PostgreSQL: LIKE→LOVE before dispatch, both v0→v1 and v1→v0;
   also CommentUpdated before CommentCreated. Preserve failing evidence first.
2. Separate ordering progress from the decision to materialize initial activity.
   Inspect suppression/deletion/retention and reaction reactivation epochs before
   selecting the exact persisted state/API refinement; document the concrete
   algorithm here before implementation. Do not infer eligibility from a null
   notification FK or turn every source update into new activity.
3. Complete real-worker lifecycle/order matrix: duplicates/retry/fenced rollback,
   delete-before-create, existing read group/type update, cross-hour reactivation,
   current privacy/preferences/self/block/mute/account and retention/replay.
4. Targeted notification/worker/native SSE, formatting/full Java21 verify,
   OpenAPI/architecture/fresh+upgrade migrations, Compose config, pinned history/
   staged/positive-control scans. Actual Compose startup if runtime wiring/schema
   changes; heavy pool10/50 benchmark if shared owner/worker/resource paths change.
   Keep older runs explicitly historical. Verified code commit, then separate
   real-hash mailbox READY_FOR_REVIEW with P78-R1 response; main alone resolves it.

Applied baseline migrations end atV0031; next isV0032 only if justified. No push,
agents, phase acceptance or Phase9. Initial checkout/index clean.

### Concrete algorithm selected after the red worker regressions

`r2-initial-activity-before.log`:3/3 real-PG cases fail (empty inbox despite two
committed notification receipts), no errors/skips. This experimentally reproduces
main's code-inspection finding. No production fix existed during that run.

V0032 adds one nullable `activity_decided_version` to notification.source_state,
separate from latest_source_version. NULL means no origin activity or suppression/
deletion decision has been observed yet, **not** that a missing notification FK
is eligible. A nonnull value is a decision-through watermark, not a fabricated
activation event version: all activity at/below it is materialized or consumed.
Existing ambiguous rows are conservatively backfilled to their latest version,
including suppressed/pruned NULL-FK rows; no automatic resurrection or historical
repair of already-acknowledged pre-fix losses is claimed.

Keep exact original event version and add the already-published current owner
snapshot version to the private Change port. Content routing is inspected first,
active recipients are batched, recipient guards acquired in UUID order, then one
bounded source snapshot is refreshed **after** those guards. This prevents an old
pre-lock snapshot from losing an origin behind a concurrent newer update. Current
owner eligibility/preferences/account/graph proof stays required for every route.
No source/business lock, owner API change, foreign SQL or worker framework change.

An ordinary eligible update advances ordering/current contribution only, leaving
an undecided origin pending and never creating a group. A real Created event or
ReactionChanged(null→type) is the only positive activity evidence. It can settle
an undecided/newer activity even when its event version is older than ordering,
but only through current source authority and the independent decision watermark.
After materialization the decision covers the current observed snapshot version,
coalescing already-observed historical activations rather than counting a burst
of old transitions twice. Existing type updates remain read and keep their group.

A denied/inactive current source consumes activity through its current version;
a real deletion/removal event also closes decisions through its own event version,
even when a later restoration/reactivation is already current. An older delete
cannot close a newer activation. No non-origin update can reopen a consumed
decision. A new reaction activation above the decision watermark can create one
current-hour activity, including activation arriving after a newer type update.
All source/contribution/group/decision/receipt/fenced ack changes remain atomic.
90/120-day cleanup and30-day replay limits are unchanged; decision watermark
survives group FK SET NULL. No FK-null heuristic or globally ordered delivery.

Refresh actual Compose startup forV0032 and rerun the heavy benchmark as conservative
contention evidence for the additional bounded projection snapshot. Keep unchanged
owner SQL/worker/pool/timeouts and all-success gate; old evidence stays historical.

The first expanded targeted run retained one failed retention fixture:30 additional
permutation cases made more than20 shared recipients old when the test advanced
the global clock91days, so its single bounded cleanup page need not contain the
test recipient. The fixture now starts91days earlier and ages only its own group,
asserting exactly one removal in one unchanged bounded pass. No production cleanup
limit, retention duration, source decision or denial assertion is weakened.

Final round2 checkpoint: targeted116 PASS, full388 PASS (57 fast+331 PG), no
failures/errors/skips/forced JVM exit. Fresh32/upgrade31/checksums/OpenAPI/
architecture pass. Actual app/media Compose upgrades retained isolated31→32;
readiness/security/resource probes pass, then task services stop with data/keys
preserved. Fresh heavy pool10/50 tool passes3200/3200 requests; dense p95 remains
2016.948–2995.735ms (500ms unmet), no capacity or causal attribution claim.
303-file source manifest is unchanged across full/Compose/benchmark. History58/
positive-control scans pass before commit; final staged/post-commit scans and
actual fix/mailbox hashes follow the mandated code-then-handoff sequence.

## A — durable envelope and semantic inbox

First preserve the worker transaction and expose the existing outbox envelope as
an immutable platform API record (including nullable causation, correlation,
schema/aggregate versions and sequence). Migrate the actual test consumers too;
verify real duplicate, rollback, lease expiry/reclaim and ten-attempt cases before
committing this prerequisite. No historical payload/metadata rewrite.

V0027 will own notification groups, contributions, source_state, per-kind
preferences and recipient watermark. Scalar account FKs, unique semantic source
keys, readVersion ≤ activityVersion, keyset/unread/retention indexes. All mutations
take recipient serialization before source_state → groups → contributions; the
watermark row is already held. Read commands use account shared guard → recipient
serialization → group rows ordered.
Preference edits share the recipient guard. No foreign SQL consumer is added.

Owner APIs inspect bounded current notification sources and authorize batches:
content supplies post mentions, comment post/root/addressed authors and reaction
targets; graph supplies request participants/current request or friendship;
community supplies invitation/inviter/current admission eligibility. Only immutable
IDs/version/status facts cross modules. Profiles provide attribution after source
authorization. Existing comments have no explicit mention relation: mentions are
the existing validated post mentions; no inferred handles or audience expansion.

| Event | Recipient route | Ordering/source |
|---|---|---|
| FriendRequestCreated | current addressee | retained graph pair envelope version; request UUID |
| FriendshipEstablished | original requester, actor is accepting addressee | same pair version/request |
| CommunityInvitationCreated | current invitee, actor is inviter | community envelope version/invitation UUID |
| PostCreated | each current validated mentioned user, max20 | post UUID/version |
| CommentCreated | post author COMMENT; root/addressed author REPLY; dedup precedence | comment UUID/version |
| ReactionChanged | current target author | stable reaction UUID/version, including inactive tombstones |
| Source resolution/update/delete facts | invalidate/deactivate current contributions without new activity | owning monotonic sequence |

Suppress self, disabled preferences, inactive accounts, block/mute and denied
current targets on creation/read. Dedup planner precedence MENTION > REPLY >
COMMENT. Reactions group by target/recipient/UTC hour; type changes keep the group
and unread version, deletion deactivates, reactivation moves to the current hour.
Source_state ordering outlives group expiry; new activity uses monotonic processing
time. Non-reaction keys include event ID. No rendered message/body fallback.

REST: GET /notifications (20 default/100 max, encrypted viewer/size/cutoff cursor),
GET /notifications/unread-count, PUT /notifications/{id}/read with observed
activityVersion, POST /notifications/read with server snapshot observed versions,
GET/PATCH /me/notification-preferences with typed kinds. Filter active actors in
bounded batches, return at most three authorized summaries. Full count scans use
bounded batches/memory and explicit resource failure if their documented ceiling
is exceeded, never an approximate or silently truncated badge. Bulk read snapshots
versions before mutation; new activity remains unread. Notification groups expire
90 days after activity; a 30-day permitted replay horizon rejects older events
(including old DEAD replay) before inbox changes. Retain source_state at least
120 days and never discard an active source's ordering state during group cleanup.

Tests: real registered worker duplicates/reorder/effects-receipt-fence rollback,
delete-before-create, cross-hour type/delete/reactivate, concurrent source updates,
route dedup, read/bulk-read races, actor filters, preference changes, expiry/target
privacy/removal, recipient IDOR, cursors, bounded cleanup and old DEAD replay.

## B — authenticated SSE and Phase 7 gate

ADR-015: identity API exposes trusted actor/actual exp from the existing JWT
validator and current session checks. Align the narrow ArchUnit dependency rule.
Bearer fetch GET /notifications/stream; no raw token retained, no URL credentials.
Only changed invalidation/heartbeat frames; REST refresh on reconnect. Poll durable
recipient watermark, recheck current session/account at ≤10-second heartbeat,
schedule actual exp closure. Bound streams (2/user, 100/global), shared scheduler,
nonblocking writer/two-frame queue, shared check executor and slow-client timeout;
release on every terminal path.
No connection or transaction spans a stream. No extra broker/service.

Real Tomcat HTTP tests cover JWT gates, actual exp/skew, logout/all/session revoke,
suspension, DB outage, stream caps/rate, disconnect, backpressure, no credentials
in frames and REST repair. Record measured check/closure/resource bounds. Run
targeted suites, Spotless and full verify, migration/OpenAPI/architecture gates;
record Phase 7 evidence and verified commits before starting C.

## C — source-authorized PostgreSQL search

V0028 owns versioned documents/tombstones, GIN simple tsvector, normalized name/
handle prefix indexes and checkpointed rebuild state. SearchIndex is a real owned
port, PostgreSQL adapter. Live consumer registration precedes export/backfill.
Owner APIs supply ≤100 versioned source snapshots and tombstones, including missing
source/account status. Upsert compares owner source versions; account lifecycle
is separate ordering from profile/post, never substituted for those versions.
Checkpointed jobs reconcile current sources and indexed missing IDs; live newer
state wins older export. Audited operator recovery reuses durable jobs.

GET /search/users, /search/communities, /search/posts: q 2..200, bounded terms,
20 default/50 max, parameterized simple tsquery and escaped prefix matching,
existing timeout and 30/min limiter. Encrypted viewer/query/type/size/rank/ID/cutoff
cursor with last-examined bounded refill. Batch source policy/current hydration
before text; no projection snippet/name/count fallback or exact hidden totals.
Private community discovery retains its existing card contract. Vietnamese/
accent fixtures document actual simple-token behavior, no unaccent/trgm claim.

Tests: stopped-worker privacy/deletion/block/membership/account, coherent current
text races, missing source/stale create, restart/live-write rebuild ordering,
cursor ties/tamper/scope, parser/injection/expensive inputs and query counts.
Measure EXPLAIN and initial <30s projection lag with workload/hardware recorded.

### C implementation refinement after the Phase7 gate

B is committed as `af07427`, full298-test gate passed before C. The owner seams
will be ProfileSearchSources, CommunitySearchSources and ContentSearchSources:
inspect/export ≤100 UUIDs/current versions, UUID keyset scans including inactive
sources, and separate current authorized hydration. AccountQueries adds one bounded
versioned snapshot map (no credentials). Profile hydration loads text/scope together,
then current batched account/graph facts. Content hydration uses the same canonical
FACTS/ELIGIBLE SQL in the statement returning body/relations; no projection-body
fallback, generic profile directory or foreign SQL allowlist change. Community
hydration returns existing authenticated discovery-card fields, not private posts.

V0028 document key is (kind,source_id), source/author-account version vector,
owner ID, tombstone/missing-source flags, restricted handle/name/body, generated
simple tsvector, GIN and name/handle pattern indexes. Owner-confirmed absence is
sticky (UUIDs are not reused), with nullable unknown version rather than inventing
one from a legacy envelope; retain last known versions when available. Current
snapshots, not payload text, drive live updates. Both vector components must not
regress; duplicate identical state leaves updated_at unchanged. Inactive source or
account redacts projection text immediately on projection; current hydration denies
immediately even before that delivery.

Public routes remain GET /search/users, /communities, /posts. Only posts supports
an optional communityId filter, with explicit current scope proof even if empty.
q is NFC/lower/space-normalized, 2..200 chars and1..8 plain Unicode terms; no query
operator language, body wildcard, unaccent or stemming. Exact/prefix normalized
name/handle is additional matching for users/communities. Default20/max50. Integer
rank DESC/UUID ASC, DB cutoff, encrypted viewer/query/type/filter/size/rank/id and
30-minute expiry; ≤1000 examined in100-row batches/200ms refill budget (not an
in-flight timeout). Advance only through examined candidates, return no rank or
hidden total, tolerate empty continued pages and mutable-projection omissions.
Implement the documented30/minute user/IP search GET limiter independently.

Register search-documents-v1 before backfill. Account lifecycle effects use bounded
SEARCH_ACCOUNT jobs scanning indexed owner IDs; POST/profile/community deliveries
inspect current owner snapshots directly. Audited ADMIN POST /admin/search/rebuilds
starts or resumes a type-specific checkpointed EXPORT then RECONCILE pass; GET
status is also attributable. One job owns100-source work/checkpoint/next-job in the
existing fenced transaction. Reconcile refreshes indexed IDs and marks absent
sources; live newer vectors beat older export. Lock actor account before rebuild
state for operator commands; jobs lock rebuild state then document UUID order;
live events lock documents only. No owner business locks or network calls.

## D — scoped moderation operations and package gate

Execution checkpoint2026-10-16: account lifecycle prerequisite is verified/committed
at `cd51ecae3d92a402fa8c70b1102501cafb857ffa` (full323 PASS). Remaining D is
completed after main's [ADR-016 acceptance](../adr/016-moderation-private-payload-retention.md),
2026-10-16. P78-D1 design is resolved; V0030/V0031 and all D operations are now
implemented/verified locally. Final full340, app/media Compose, all3200 pool10/50
requests and scanners pass. The plan below preserves execution refinements;
main review/acceptance remains separate. Exact evidence is in [Phase8](../phases/phase-8.md).

### Resumed D execution plan at main152a7c9

V0030 splits report evidence/action private payloads losslessly, with atomic SQL
count/value proofs before old columns drop, deferred parent/payload completeness,
no DELETE/reinsert, immutable ciphertext except one-way eligible redaction, and
legacy→verified encrypted transition only. Migration runs in a controlled write
stop: old/new binaries cannot write both schemas. V0031 adds assignment, root
retention/hold controls and append-only bounded versioned history; legacy history
versions stay unknown, never fabricated. Platform audit/action metadata triggers
remain unconditional. Cases and case-linked actions share the existing case root;
only standalone actions get a separate one-to-one retention root.

Hold edits use If-Match, current scope proof, sorted account guards before
community/root/payload, DB time, expiry≤365days, explicit renew/release and audit.
Assignment validates current staff in immutable scope, grants no permission, and
shares the case version/root with resolve/hold. Parameterized queue filters are
status/assignee/unassigned; all staff queue/history/evidence/maintenance reads are
attributable. Separate ADMIN account action HTTP uses the existing versioned owner
command and full exclusive actor/target guards before action root; no role edits.

Existing durable job handlers implement legacy encryption and retention, one root
and≤100 payload rows per transaction. Encryption decrypts/compares complete legacy
JSON before plaintext removal. Retry/fencing/audit/next job are atomic; no network
key-service calls under locks. DB time/root guards recheck eligibility/hold.
NONE→STARTED→DONE is irreversible; STARTED denies holds; bounded passes converge
before DONE.365days starts at terminal resolved_at, standalone created_at;
unresolved/current holds excluded. Recurring durable work and audited ADMIN resume
reuse the same jobs. No alternate framework or runtime trigger exemption.

Tests: fresh/upgrade checksums, exact legacy Unicode/null/JSON/ciphertext; real
raw-SQL guards (not missing columns); both hold/cleanup race orders; >100 payload
restart/fencing/rollback; crypto verification failure; cross-scope assignment/
history/queue and revoked staff; ADMIN hierarchy/If-Match; auth/SSE old sessions
and no content restoration. Operations: restricted metrics, static dashboard,
write-stop/key lifetime/backfill convergence runbook. Then full verify, real
app/media Compose, pool10/50 all3200-success benchmark, API/architecture/migrations
and pinned history/staged/positive-control secret scans before full handoff.

Operations refinement: expose the already-present Prometheus registry through an
audited/current-ADMIN-only GET /api/v1/admin/moderation/metrics, using the existing
case operations authorization boundary before scraping. Keep /actuator/prometheus
and actuator internals deny-by-default; no new dependency/collector/service or
long-lived credential model. Dashboard JSON/runbook are static artifacts for an
operator-approved protected data source, not a public rollout.

Account HTTP operability refinement: audited ADMIN GET /admin/moderation/accounts/{id}
returns only current ID/status/read-only platform role/version plus ETag so the
operator can obtain the target If-Match without guessing or identity/profile
directory access. Complete sorted shared account guards and authority recheck;
separate status command retains exclusive final modes. No epoch/credentials export.

Scope-proof hardening: immutable case metadata (including source guard IDs) is
read without payload first. Prove exact scope before any report root lock or
ciphertext/legacy query, then obtain complete account/community/source/root guards
in final order. A dedicated regression spies actual SQL/owner port and proves
wrong-scope evidence/resolution/history/action paths touch no payload/root lock.
System history copies no unguarded assignee FK; only ASSIGNED uses its guarded ID.

### D lifecycle refinement before implementation

V0029 adds an account-owned monotonically increasing authentication epoch and
an identity-owned captured epoch on sessions (existing ACTIVE accounts/sessions
start at zero; already SUSPENDED accounts start at1 to invalidate old sessions).
Suspension increments the account epoch under the exclusive lifecycle guard;
reinstatement never resets it. Issuance captures it under the existing account
guard, and access/current-session/refresh checks compare it synchronously. This
prevents suspension followed by reinstatement from reviving old Bearer or refresh
credentials without a reverse account-to-identity dependency or eventual consumer.
ADMIN suspend/reinstate require a source version, deny self and peer ADMIN, and
take the complete sorted actor/target account set in exclusive final mode before
any case/community locks. No platform-role, ownership or content restoration.

The retention storage design must also preserve the existing unconditional
moderation-action and platform-audit mutation rejection. Do not add a runtime
trigger bypass. Inspect the immutable private reason/snapshot columns against the
365-day requirement before selecting the later moderation migration; V0029 does
not change those guarantees.

The subsequent moderation migration extends existing reports with assignment,
terminal timestamp, bounded evidence hold/redaction markers and append-only
versioned resolution history;
index scope/status/assignee/retention. Current eligible assignee proof uses account
and community APIs, not the narrow ADR-014 profile proof. Assignment is no grant.
Separate global ADMIN and community staff routes for filtering, assignment/history,
evidence and versioned resolution. Scope/target remain immutable.

Separate /admin/moderation/actions for versioned suspend/reinstate through account
owner commands. Pre-read immutable IDs; sorted complete account set with target
exclusive final mode → community/source → case; no shared lock upgrade. Deny
self/peer ADMIN, retain ownership boundary, revoke existing sessions durably via
the account/identity lifecycle contract without a reverse dependency. Reinstatement
restores neither deleted content nor previously revoked sessions or community state.
Action/source/case/history/audit/outbox remain one transaction.

Retention: engineering 365 days after case terminal resolution, unresolved/held
cases excluded, evidence keys retained until all referenced ciphertext is redacted.
Bounded idempotent cleanup redacts private evidence/action detail, retaining IDs,
versions and attributable audit tombstones through an explicit controlled DB
retention mechanism; append-only protection is preserved for ordinary writes.
No legal policy, cascade erasure or Phase 9 workflow.

Tests: scope/IDOR/revoked staff, assignment/resolution conflicts, hierarchy,
suspend/reinstate vs login/write/SSE, audit/outbox rollback, held/unresolved/terminal
retention and bounded retry. Add low-cardinality operational metrics, simple
dashboard/runbooks, replay/rebuild/retention instructions without metric exposure.

Final gates: targeted security/race/worker checks; Spotless + complete verify;
fresh/upgrade schema preserving V0001–V0026 data/checksums; OpenAPI/architecture;
app/media Compose; pinned redacted full-history scan + positive control. Added
owner/worker/resource paths require rerunning the committed pool10/50 feed
benchmark, recording all outcomes with its all-success gate unchanged. Update
README/schema/domain/security/subsystems/operations and separate Phase 7/8 evidence.
Commit owned verified slices, then real-hash mailbox READY_FOR_REVIEW in a separate
documentation commit. No push, agents, phase acceptance or Phase 9 implementation.

## Actual slice evidence

- A prerequisite commit `beebb0a`: immutable stored envelope and lazy consumer
  discovery; Spotless + targeted verify, 10 fast/architecture +33 PostgreSQL/auth
  tests, zero failures/errors/skips. No schema/producer rewrite.
- A inbox precommit: targeted verify on 2026-10-14, 11 fast/OpenAPI/architecture
  +31 PostgreSQL tests (NotificationsIT11, EventEnvelopeIT3, FoundationIT17), all
  passing with no skips. Named regressions cover reactivation-before-delete,
  receipt/effect/fence rollback, cross-hour lifecycle, current actor filtering,
  recipient IDOR and bulk-read/new-activity interleaving. Full Phase 7 gate has
  not run at that checkpoint and B/C/D had not started. V0027 preserves all applied migrations;
  older FoundationIT checksum/data/constraint assertions remain intact, expected
  latest migration counts advance to27.
- Refinement: recipient serialization comes before all notification source/group
  writes, avoiding a reverse watermark/group lock order with read commands.
  Internal background proofs accept recipient UUID explicitly; no fabricated
  session or HTTP/system bypass is introduced.
- B implementation uses ADR-015 StreamAuthentication.Proof/verify plus existing
  SessionQueries only. Shared nonblocking transport, two-frame queues, typed
  caps, current-source repair and early shutdown are implemented. Verification
  remains in progress; Phase 8 C/D have not started.
- 2026-10-15 runtime investigation: native targeted tests passed but one earlier
  fork logged a 30-second shutdown wait. IntelliJ non-suspending probes confirmed
  complete paths and streams=0; a paused shutdown/native stack located Tomcat's
  active-request drain before @PreDestroy. A separate open-stream context-close
  regression failed its five-second deadline, then passed in27ms after moving
  stream stop before the web-server drain via SmartLifecycle. The standalone
  test owns its context to avoid Spring TestContext restart callbacks. Agent
  breakpoints/debug sessions were removed; user breakpoints stayed unchanged.
  Evidence: .local/evidence/p78/sse-shutdown-before.log and sse-shutdown-after.log.
- The first full Phase7 verify ran52 fast +242 PG tests and failed one native
  slow-reader teardown (container async count remained1), despite logical
  reservations reaching0. This is a failed gate, not accepted/flaky-to-ignore.
  A focused late-onError regression failed (native complete expected twice,
  observed once). Cleanup now releases metrics/reservations once but completes
  subsequent native error cycles, with onComplete only releasing. Both native
  async and servlet allocated counters must return0 in every HTTP test teardown.
  Final verification is pending; C/D remain gated.
- A second full verify still failed the same native teardown despite the late-error
  application fix. IntelliJ conditional logpoint in Tomcat AsyncStateMachine.recycle
  captured `COMPLETING`, error `NONE`, through ConnectionHandler.release; the
  context retained1 async request with0 sockets/waiting processors. The 11.0.24
  write-notification/completion race throws before post-processing. The compatible
  Tomcat11.0.27 patch explicitly returns LONG for that late notification so normal
  completion/decrement runs. Override all managed Tomcat artifacts together via
  tomcat.version; Boot4.1.1/Java21 and resource limits stay unchanged. Repeat the
  real slow-reader regression three times and retain native resource assertions.
  Official source/changelog: https://tomcat.apache.org/tomcat-11.0-doc/changelog.html
  and https://github.com/apache/tomcat/compare/11.0.24...11.0.27 . Verification pending.
- Patch-only targeted verification on actual11.0.27 still failed1/3 repeated
  slow-reader teardowns; it is not reported green. AsyncContext.complete does not
  abort a blocked final native flush. The existing Tomcat transport now supplies
  a stream-only native abort hook before backpressured completion, with serialized
  terminal callbacks and no hook call after completion/recycling. Native-buffer
  work retains its unsent deadline even after the Java queue empties. No new
  dependency or global timeout/pool change. First focused checkpoint:8 fast +3
  native slow-reader repetitions PASS (110–376ms, ≤2 frames, both native counters0).
  Full verify with the additional buffered-frame deadline regression is pending.
  IDE launch retained11.0.24 after the Maven override; CLI XML/log proves actual
  11.0.27 verification. All agent probes removed; no debug sessions remain.
- Full native-abort verify passed54 fast +244 PG tests (zero errors/skips), but
  still logged a forced JVM exit at30s. The native counters pass; the actual dump
  now locates shutdown in HikariPool.shutdown/connection-adder awaiting termination,
  not Tomcat's active-request drain. The three new Spring/Testcontainers classes
  explicitly evict their cached context after class so their pools do not outlive
  the owned database container. No production pool, timeout or test assertion is
  changed. A clean full-suite shutdown is being verified before closing the gate.
- Final Phase7 gate2026-10-15: Spotless + full verify PASS54 fast/244 integration
  (298 total), zero failures/errors/skips and no forced-fork-exit warning. New
  contexts close after class. Exact resource/lag values and prior failures are in
  [Phase7 evidence](../phases/phase-7.md). Internal gate allows C/D within this
  assignment only; neither phase is accepted. Commit B before starting C.
- C checkpoint: full verify317 PASS (55 fast +262 PG), zero failures/errors/skips,
  no forced JVM exit. Search15 PG cases +2 actual-scheduler/query-plan cases,
  all19 feeds/19 foundation/30 authentication and earlier regressions pass.
  Logs search-gate-targeted.log (63 PASS before later integrity/operator cases)
  and search-full-final.log. Real scheduler10-post burst20,275ms/max20.056s;
  page50 uses6 SQL; fresh/maintained plans4.338/0.098ms, selective GIN after normal
  VACUUM ANALYZE without forced planner switches. Snapshot checksum proof uses
  one bounded owned read; unknown versions are never fabricated. Source scope UUID
  is indexed for post filtering. DB time supplies projection timestamp and cutoff.
  The first full C attempt failed two test-harness assumptions: contract fixture
  delivery lost priority to the new registered consumer's backlog, and Spring
  translated the deliberate checksum exception. Fixture due ordering now isolates
  the same real receipt/dedup path with unchanged exact assertions; checksum test
  asserts the actual translated exception and repairs its Unicode payload. Prior
  failures remain evidence, not green. Commit C before D. Heavy benchmark/Compose/
  final scanners remain package gates; no phase acceptance or handoff here.
- D resumed after152a7c9: V0030/V0031 owned payload/control boundary, verified
  legacy encryption, bounded retention/explicit holds, scoped assignment/filter/
  history, account HTTP and protected metrics/dashboard/runbook implemented.
  Full340 PASS (57 fast+283 PG), zero failures/errors/skips/forced exit; final
  scope-focused50 PASS. Fresh31/upgrade29 preserve all old checksums/values and
  action/audit guards. Real crypto/audit/next-job/fencing failures roll back,
  205/206/102-payload fixtures converge in bounded passes, both hold race orders
  tested. Earlier failed compile/fixture attempts retained, not relabeled green.
- Final package operations on the same executable source: app/media Compose
  startup/bootstrap/readiness/security/resource probes pass, then task services
  stopped without deleting volumes/data/keys. Committed heavy tool pool10/50,
  1million posts/50 readers:3200 attempts/3200 successes/0 failures, all16 outcome
  lines summed. Dense p95=1411.877–2259.538ms still misses500ms; no production SLA.
  Pinned history/staged scans and synthetic positive control pass without changed
  fingerprints. Source SHA-256 manifest remains identical after full/Compose/
  benchmark. Only evidence/status docs follow; code then real-hash mailbox commits,
  READY_FOR_REVIEW to main, not phase self-acceptance or remote delivery.
