# Phase 9A C — implementation packages and release evidence

Status: **Proposed; not activated**, 2026-10-17. A+B+C design delivery for main
review, not implementation completion. [Inventory A](phase-9a-data-inventory.md)
covers56 actual tables at `d2a8e3c`; [proposed ADR-017 B](../adr/017-account-erasure-and-recovery.md)
defines decisions D01–D10, user/environment inputs U01–U06, fixed roster, API,
race/retry/restore contracts. Do not treat this plan as Phase9B authorization.

## Dependency gates and recommended next assignment

```text
G0 main dispositions on D01–D10; local policy profile + package activation
  -> 9A-I lifecycle/recovery intent + identity/profile/technical cleanup
     [I.1 protocol/guard -> I.2 independent intent -> I.3 identity/social proofs]
  -> main review gate G1
  -> 9A-II social sources/media + derived/retention completion
     [II.1 participation/references -> II.2 authored text/objects
      -> II.3 projections/moderation proofs -> II.4 strict finalization]
  -> main review gate G2
  -> 9A-III isolated recovery + operator/release hardening
     [III.1 storage/ledger restore -> III.2 outages/keys/ops -> III.3 evidence]
  -> main engineering acceptance G3 (local implementation only)
  -> external/product gates U01–U06 + measured target-environment release review
  -> separately authorized launch (not part of any assignment here)
```

Recommend next development chat **`Develop Phase 9A-I — Erasure Foundation & Identity`**,
opened manually by the user **after main acceptance/activation**. One writer,
no spawned agents; main remains reviewer. It delivers the entire I package with
verified internal slice commits, not an early handoff after one module. Other owner
steps must remain explicitly pending; I cannot claim end-to-end erasure. Do not
activate II/III merely because their plan is written.

G0 must record engineering policy/version and which defaults are accepted **only
for synthetic local fixtures**. Real retention/restore operations remain forbidden
until separately authorized; lack of production SMTP/credentials does not prevent
local implementation under that profile. A conflict in D02/D03/D08/D09 requires a
concrete ADR revision reviewed by main before the affected slice, not an assertion
that earlier phase acceptance settled it.

## 9A-I — lifecycle, independent intent and identity/social cleanup

Three coherent capability groups, sequential inside one assignment:

1. Account request/step/resource manifests, fixed12-step roster, bounded account
   scheduler/reconciliation, admin progress/resume and API authorization. Owner
   handlers call account.api; account never calls higher owners. Preserve close
   If-Match/recent-auth/community blocker/session revocation. Add one-way completion
   guard now that refuses DONE if **any** owner lacks a real proof; no fake handlers
   or “disabled module = empty” shortcuts. Full finalization waits for II.
2. Conservative independent PREPARED intent/export receipt before close commit,
   COMMITTED/completion journal mechanics and technical platform scoped cancellation.
   Account exporter uses existing ExternalJobHandler; platform adapter only stores/
   verifies technical records. Versioned immutable local synthetic archive fixture
   proves ordering/fault contracts; production adapter properties remain U04. No
   archive network under account locks or fallback to DB-only manifest on failure.
3. Identity secrets/proofs/session/mail inventory and profile/avatar/preference
   cleanup, fresh-proof email/provider reuse and handle reservation. Narrow
   identity→community closure edge remains only ownership check; profile→media only
   source release. Cancel secret mail by producer-supplied opaque scope/job IDs,
   not decryption/querying platform tables. Retain unique email/provider claims
   until all pre-t0 usable proofs/callbacks have been invalidated or drained;
   unknown anonymous state is bounded by expiry plus network completion, not guessed
   ownership. Erasure may leave identity pending while that drain finishes.

Migration allocation **tentative**, recheck HEAD immediately before coding:
V0033 account request/prepared intent/steps/manifests and guards; V0034 platform
technical effect correlation/cancellation indexes; V0035 identity erasure traversal/
proof correlation as needed; V0036 profile handle claims/reservation. Do not create
an empty migration merely to fill a slot; no edits to V0001–V0032. Current account
status CHECK already includes DELETED; that is not proof the new operation exists.

