# Phase 9A-II — sources, media and strict completion

**Accepted by main at G2 on2026-10-24**, executable `8df0b6b`, isolated synthetic
policy only. P9AII-R1/R2 are resolved; see round2 below. Assignment baseline
`6162303`, accepted I executable `bbbde93`, G1 review `faf0ad8`. The fixed
[mailbox](../agent-handoff.md) supplies the actual final code hash and review state;
development does not self-accept G2. [Assignment](../work-packages/phase-9a-ii.md),
[pre-code map](../work-packages/phase-9a-ii-implementation-map.md) and
[ADR-017 C01–C05](../adr/017-account-erasure-and-recovery.md) bind this delivery.
Development used no agents, push, III, real erasure/restore, provider contact or deployment.

Historical main round1 on2026-10-23 was **CHANGES_REQUESTED** at executable `6d3fbfc`.
P9AII-R1 (ordinary suspended-author retention) and P9AII-R2 (missing moderation
eligibility provenance in immutable recovery exports) were OPEN at that review.
The implementation report below is developer evidence; round2 supplies acceptance.

## Implemented behavior

All twelve fixed owner steps have real registered handlers. Community/graph remove
terminal participation while retaining archived ownership/attributable UUID history;
unexpected ACTIVE ownership remains an explicit blocker. Invitation acceptance
guards both participants and rejects a terminal inviter. New inbound blocks cannot
recreate graph residues after its proof; suspended-target behavior is preserved.
Graph handles at most12 canonical pairs/96 possible relation items per pass.

Content reference repair shares a100-item budget across repeated current mentions and the subject recipient, and narrows last-recipient CUSTOM to ONLY_ME without changing
another author's text/revisions/reply IDs. Authored roots/comments soft-delete and
reaction choice becomes a one-way null tombstone. At DB t0+30d, nested100-item stages
remove revisions, verified-export attachment/alt-text mappings, recipients, mentions,
tags, body/link/comment text. Soft-post enumeration takes at most9 roots plus their schema-bounded ten attachments each (≤99 fetched collection items). Queued ordinary parent retention skips terminal
authors/erasure-origin posts, preserving others' descendants. Post/comment/reaction
terminal shapes reject resurrection. The bounded one-post+100-recipient atomic
audience exception is explicit; fixed root/protocol rows are counted separately.

Media scans every asset state/generation and verifies independent ID chunks before
mapping loss. Old-generation variant metadata is pruned in ≤100-row passes after claiming; new metadata inserts are fenced. Source release precedes stable request/generation claiming. Old
processing prepare/complete/failure cannot READY/charge after close. External drain
and all-version/delete-marker purge run outside SQL locks; current request/round/
step/execution/asset generation controls the once-only accounting/metadata ack.
**No runtime writer-drain adapter exists.** Existing assets wait when drain/storage
is unknown; settling, lease expiry and sweeping cannot supply positive proof.
Only the sealed/joined synthetic writer harness attests local positive completion.

Notification materialization guards sorted actor/recipient accounts before recipient
rows, refreshes current sources and preserves V0032 decisions for suspended denials.
Terminal principals cannot create new projection state. Cleanup pages source links/
contributions before group deletion and preserves unrelated actors/read versions.
Search guards owners before rebuild/document locks, rechecks captured snapshots and
permanently blanks terminal USER/POST text/vectors even in an empty index. Author
enumeration comes from content's API, not an old projection. Feed keeps authoritative
current-source policy with no artificial erasure step.

C01 existing pending-subject cases remain reviewable/resolvable with mandatory
encrypted detail. Structural account guards precede moderation/completion proof
invalidation and case locks; ordinary new roots and final-DELETED private appends
are denied. Bounded structural root/history/target/source/parent inventory does not
decrypt arbitrary free text. ADR-016 unresolved/held/terminal+365d waits retain bytes
and keys. Independent BASELINE/control history and one-way redaction records precede
proof. Hold/renewal/release exports conservative PRESERVE intent outside locks, then
rechecks authority/root/version before control/history/audit and COMMITTED intent.
Archive failure preserves the prior hold. Explicit ADMIN PRESERVATION maintenance
resumes at most100 DEAD immutable exports without deleting failure history.

