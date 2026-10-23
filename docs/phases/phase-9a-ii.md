# Phase 9A-II — sources, media and strict completion

Candidate under development/main review, isolated synthetic policy only. Baseline
`6162303`, accepted I executable `bbbde93`, G1 review `faf0ad8`. The fixed
[mailbox](../agent-handoff.md) supplies the actual final code hash and review state;
development does not self-accept G2. [Assignment](../work-packages/phase-9a-ii.md),
[pre-code map](../work-packages/phase-9a-ii-implementation-map.md) and
[ADR-017 C01–C05](../adr/017-account-erasure-and-recovery.md) bind this delivery.
No agents, push, III, real erasure/restore, provider contact or deployment is used.

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

Main reviews the complete package at G2; only main can accept it or push.