For D06 handle uniqueness, recommend a profile-owned canonical handle-claim row
keyed by normalized handle, backfilled for all live profiles, with explicit LIVE/
RESERVED state. Live claim links its profile; reservation clears former-user link
and stores only expiry. Creation/rename/reservation/expiry share that unique claim
protocol plus tested database completeness guards. Existing profile unique index
remains. A second reservation table queried only by application code is not enough
to arbitrate a concurrent live registration. Review lossless backfill and rename
lock order; no permanent former-handle→user redirect map.

I.1 gate: real PG schema/fresh+32-upgrade+checksums, request/roster invariants,
progress IDOR/CSRF/version/audit rollback, no completion with unknown owner.
I.2 gate: archive unavailable/missing/stale/digest mismatch, crash between every
intent/commit/receipt boundary and conservative unmatched intent; ten attempts/
DEAD/explicit resume; no network under locks, platform no business imports.
I.3 gate: zero subject credentials/mail ciphertext/session/profile/preferences,
avatar release, stale provider/reset/registration callback denial, new UUID on
re-registration, handle reuse race. Old grants and delivered email are not revived.

Deliverables: reviewed APIs/implemented OpenAPI, owned migrations/code/regressions,
updated account/domain/security/events/schema/identity/profile/media contracts,
operator progress/retry runbook and `docs/phases/phase-9a-i.md` with exact checks.
Then main review G1; preserve all not-yet-implemented owners as pending.

Stop/hand back a concrete finding if independent-intent semantics are rejected,
schema/crypto assumptions require a new dependency/edge, proof correlation requires
retaining prohibited raw identity in account, or required real-PG/storage tests
fail. Do not substitute successful session revocation for complete package proof.

## 9A-II — all source owners, physical objects and final completion

Depends on G1/accepted I APIs and main activation, plus accepted D02/D03/D04/D07/D09.
Three capability groups, with an end-to-end final gate in the same assignment:

1. Community/graph participation and content reference repair. Owner handlers use
   account.api and their own persistence only; update all mutation/expiry paths to
   respect terminal fences. Preserve archived-owner/member FKs, cancel admissions,
   erase requester message, clear graph edges with retained pair sequence. Content
   removes live addressed mentions/CUSTOM recipient; last recipient narrows to
   ONLY_ME under post lock. No public audience widening or other-author text erase.
2. Authored post/revision/comment/reaction and media lifecycle. Prompt denial then
  30d redaction,≤100-item nested stages plus fixed aggregate/root rows. Add erasure-origin marker so already queued
   PostRetention jobs preserve others' descendants in that branch. Keep immutable
   revision UPDATE/comment reply_to guards. Reaction tombstone clears choice while
   retaining identity/version. Content/profile release bindings through media.api;
   media enumerates every asset to external manifest, fences generations and drains
   all original/variant writers before all-version/marker proof. Silo alone does
   not supply absolute ingress deadline; synthetic controlled-bound fixture can
   prove protocol, while missing real environment bound keeps WAITING_EXTERNAL.
3. Notification/search terminal guards and final source scans, moderation structural
   subject inventory/retention acknowledgements, preservation-control export and
   strict account finalization. Move projection account guards before recipient/
   rebuild/document rows; freeze/reread stale snapshots. Preserve V0032 real-origin/
   decided-through semantics and ADR-016 immutable payload/control rules. No new
   SQL consumer or source-authorizing projection. Account finalizes solely from
   current fixed-step proofs and external certificate, with no owner callback.

Tentative migrations after I (reallocate globally as necessary): V0037 community/
graph bounded traversal indexes/protocol state; V0038 content erasure-origin/
reaction terminal shape/stage indexes; V0039 media erasure-generation/quiescence/
tombstone metadata shape; V0040 notification terminal recipient/source state;
V0041 search irreversible subject guard; V0042 moderation bounded subject-root/
preservation export refs. Separate files by actual owning changes; no shared
cross-owner UPDATE/JOIN from a worker. Preserve old trigger protections and all
historical migration checksums. No untested mixed-writer rolling guarantee.