C02 completion exports an immutable certificate of eleven current prerequisite
proofs and the resource manifest, excluding its own step version/receipt/final event.
The final transaction takes subject exclusive then roster rows in order, compares
the captured vector and commits receipt/completion DONE/account DELETED/audit/outbox
atomically. New DB guards reject incomplete/null-bound certificates, unknown resource
owners/kinds and terminal reactivation. Stale success/failure cannot affect a successor;
changed vector gets a new attempt. No-data accounts still require every real owner.

## Schema and boundaries

V0038–V0051 are consecutive actual owner migrations. V0001–V0037 are untouched.
Schema refinements after development tests use later migrations:
media live/claimed shape (43), NULL-safe completion/invalidation (48), terminal content
relations/comments (49), resource owner/kind allowlist (50) and variant insert fence (51). V0046 adds only two
moderation protocol tables, preserving the inventory's historical56-table baseline.
No dependency, module DAG edge, foreign SQL view/consumer or generic workflow engine
was added. Architecture acknowledgement callers are the explicitly assigned owner
handlers; no architecture exclusion was introduced.

The I binary is incompatible with nullable erased choices/media metadata and II
finalization. Stop all writers, apply/validate the complete schema, keep ingress
closed during partial prefixes and forward-fix. Binary rollback/backup restore is
not an erasure undo mechanism. Historical DELETED with unproven request/generation
remains a visible issue for authoritative review, never guessed provenance.

## Verification evidence

Durable evidence root: `/home/vonhuphi/social-p9aii-evidence.ll068R/`.
Failed/interrupted attempts remain under `attempts/`; final executable manifest,
curated reports, runtime isolation, benchmark and secret-scan results are recorded
there before final handoff. No earlier phase's run is relabelled as current evidence.

Final and targeted evidence (final checks supersede earlier executable coverage):

| Check | Actual result |
|---|---|
| Final full Java21 verify r5 | **469 PASS** (62 fast +407 IT), zero failure/error/skip;8m32s, finished2026-10-22 23:52:16+07; formatting, architecture, OpenAPI, published SQL-view/object-policy parity, migration prefixes/rollback and P9AI-R1/R2 included |
| Final bounded-reference/media checks | 10,001 current mention offsets +last recipient drain in101 passes (≤100 combined rows);10,001 old-generation variant rows drain in101 passes, post-claim insert denied; other-author bytes and quota preserved |
| Fresh/V0037/V0032 native r2 | All3 PASS on current image: real PG/Silo/app HTTP security/media/SSE +app restart; historical no-data subject reaches all12 DONE/DELETED with verified certificate; media subject remains PENDING without fake drain; accepted37/32 prefix checksums unchanged |
| Content protocol, inherited P9AI-R1/R2 | 28 IT +62 fast PASS in content r3; over10,000 revisions/comments, ≤100 examined/pass, other-author reply preserved |
| Real PG/Silo/archive media r5 | 2 IT +62 fast PASS; >50 versions/markers, joined late ingress/variant writers, crash before ack, stale ack and once-only quota; missing drain/storage remains waiting |
| Moderation r2 | 44 IT +62 fast PASS; existing OPEN case after close resolves with ciphertext, waits retention; archive outage/authority change preserve hold; expired root exports redaction; original hold/cleanup interleavings retained |
| Finalization r2 | 34 IT +62 fast PASS; every real owner/no-data completion, nonrecursive receipt, stale vector/superseded failure, final audit rollback/retry, reactivation denial |
| Large/protocol r2 at V0049 | 73 IT +62 fast PASS,2m00s,2026-10-22 22:57:10+07; all new migration prefixes/checksums/failure rollback plus actual PG/Silo/archive |
| Large graph/notification | 10,001 edges +10,001 actors;1,085 passes to prerequisite completion, collection delta≤100, sampled peak heap148,724,296 bytes; other actor/read state preserved |
| Large empty-index search | 10,001 posts;202 passes, ≤100 sources/pass, all terminal text/vectors blank |
| Large all-state media inventory | 10,001 FAILED/DELETED assets;101 verified chunks in203 passes, ≤100 IDs/pass, sampled peak heap170,723,392 bytes; truthful WAITING_EXTERNAL without drain; no mapping/purge success fabricated |