II.1 gate: real-PG owner closure/member/graph/ref races both orders and deferred
triggers, others' text/history preserved, indexed bounded reverse traversals.
II.2 gate:≤100-item progress across large descendants/revisions (fixed root rows
counted separately as ADR-017 specifies),30d boundary,
source release/cleanup crash/lease loss, quota once-only, real pinned Silo version/
marker purge with >50 versions, old capability/worker late-write negatives.
II.3 gate: old events before/after close/receipt prune, live rebuild snapshot race,
notification actors/read state, held/unresolved/365d/expiry+new association races,
preservation ledger baseline/export failure and key lifetime.
II.4 gate: one synthetic user present in **every** owner, historical pending with
missing events/deliveries, all denied surfaces, strict DELETED only after source/
object/moderation/certificate proofs; duplicates/restarts converge and no private
fallback. Include no-data account, archived owner, complex held case, ACTIVE
ownership anomaly and storage-disabled-with-existing-assets negative.

Deliverables: owner implementations/regressions, implemented OpenAPI and owner/
schema/security/event/job docs, complete erasure progress/replay runbook,
`docs/phases/phase-9a-ii.md`; full checks and shared-resource feed regression. Main
review G2. Any undeletable version, unresolved/held payload, unknown owner or archive
gap stays pending/failed, not a waiver or DONE. No real account deletion authorized
merely by the synthetic implementation assignment.

## 9A-III — recovery drill, operator hardening and release evidence

Depends on G2/main activation and concrete synthetic storage/archive/DB target
adapters; production environment choices only needed for their dependent gates.

1. Recovery epoch/exposure attestation, bounded ledger import/owner re-erasure and
   preservation controls, isolated DB/storage/key drill below. Account owns recovery
   business facts; technical archive/readiness adapters do not import owners.
   Startup/identity HTTP gate consults account.api readiness; fail closed even for
   anonymous PUBLIC content until epoch release. Distinct recovery handlers may
   run while ordinary scheduler/mail/provider/SSE workers remain disabled. New
   account-owned recovery epoch/verification rows, tentatively V0043; reconcile
   all restored checkpoints under a new verification round.
2. Restricted operator recovery/runbooks/metrics, outages/leases/shutdown, signing
   key rotation with retained verification keys, shared AES/storage key backup/
   recovery and no premature retirement, compatibility/forward-fix migrations,
   resource quotas/abuse and dependency/security review. This is not permission to
   build a new key service or change ADR-016 payload substitution guards. An online
   shared-key rotation/re-encryption design needs its own accepted ADR/assignment;
   meanwhile keep compatible keys for live/held/backup ciphertext and record that
   limitation. Never claim current key-ID labels implement a key ring.
3. End-to-end evidence binder: local checks, restore cut/resource/key/schema hashes,
   bounded large-account work/lag, incident drills, API examples/onboarding exercises,
   media alt-text/accessibility guidance, external gate ownership and unresolved
   risk register. Produce `docs/phases/phase-9a-iii.md`; main may accept local
   engineering delivery while public launch remains explicitly closed.

III.1 gate: restore pre-erasure source with post-backup external ledger, rerun every
affected owner even when restored step says DONE; old sessions/rebuilds/jobs cannot
materialize deleted data. Unmatched intent/stale catalog/missing chunk/key/object
inventory/wrong target deny release. Newer holds survive old snapshot restoration.
III.2 gate: outage/restart/DEAD operator drills, access denial, bounded pool/worker
use and protected telemetry; immutable metadata unaffected. III.3 gate: full local
matrix below, evidence review G3. Stop recovery exposure on any missing proof;
never use real `.local` data or silently choose a production backup provider.

## Verification matrix for future implementation

These are required tests/checks to implement and run later, **not tests run in this
design round**. Use real PostgreSQL18 and pinned Silo where applicable. Separate
connections/commit barriers/pg_blocking_pids establish races, no timing sleeps to
guess an interleaving. Assert final persisted states/events/audit/proofs and byte
absence, not only HTTP codes or mocks. Tests use synthetic accounts and ephemeral
isolated infrastructure, never developer Compose data.

| ID / owner scope | Positive and negative cases | Required proof / gate |
|---|---|---|
| T01 migrations | Empty→new schema; V0032 snapshot→upgrade with ACTIVE/SUSPENDED/PENDING accounts, old refresh chains, archived owner, CUSTOM/history/held payload/media versions; all V0001–32 checksums identical | Real PG/Flyway/Hibernate validation; complete manifests/claim backfill; rollback failed migration atomic; old app never starts against incompatible contract; I–III |
| T02 account/auth | Recent/stale/missing auth/If-Match, active owner409, ordinary user admin progress403 even for own UUID, UUID IDOR, suspended/deleted principal, invalid protocol/owner/ack | No mutation on denial, all mandatory audit writes rollback with state; revoked Bearer/refresh never regains progress access; I |
| T03 independent close intent | Archive unavailable; PREPARED write succeeds but DB receipt/close fails; concurrent retry; lost204; COMMITTED export missing; wrong digest/current cut | No successful204 or destructive step without PREPARED receipt; committed close protected even before COMMITTED export; unmatched intent quarantines restore, not ACTIVE-account erase; I/III |
| T04 atomicity/fencing | Fail after owner write, audit, checkpoint, continuation, receipt, fenced ack; duplicate/out-of-order event; crash lease60s; ten failures/DEAD; explicit stale/fresh resume | State+receipt+step+job commit or rollback; stale worker cannot READY/complete/decrement quota; no auto-new budget after DEAD; I/II |
| T05 identity/proof reuse | >100 tokens/session generations, pending change-email/reset/LINK/REAUTH; anonymous LOGIN/onboarding/registration before cut; callback exchange during close; same email/provider later new account | FK-safe bounded purge and cancellation refs; zero ciphertext/proofs/credentials; fresh proofs new UUID, no email link/old grant; release cannot race pre-t0 proof; I |
| T06 profile/handle | Name/bio/preferences/avatar with others' attribution, deleted profile absent; handle rename/create/reserve/release at exact30d boundary | No old display-name/avatar fallback; database uniqueness across claims; independent new handle owner, no old-ID link; I |
| T07 participation races | Create/transfer owner vs close both orders; archive leave/erasure; active-owner corruption; accept/join/invite/request/expiry/ban vs close; follow/block/friend expiry both orders | Account→community/pair lock order and terminal state, archived FK retained, zero effective membership/edges; others' admissions/history safe; II |
| T08 content/ref policy | PUBLIC/FOLLOWERS/FRIENDS/CUSTOM/ONLY_ME/COMMUNITY; last CUSTOM recipient; archived/departed scope; root with other-author replies; immutable reply_to/revision attacks; orphan/shared hashtag | Zero subject text/revisions/relations after30d, only intended audience narrowing, no erased other-author text; no trigger bypass; SQL/object authorization parity incl published views; II |
| T09 late source mutations | Write/edit/comment/reaction/report before/after close; captured source facts then closure; new structural moderation association after root proof | Earlier committed work included in final scans, later private writes denied; terminal reaction cannot reactivate or regain type; no premature finalization; II |
| T10 media/process | Every state inclFAILED/DELETED, ready/released binding, multiple generations, partial outputs, >50 versions+markers, purge relist truncation, HEAD absence, active capability expiry, late accepted PUT | Storage calls out-of-TX, durable intent+manifest IDs, writer drain/deadline before absence ack; incomplete/disabled/outage blocks; already downloaded bytes explicitly outside recall; II/III |
| T11 crash external effect | Crash after delete/append before checkpoint; process remains stale after lease reclaim; late variant PUT after apparent purge; stale ack after quota row change | Repeated purge/list/append idempotent, once-only accounting, no false DONE; terminal tombstone sweep detects incident and blocks/invalidate proof; II/III |
| T12 notification | Subject recipient and actor in another's aggregate; other eligible actors/read versions retained; old real-origin/update/delete after30d and source prune120d; SSE open on close | No group/badge/actor/type resurrection, no old-session stream; V0032 decided watermark remains truthful and unrelated actors unaffected; II |
| T13 search/feed/media privacy | Worker stopped, stale index body; empty index receiving pre-close snapshot; live rebuild both winning orders; old feed/search/history/media cursor and pinned URL reissuance | Current source denial and stored search/vector absence; no fallback; account guard before search rows; batched query bound/SQL-view parity; II |
| T14 moderation/keys | Reporter/actor/target/source/parent/assignee roles; terminal365d boundary vs unresolved/hold; hold wins/cleanup wins; large linked action root; missing key; pre-backup later renewed hold | Step waits honestly, no DELETED with applicable bytes; ADR-016 immutability/one-to-one/STARTED/DONE unchanged, root→payload locks; held keys preserved; II/III |
| T15 historical reconciliation | Deletion before consumer registration; event/delivery/receipt expired/missing; partial checkpoints/duplicate passes; new roster version/disabled owner; DELETED residual repair | Current account scan seeds all required work; fixed roster not consumer count, no invented source versions or ignored unknown owner; I/II |
| T16 large account | ≥10000 posts/revisions/comments/edges/notification actors/assets with skew and unrelated ballast; root with >100 actions, refresh chain>100 | Every pass≤100 collection-items/payloads plus measured fixed protocol/root rows; one atomic100-recipient+post switch can touch101 source rows, never100 such posts/pass. Fixed checkpoint size/indexed seeks/noN+1/restart/bounded memory; record pass count/locks/query duration/lag without SLA invention; II |
| T17 backup/restore | Pre-request snapshot, post-request independent intents/completion/resource chunks; old objects not in restored DB; already-DONE restored step; duplicate/missing/stale catalog; source still accepts writes | New epoch offline, current cut + no split-brain writer proof; all owners reverified; cannot expose erased data before restore attestation; III |
| T18 key recovery/outages | PG/socket/lock/pool, archive/SMTP/storage down, wrong key/missing version, restart/shutdown during delete/export, lost independent ledger | Closed required paths503, queues durable/DEAD visible; missing keys not treated as erasure, no permission cache, no timeout/pool relaxation; I–III |
| T19 architecture/API/runtime | Owner API imports, no callbacks/foreign SQL/JPA, current scopes, allowed JSON/error codes/OpenAPI; real app/media Compose fresh+upgrade, native HTTP/SSE teardown | ArchUnit/SQL contracts/formatter/OpenAPI/Java21 verify; no exclusions. Compose config and real startup/readiness/security probes distinct checks; I–III |
| T20 security/operability | Pinned redacted staged+full-history scans, synthetic positive control, secret mount/IAM; shared queue resources + feed after changes | Same exact ignores/no weaker scanner; all3200 benchmark requests succeed with pool10+diagnostic50 when shared resource changes; retain failures and full distributions; I–III |