These heap observations are sampled JVM used heap, not retained-size or capacity
guarantees. Counts describe the isolated fixture; fixed protocol/guard rows and
actual collection mutations differ. No pool, timeout, lease, retry budget or planner
setting was raised. The final full reports and log are retained under
`full-verify-r5/`; `executable-manifest.json` binds391 source/config/API/CI paths and
is checked before delivery. `native-run-r2.log`, per-fixture probe/bootstrap/
isolation/checksum files, `image-identity.txt` and `native-archives/` bind the3
native checks to the current image/source. All ports bind127.0.0.1; each project has
its own DB volume, storage directory, archive and lineage with new synthetic keys.
The native bind-mount fixture runs UID1000 with the existing read-only/cap-drop
settings; the built image default remains non-root UID10001. Each app actually
restarts before its durable historical request converges. SSE closure observations
are4956/4975/4937ms for fresh/upgrade37/upgrade32, and old capabilities deny after
their real TTL. `native-cleanup.txt` records removal of only the3 fixture projects/
volumes and generated storage/keys, after archive retention outside `/tmp`.
Fixture teardown is not an erasure acknowledgement. The unchanged3,200-request
pool10/50 benchmark completed with zero failures; detailed results follow below.

Lock/query observations distinguish inspected bounds from measured values: full
sorted account sets precede owner locks; the named invitation/block and empty-index
snapshot races observe actual PostgreSQL Lock waits in both orders. Graph takes
at most13 account guards and12 canonical pair advisory locks; content soft-post
prefetch has≤99 root+attachment items, and child DML is100-item bounded. Repeated
mention/variant before/after SQL row counts equal the durable examined delta.
Batched source queries and reverse-index plans are tested; no claim of an aggregate
per-pass SQL-statement profiler or retained-heap capacity measurement is made.
Continuation queue jobs/checkpoints are durable and version-fenced; DEAD budgets
remain explicit and require current audited ADMIN resume. Large fixtures report
pass counts and sampled heap rather than a latency guarantee.

Failed/interrupted attempts are preserved, not relabelled as passes. Early targeted
runs exposed fixture/schema expectation issues, the suspended-actor V0032 decision
regression (fixed), and the existing disabled-policy ambient hold transaction
contract (preserved). Full r1 did not compile due to a duplicate fixture field;
r2 had15 stale migration-count expectations and one old owner-placeholder count;
r3 exposed the safe optimistic snapshot abort described below. R4 was interrupted
for trailing-whitespace normalization of the new, uncommitted V0048 before final
artifact/checksum verification; no accepted V0001–V0037 checksum changed. R5 is the
complete current-source pass. The runner also reports a fork shutdown after
System.exit(0)/30s and cached stopped-container Hikari warnings; Maven exits0 and
all469 tests have zero failures/errors/skips. Those logs remain intact.

The first image build lost its Docker RPC when the user interrupted the session;
the unchanged-source second build passed. Native r1 reached close/security checks
and restarted successfully, then its harness polled the obsolete dynamic Docker
host binding. R2 resolves the actual loopback binding after restart. Failed native
logs/archive and unstarted sandbox-loss metadata remain under `attempts/`; keys
and data are freshly generated only for the isolated native fixtures.

## Unchanged feed benchmark — October23

`FeedBenchmarkTool` is unchanged from the assignment baseline. Java21 command
`./mvnw -B -ntp -Dfeed.benchmark=true -Dtest=FeedBenchmarkTool test` completed
PASS in7m18s,2026-10-23 19:52:07+07: **3,200 attempts/successes, zero failures**,
all16 scenarios/50 concurrent readers. Seed is1,000,000 posts,20,050 synthetic
profiles and10,000 follows per reader; seed332.887s, database1,123,661,503 bytes.
Observed host8 processors, MemTotal7,848,004kB, JVM max2,011,168,768 bytes; cgroup
cpu.max/memory.max files were unavailable, not assumed unlimited. No native app,
heavy build or verify ran alongside the benchmark. This host also runs the IDE/OS;
these observations do not establish production capacity or isolate a cause.

The default pool remains10;50 is diagnostic only. All latency values below are ms,
all rows have zero failures. Raw diagnostics retain acquisition/held/outside-execute
measurements, DB wait samples and query plans in `benchmark.log`; CSV/JSON and
benchmark XML/text are preserved separately from full verification reports.

| Pool | Scenario | Successes | p50 | p95 | p99 | SQL/attempt |
|---|---|---:|---:|---:|---:|---:|
| 10 | first_burst | 500 | 1405.142 | 3110.893 | 4105.057 | 15 |
| 10 | post_burst_steady | 500 | 1205.770 | 2642.999 | 3220.084 | 15 |
| 10 | no_sources | 100 | 87.065 | 184.702 | 197.948 | 6 |
| 10 | rare_old | 100 | 279.180 | 497.506 | 605.165 | 12 |
| 10 | last_page | 100 | 332.913 | 807.968 | 889.049 | 11 |
| 10 | mostly_rejected | 100 | 167.845 | 379.276 | 434.182 | 6 |
| 10 | overlap | 100 | 1198.791 | 2581.124 | 2794.529 | 15 |
| 10 | exhausted | 100 | 156.626 | 415.938 | 477.448 | 5 |
| 50 | first_burst | 500 | 1583.991 | 2786.914 | 3466.944 | 15 |
| 50 | post_burst_steady | 500 | 1209.725 | 2149.009 | 2465.309 | 15 |
| 50 | no_sources | 100 | 27.819 | 57.997 | 77.028 | 6 |
| 50 | rare_old | 100 | 133.910 | 219.154 | 276.975 | 12 |
| 50 | last_page | 100 | 240.788 | 385.932 | 415.666 | 11 |
| 50 | mostly_rejected | 100 | 121.239 | 250.169 | 298.239 | 6 |
| 50 | overlap | 100 | 950.884 | 2031.576 | 2131.854 | 15 |
| 50 | exhausted | 100 | 162.663 | 343.826 | 375.184 | 5 |

Pool10 first burst observed peak43 pending/10 active connections and CPU load1.0;
no acquisition failed. Dense scans examined/returned10,000/10,000 (ratio1.0),
15 SQL statements per attempt. Dense/overlap p95 remains above500ms, including
with pool50. The500ms goal stays OPEN; this successful run does not establish the
cause of earlier phase timeouts or authorize pool/timeout changes.

Pinned Gitleaks v8.28.0/digest from CI scans staged code and full history with
redaction and the unchanged3 exact exceptions. The external generated positive
control detects its intended generic key. Final scan logs/reports, config/diff/link
checks and the executable/artifact manifests are retained in the evidence root;
no new finding or broadened ignore is accepted by development.

## Retained limitations and next owner

U01–U06, production retention/IAM/keys/independent latest-cut authority, real writer
completion bounds, restore/import/exposure barrier and III remain unapproved.
Held/unresolved moderation, unknown writers, missing receipts and bad provenance
remain pending/failed. UUID attribution, archived-owner FKs, others' bytes/replies,
immutable audit/job/control records, restricted expiring backups and delivered
external copies remain declared exceptions. The500ms feed goal remains open.
Observed snapshot interleaving: source inspection can populate Hibernate's account
entity before waiting for the account guard. If close commits meanwhile, Hibernate
rejects that obsolete version; the entire projection transaction rolls back before
any document write. The existing bounded worker retry uses a new transaction and
writes the permanent blank marker. The named two-order regression checks both this
safe abort and stale upsert denial; no in-transaction optimistic failure is ignored.

## Main review — round1

Main independently ran Java21 full verify on the submitted executable:469 PASS
(62 fast +407 IT), zero failures/errors/skips,8m04s, finished2026-10-23 20:10:12+07.
The50 current-run class XML reports agree with the log; three unrelated older
target reports are not counted. All391 manifest paths match. Applied V0001–V0037
are unchanged; diff/Compose checks and pinned full-history scan (76 commits)/
synthetic positive control pass. The fork shutdown and stopped-container warnings
remain visible in `/home/vonhuphi/social-p9aii-main-review.YE7Otx/verify.log`.
Native and benchmark checks above remain developer evidence, not main reruns.

Two code-inspection findings still require fixes and new regressions:

- **P9AII-R1:** `PostRetention` returns for every inactive author, including
  SUSPENDED; due ordinary cleanup is acknowledged without rescheduling. Suspension
  must not waive the thirty-day retention policy, including with erasure disabled.