Targeted tests first, then Java21 formatter check (`./mvnw spotless:check`) and
`./mvnw -B -ntp verify` for each implemented package. Use `spotless:apply` only on
owned code, review its diff. Docker/Testcontainers failures are failures, never
skips. Run `docker compose config --quiet` and resolved app/media configuration;
actual build/startup/31-or32→new-schema upgrade/security probes need their own
record. Image packaging with skipped tests does not replace verify. Preserve
deferred-trigger, published SQL-view shape/policy and existing OpenAPI tests.

Compatibility and rollback must be demonstrated, not inferred from nullable
columns or a passing fresh install:

| Upgrade state | Permitted recovery / required compatibility test |
|---|---|
| Migration fails before committing a new version | Keep all writers stopped; prove transaction rollback/checksums and retained data. If an earlier new migration already committed, inspect that prefix and correct forward; never edit an applied file |
| New schema installed, protocol never activated | Start only an artifact explicitly tested against that schema prefix. Do not assume V0032 binary tolerates erased reaction/media shapes, handle claims or startup gate |
| Any erasure intent/effect or recovery epoch activated | No rollback to an artifact that omits terminal fences/exposure barrier. Retain a tested compatible Phase9 previous artifact with the same protocol/guard semantics, or keep service closed and forward-fix |
| External delete/redaction already committed | Irreversible: binary rollback cannot restore data. A backup is not a rollback shortcut; all restore reconciliation/ledger gates still apply |

Before launch, test candidate and compatible previous Phase9 artifacts against
the deployed schema/retained proof data, include a failed-start/rollback rehearsal,
and record which HTTP/worker combinations are safe. Existing V0030–V0032 write-stop
requirements remain. Do not advertise rolling old/new writers or a compatible
rollback artifact until that exact pair is tested.

Heavy feed regression is required for I changes to shared worker/fencing/cancellation/
pool occupancy, II projection/source/resource changes and III startup/worker changes.
After a wholly documentation-only review no application/benchmark rerun is needed.
Existing isolated benchmark command (not run in this design task):

```bash
./mvnw -B -ntp -Dfeed.benchmark=true -Dtest=FeedBenchmarkTool test
```

Keep its real1M posts/10k follows/50 readers/pool10 and labeled50, all3200 successes,
attempt/failure rate and success-only percentiles, cold/steady/sparse/exhausted
scenarios, SQL/EXPLAIN/connection-wait/JVM evidence. A latency goal is not a measured
result. No automatic extra reruns after appropriate checks pass unless new failure/
change justifies them. Do not swallow a failing first run by reporting only a retry.

## Isolated synthetic restore drill specification (future, not executed)

All paths below are an explicit **proposed sandbox layout**, not existing backups
or commands to run now. Future tooling must allocate a unique directory with
`mktemp -d /tmp/social-p9a-drill.XXXXXX`, resolve its absolute path and validate
every source/target beneath it. Never use `.local`, Compose volumes, host home,
workspace root or any reachable production endpoint as a restore/delete target.

| Item | Proposed resolved path / endpoint boundary |
|---|---|
| Synthetic pre-erasure source snapshot | `<drill>/source/pg-v32.dump` (read-only artifact); source PG uses isolated Testcontainers database `p9a_source`, random loopback port |
| Synthetic versioned object copy | `<drill>/source/objects/` with explicit quarantine/delivery version inventory; source Silo isolated from developer Silo, copied artifact read-only |
| Independent ledger (outside the backup set) | `<drill>/authority/ledger/`, `<drill>/authority/latest-cut.json`; retained after source snapshot, distinct mount/credentials; test emulates independent authority, not proof of separate physical failure domain |
| Compatible synthetic keys | `<drill>/authority/keys/`, restricted generated test keys only, never printed; backup credentials contain none of them |
| New restore target | `<drill>/restore/pg/` and `<drill>/restore/objects/`, new PG database `p9a_restore` and fresh target Silo container/buckets `p9a-restore-quarantine`/`p9a-restore-delivery`, random loopback ports and target-only credentials |
| Redacted evidence | `<drill>/evidence/` containing schema/source hashes, checksums/version counts/probe codes and timings, no secrets or text; recommended expiry≤7d |

Drill sequence:

1. Seed two synthetic owners plus a third unaffected account, all56 table categories
   where reachable, every media version/marker/generation and a held moderation
   case. Capture PG/object backup before deletion; ledger remains a separate later
   timeline. Capture an old active JWT/refresh in test memory only.
2. Execute synthetic close/erasure on source after activation, not in this design
   task. One subject completes only after legitimate advanced test-clock retention
   and proven writer drain; the other stays pending due hold. Export erasure and
   preservation controls; include asset prefix with orphan object missing from
   restored source DB. Record immutable completion and authoritative cut.