- **P9AII-R2:** moderation's immutable export contains observation time but not the
  source terminal/age eligibility anchor required by ADR-016/017. Correct the
  export and old-format baseline compatibility; do not defer this II prerequisite
  to implementing the III importer.

Neither finding was separately reproduced as a failing test by main. Existing
green tests do not accept G2. See the mailbox for precise anchors, scenarios,
required outcomes and regression gates. Next owner is the existing Phase9A-II
development chat for the combined repair package. Main changed documentation only,
did not push this candidate, and has not activated III.

## Development repair — P9AII-R1/R2, review baseline2f28960

Both findings remain OPEN for main's review. R1 narrows ordinary PostRetention's
bypass to DELETION_PENDING/DELETED or erasure-origin. SUSPENDED accounts retain
ordinary due cleanup without gaining access. Three real PostgreSQL/durable-worker
cases cover >500 revisions, a deleted post/descendant and removed-post media while
erasure is disabled, then reinstatement without replacing completed jobs. Existing
enabled close-first/erasure-origin descendant protection remains unchanged.

R2 exports format2 with typed KNOWN/UNRESOLVED/UNKNOWN eligibility: report resolved_at
or standalone action created_at plus the authoritative SQL365-day eligibleAt,
captured with root/version/control. recordedAt remains observation time; current
and proposed hold deadlines stay separate. History includes original createdAt;
private evidence/reasons/ciphertext remain excluded. No ADR-016 rule changes.

V0052 adds immutable export format metadata and format-aware baseline state. Existing
format1 bodies/digests/receipts are untouched. A format1 complete cache restarts its
cursor and appends a new baseline, including DONE/redacted roots. Matching format/
root/version receipts alone advance the current cursor. Pages obey both100 history
rows and the existing16KiB archive cap; a byte-bounded prefix never skips history.
V0001–V0051 are unchanged; no new table, dependency, consumer or HTTP shape.

Actual archive-body regressions cover OPEN/RESOLVED/DISMISSED reports, standalone
actions, both root types' hold/release intents and committed controls, exact source
deadlines deliberately years before export, private-payload exclusion,207 history
rows across verified bounded pages, legacy complete/redacted caches and stale root
receipt isolation. Existing outage/DEAD/resume/superseded-execution and post-redaction
tests remain. V0051→V0052 proves old receipts/times unchanged and failed-prefix
transaction rollback/forward recovery, alongside earlier upgrades/checksums.

Final combined targeted command includes ErasureFoundationIT, ErasureMigrationIT,
ModerationOperationsIT, ErasureProofChainIT and the three new Phase4IT cases:
95 IT +62 fast PASS, zero failures/errors/skips,2m51s, finished2026-10-23
20:40:06+07. Failed development attempts (including actual archive byte overflow
and an overly strict page-count assertion) are retained without weakening the
archive limit or negative paths. Historical469/native/benchmark rows above remain
bound to the original submission; repaired delivery evidence is recorded below.

Repaired Java21 `./mvnw -B -ntp verify`: **481 PASS (62 fast +419 IT)**,
zero failures/errors/skips,7m26s, finished2026-10-23 20:47:49+07. All50 current
class XML reports agree with the full log; stale targeted/benchmark reports are
retained but excluded. Architecture, OpenAPI, SQL/object-policy parity, all
inherited regressions and both finding matrices pass. Existing fork shutdown/
stopped-container warnings remain in the full log; no test exclusion was added.

All392 executable/config/API/CI manifest paths match repaired bytes. The unchanged
Dockerfile build passed on retry; the first container build's Spotless dependency
resolution failure is retained. Fresh and native V0032/V0037/V0051 upgrades all
reach V0052 with prefix checksums unchanged, real PG/Silo media processing/security,
CSRF403/stale412/close204, session/media401/post404, SSE closure, restart and
historical no-data12-proof/certificate finalization. The media subject remains
PENDING with unproven writer drain; old URL denial uses its real TTL. Inspection
confirms fixture-only mounts, loopback ports and separate projects/keys. All4
projects, named/anonymous volumes and generated keys/storage are removed, with
archives retained durably. Cleanup is not an erasure acknowledgement.