3. Restore **only** those synthetic read-only artifacts into the new target, using
   distinct target credentials. Verify exact target before mutation. Keep ingress/
   ordinary workers/SMTP/OAuth/SSE off; original sources remain read-only. Stop old
   source writers before sealing final cut; a running source is a failing negative.
4. Prove pre-erasure backup alone would contain subject data in restricted test
   assertions, without serving/logging it. Import current independent cut, create
   new recovery epoch, re-deny accounts/sessions, re-erase all restored private
   bytes/versions through owners. Completed source step does not skip target work.
   Restore held control and retain its legitimate private bytes under restriction.
5. Run T10–T18 negatives: stale-but-valid old cut, missing chunk/key, altered digest,
   truncated catalog, uncommitted PREPARED, late writer, newer hold absent from DB,
   checkpoint/ack crash, missing owner and disabled storage. Each must withhold
   exposure/attestation; repair only the tested cause, never force the gate green.
6. Verify successful target absence via owner SQL APIs and real pinned Silo list/
   HEAD, denied ordinary HTTP/feed/search/notification/media/access/JWT/refresh,
   no resurrection after replay/rebuild and unaffected third-account data. Verify
   held evidence can be read only through current scoped audited authority. Recheck
   latest cut under source-write isolation; issue epoch attestation then test
   service exposure. No real SMTP/provider call or external notification occurs.
7. Record actual restore duration/observed data-loss interval, ledger intent loss0,
   redaction/purge passes/lag and failures. Later authorized teardown removes only
   validated synthetic containers/directories, explains retained evidence/recovery;
   no broad recursive deletion of a workspace/home/volume.

Local pg_dump/object-copy drill is not a managed PITR/WAL, region outage, immutable
storage, IAM or key-recovery certification. Future production drill needs its own
source/target IDs, backup/ledger authority and explicit operator authorization.

## Local evidence versus public-launch requirements

| Gate | Locally implementable evidence | Additional public/production evidence and owner |
|---|---|---|
| Erasure semantics | All owners/56-table fixture, bounded proofs, denied paths/replay and strict status | U01 confirms scope/retention/minors/jurisdiction/notice/audit disposal; no unapproved irreversible policy |
| Independent ledger | Synthetic immutable append/current-cut/anti-rollback and fail-closed tests | U04 actual append-only provider/IAM/retention+independent trusted catalog/failure domain, accepted-intent durability, complete historical baseline |
| PG backup/recovery | Fresh/upgrade/checksum and isolated restore protocol | Encrypted automatic base+WAL/PITR/replica/export catalog, recorded target hardware; PG RPO≤15m/RTO≤4h measured, not assumed; erasure/hold intents loss0 |
| Media deletion/restore | Real pinned Silo versions/markers/permissions/failure tests plus bounded-writer fixture | U03 actual storage region/KMS/IAM/delete/replicas/object-lock conflict, compatible DB/object backup; enforced total ingress/worker deadlines and overwrite budgets |
| Keys | Existing RS256 retained-key tests; compatible AES/storage key restore, premature-retirement denial | U04 secret-manager access/backup/rotation/recovery, all retained/held/backup ciphertext readable; separately accepted multi-key/re-encryption if required |
| Providers/mail | Deterministic provider server + Mailpit, no unsolicited live calls | U02 Google registration/smokes; Meta official-contract/app review/deauthorization/deletion callbacks verified; production SMTP/domain/delivery/retention; disabled until complete |
| Admin/telemetry | Current ADMIN/CSRF/audit/role denials and protected metrics | U05 restricted gateway/MFA/runtime-vs-DDL roles/operator authority, alert delivery/incident on-call, redacted sink retention |
| Capacity/abuse | Large-account pass/queue memory/query bounds, feed benchmark, failure distributions | U06 real hardware/budget, sustained load/soak, measured worker/erasure/notification/search lag and latency objectives; shared limits before multiple public API instances |
| Security/release | Java21 verify/architecture/OpenAPI/Compose/image/pinned secret gates | Dependency/container/SBOM/security review and exception ownership, processor sandbox/egress/TLS/proxy, selected redistribution license, compatibility rollout and rollback/forward-fix rehearsal |
| Product/readiness | Accurate docs/API examples, alt-text guidance and onboarding exercises | User-facing erasure wording, accessibility/product review and explicit launch decision; neither local commits nor remote CI success inferred |

Remaining inputs are **exactly U01–U06 in ADR-017**; resolve choices with main and
the user/environment owner, without asking for secrets. No legal compliance,
production capacity, remote CI, public rollout or Phase9B claim follows from tests.

## Operability risk register carried into implementation

| ID | Evidence / implication | Required follow-up, no waiver |
|---|---|---|
| P9A-R01 | Main round2 first verify had one ERROR in unchanged `Phase4IT.candidateQueryUsesActualAuthorIndexOnSkewedDataAndHydrationRemainsBatched`, JDBC commit I/O/SocketTimeoutException line1793 after12000-row fixture. Other387 passed | Preserve failed log `/tmp/social-p78-r2-main-verify.log`. Isolated unchanged recheck58 PASS/32.436s and full unchanged388 PASS/5m11s, finished2026-10-17 11:31:10+07, zero errors/failures/skips. Root cause unproven; capture JDBC/pool/PG wait/resource evidence if it recurs, never relax timeouts/exclude test. [Accepted archive](../handoffs/phase-7-8-notifications-search-moderation-ops.md) retains exact evidence |
| P9A-R02 | Developer measured3200/3200 pool10/50 benchmark; dense p95≈2.02–3.00s,500ms target unmet. Main inspected matching evidence/303-file manifest, did not rerun heavy gate | Measure shared-worker/projection impact with all-success requirement; sustain/target-hardware tuning before any latency SLA. Do not raise pools/timeouts or substitute new goal |
| P9A-R03 | Silo rolling idle upload timeout and periodic repurge do not prove total write completion | WAITING_EXTERNAL until enforced quiescence/deadline; synthetic fixture distinguished from actual deployment ingress |
| P9A-R04 | Current AES key IDs are labels; held/unresolved ciphertext/old backup may outlive short proofs | Preserve compatible keys; no implicit key swap/crypto-shredding. New rotation design requires acceptance before replacement |
| P9A-R05 | Retained immutable audit/metadata and unresolved/held365d payload may retain personal data; strict completion can wait indefinitely | Make this visible in status/policy, keep ADR-016. U01 audit disposal/privacy scope must be settled for public launch |
| P9A-R06 | Historical pending deletion may lack deliveries; DB-only or lagging manifest loses recent erasures; restore may lose recent holds | Full baseline export/pending scan + conservative independent intents + latest cut/preservation reconciliation and exposure barrier; unmatched/incomplete cut stays offline |
| P9A-R07 | Pre-fix notification initial activity loss is ambiguous; V0032 conservatively decided legacy rows | No automatic historical repair/reset receipt/decision to make erasure tests pass; separately scoped repair only |

## Design-round verification and handoff contract

This round checks repository evidence, all-table inventory, proposed dependency/
policy consistency, links, whitespace/scope and existing pinned redacted staged/
full-history scans with unchanged exact ignores and synthetic detection control.
It does **not** rerun application tests for unchanged executable files or perform
the proposed restore/storage experiments. Record actual check outcomes and logs
in the fixed mailbox after checking them, never mark matrix T01–T20 executed here.

Commit A+B+C+ADR index as verified design documents first. Then commit
`docs/agent-handoff.md` separately with the **real** design commit, accepted code
baseline, D01–D10/U01–U06, actual checks/limitations and READY_FOR_REVIEW/next owner
main. Preserve earlier reviewer findings; only main accepts/resolves decisions.
No self-referential handoff hash, push, real erasure/restore, agents or deployment.