Repaired evidence is separate from the original submission:
`/home/vonhuphi/social-p9aii-repair-evidence.qj4n9n9k/`, including current-source
manifest, targeted/full logs and curated XML counts, image build/identity, all4
native probes/isolation/prefix checksums/archives and explicit cleanup metadata.

Repaired unchanged FeedBenchmarkTool: **3,200/3,200 successes,0 failures**, all16
scenarios, pool10/diagnostic50,5m22s, finished2026-10-24 01:11:46+07. No heavy
build/verify/native app overlapped the benchmark. All percentiles below are ms;
complete outcomes, plans, SQL/connection/heap/CPU/wait samples and CSV/JSON are
retained in the repaired evidence root. The500ms goal remains OPEN; this successful
run does not resolve prior timeout root causes or establish production capacity.

| Pool | Scenario | Success/attempt | p50 | p95 | p99 | SQL/attempt |
|---|---|---|---|---|---|---|
| 10 | first_burst | 500/500 | 1162.288 | 2467.223 | 3142.581 | 15 |
| 10 | post_burst_steady | 500/500 | 967.182 | 2100.173 | 2611.730 | 15 |
| 10 | no_sources | 100/100 | 61.866 | 151.499 | 156.382 | 6 |
| 10 | rare_old | 100/100 | 182.857 | 384.098 | 437.520 | 12 |
| 10 | last_page | 100/100 | 261.490 | 560.660 | 625.194 | 11 |
| 10 | mostly_rejected | 100/100 | 136.153 | 310.829 | 334.768 | 6 |
| 10 | overlap | 100/100 | 934.595 | 1840.344 | 1978.012 | 15 |
| 10 | exhausted | 100/100 | 126.412 | 316.581 | 347.372 | 5 |
| 50 | first_burst | 500/500 | 1238.461 | 2094.718 | 2704.521 | 15 |
| 50 | post_burst_steady | 500/500 | 882.274 | 1388.575 | 1586.185 | 15 |
| 50 | no_sources | 100/100 | 28.483 | 51.499 | 56.654 | 6 |
| 50 | rare_old | 100/100 | 137.947 | 243.186 | 297.808 | 12 |
| 50 | last_page | 100/100 | 232.629 | 418.179 | 463.446 | 11 |
| 50 | mostly_rejected | 100/100 | 115.567 | 260.892 | 278.551 | 6 |
| 50 | overlap | 100/100 | 684.103 | 1266.592 | 1390.801 | 15 |
| 50 | exhausted | 100/100 | 116.167 | 242.002 | 247.785 | 5 |

Measured resource contention remains visible: pool10 peak pending43 (pool50:37); sampled CPU
load reaches1.0 in both pools. Pool50 is diagnostic only. Raw acquisition timings,
DB waits and sampled heap are retained; no pool/timeout/lease/planner change is
part of these fixes. Final delivery scans/config/diff/link checks and real commit
provenance are recorded in the mailbox and external evidence index. Findings
P9AII-R1/R2 stay OPEN for main; development requests review, not acceptance.

## Main review — round2 / G2, 2026-10-24

Main accepts the complete assigned isolated synthetic II package at executable
`8df0b6b`, resolving P9AII-R1 and P9AII-R2 after code/regression review. Suspended
ordinary retention remains effective; format2 preservation exports authoritative
eligibility/history times and recaptures legacy baselines without rewriting old
receipts. No new actionable repair finding was identified. Original findings and
developer reports above remain historical evidence, not current OPEN statuses.

Independent Java21 full verify: **481 PASS (62 fast +419 IT)**, zero failures/
errors/skips, exit0,7m45s, finished2026-10-24 01:24:48+07. All50 current class XML
reports match the log; three stale target reports are excluded. Evidence root:
`/home/vonhuphi/social-p9aii-main-review2.BcBHC4/`. All392 manifest paths match;
V0001–V0051 unchanged, Compose/diff checks and pinned79-commit history scan/
synthetic positive control pass. Native four-path upgrade and3200-request benchmark
remain reviewed developer evidence, including the unavailable native runner exit
status, not independent main reruns or an invented exit0.

G2 is not a release/production or PhaseIII activation. The current development chat
is finished; main/user owns the next assignment. Default-off/isolated synthetic
scope, unknown-writer waits, U01–U06 and the unmet500ms feed goal remain unchanged.
