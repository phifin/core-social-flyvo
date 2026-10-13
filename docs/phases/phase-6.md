# Phase 6 — chronological source-authorized feeds

Status: implemented, **READY_FOR_REVIEW (round 2)**; P56-R1 remains open pending main.
Main independently passed all 248 package tests but reproduced empty HOME source
queries taking ~4.8–4.9s and four concurrent queries timing out at 5s on 1M unrelated
posts. See [main review and stabilization assignment](../agent-handoff.md).
The developer delivery evidence below is retained; latency goal remains unmet.
**Not accepted**. Phase 5's full internal gate passed before feed implementation:
70 targeted/231 full tests at report code `19849c96307d687958ede54df0b4fbd9cbf132a7`.
See [Phase 5 evidence](phase-5.md), [package](../work-packages/phase-5-6.md) and
[mailbox](../agent-handoff.md). No Phase 7 or automatic acceptance/push.

## Delivered behavior

Home unions own personal/follow/friend/joined-community/direct CUSTOM sources,
deduplicated by source-driven SQL UNION before pagination. Unrelated followed-user community
posts and unrelated PUBLIC discovery are excluded. Timeline is personal-only.
Mute suppresses home only; block, current account/audience/member state and source
deletion/removal affect all surfaces. Empty community/timeline scope is authorized
explicitly, not inferred from there being no returned posts.

Content owns scoped candidates, PostgreSQL cutoff and coherent current source/body
hydration. Feed never joins foreign SQL or transports 10k graph IDs into Java.
Concrete chronological ranker, immutable published time/UUID order, encrypted
30-minute viewer/mode/scope/size-bound cursors, last examined continuation and
100-candidate/1,000-examined/200ms refill budget follow [feed](../feed.md).
The measured query-work budget stops further refill, not an in-flight query; the
existing five-second statement timeout is unchanged. One scan always obtains a
progress key. Empty continued pages and weak consistency are explicit.

Attribution/engagement are batched after authorization; optional counts never
authorize and no media URL is embedded. Low-cardinality mode/kind metrics cover
requests, examined/returned, scan ratio, owner-query calls/duration and budget.
No new dependency, published view, feed table/projection/cache/worker or broker.
Round 2 adds V0024–V0026's partial covering personal-author/CUSTOM/community indexes.
V0001–V0023 remain unchanged. Actual migration maximum is V0026.

## Round 2 stabilization — P56-R1

Source-driven HOME queries enumerate current author/community/recipient sources
inside the approved content adapter, not global chronology. Common author facts
are statement-local, and the canonical ELIGIBLE predicate applies before every
source's eligible LIMIT. UNION deduplicates before global chronology/LIMIT. Top K
per source safely supplies global Top K, including overlaps; no pre-privacy LIMIT,
false exhaustion, cursor/timeout changes or authorization cache. Hydration is
unchanged and rechecks current source/owner privacy with the body and relations.

Permanent FeedsIT regressions add no sources on 20k unrelated posts, rare old
source with unrelated newer/older history, last page/exhausted seek, and >1,000
newer private rows before overlapping follow/friend/CUSTOM visible keys. Assert
EXPLAIN ANALYZE source-index physical work rather than only wall-clock thresholds.
Existing races, audience/community parity, cutoffs/cursor and query-count remain.

Initial round 2 source-driven query still timed out on dense first-page warmup:
the plan showed per-author bitmap heap/sort and 194 JIT functions with optimization/
inlining enabled; no successful load claim. Log
`/tmp/social-p56-r2-benchmark-first.log`. Refinement computes common author facts
from source edges/current account/block/mute views once, avoiding duplicate per-post
graph lookups; V0024 covers personal-author keys/audience without body reads. It
does not disable JIT or change server/client timeouts. Final load evidence follows
when verified; the earlier round 1 evidence below remains historical.

The first covering-index attempt passed serial warmup but failed the default-pool
dense burst with 5s connection acquisition timeouts; log
`/tmp/social-p56-r2-benchmark-cover.log`. It is retained as failed evidence. The next
refinement bounds author traversal by the Kth distinct already-eligible own/
community/direct-CUSTOM seed key, when K seed keys exist. Anything below that key
cannot enter global Top K; otherwise the bound is negative infinity. Bounds are
recomputed with current cutoff/seek/source/privacy in the same statement. No JIT,
timeout/pool default, cache or cursor change masks the failed run.

After the environment interruption, the resumed 2026-10-12 seed-bound attempt still
failed pool 10's dense burst. Its measured first warmup EXPLAIN was planning 9.760ms,
execution 374.053ms, 111,295 shared hits; 10,000 addressed post_pkey heap lookups and
273 JIT functions (124.507ms JIT) remained. Log retained locally at
`.local/evidence/p56-r2/benchmark-bound-failed.log`; historical `/tmp` files are no
longer available after that environment reset, not claimed re-run evidence.
V0025's narrow covering CUSTOM ID index and a statement-local current denied-author
set address that measured work without changing source/privacy/cursor/timeouts.
Final measurements and full package checks are recorded below after completion.

The covering-CUSTOM attempt reduced serial warmup to planning 7.308ms/execution
176.716ms but still failed pool 10's dense burst. Its 269 JIT functions consumed
90.992ms; log `.local/evidence/p56-r2/benchmark-custom-failed.log`. The source seed's
proof now reuses the actual candidate row and shared canonical facts builder's
point lookups instead of re-reading that same post and expanding community joins.
Root and current owner facts share one statement snapshot; privacy still precedes
each eligible LIMIT and final coherent hydration is unchanged. This is query-shape
refinement, not disabling JIT or raising the timeout. Final tool/gates run on this
exact source; no failed attempt is presented as completion.

The initial point-proof attempt then exposed a community plan regression: bitmap
heap/sort visited ~90k rows before the seed LIMIT; warmup planning 4.794ms/execution
517.112ms, 76,777 hits and 76.721ms JIT. Pool 10 burst failed; retained log
`.local/evidence/p56-r2/benchmark-point-proof-failed.log`. V0026 covers the required
community author fields and scope/status-proven projections, preserving streamed
chronology. Direct CUSTOM reuses the current addressed-recipient fact inside that
same statement; all other canonical owner checks remain. No applied migration,
timeout, JIT setting or privacy/cursor contract is modified.

The community-covering attempt reached warmup planning 3.910ms/execution 151.620ms,
but pool 10 still starved: estimated cost triggered 235 JIT functions, 72.144ms
compilation despite ~79ms interpreted work. Log
`.local/evidence/p56-r2/benchmark-community-cover-failed.log`. Based on this measured
overhead, the final refinement explicitly turns JIT off LOCAL to the HOME read
transaction, not server/session configuration. This supersedes the earlier
attempts' no-JIT-setting plan, not the frozen privacy/cursor/timeout/cache contracts.
Real PG tests assert restoration after success and injected rollback. EXPLAIN now
uses the actual owner API's settings in its transaction; instrumentation counts
the extra setting SQL. No failed load is labeled successful; final code evidence
is separate below. PostgreSQL documents why compilation can hurt short queries:
[JIT decision](https://www.postgresql.org/docs/18/jit-decision.html),
[LOCAL set_config](https://www.postgresql.org/docs/18/functions-admin.html).

The LOCAL-JIT attempt reduced warmup planning/execution to 4.164/76.369ms,
but still failed pool 10 connection acquisition at 5s; retained log
`.local/evidence/p56-r2/benchmark-local-jit-failed.log`. Its plan made 9,100
account probes before discovering whether each author had any keys above the
eligible seed bound. Current refinement checks active-author facts only on
matching post candidates, still before canonical privacy and eligible LIMIT.
The 48-test focused gate passed; complete benchmark/package results remain pending.

The completed intermediate two-pool run still failed: 2/1,000 dense requests
failed acquisition in pool 10, and 1/1,000 in pool 50 while it grew to 38 active
connections. All 1,200 sparse/overlap/continuation requests succeeded. Log
`.local/evidence/p56-r2/benchmark-both-pools-failed.log`; this is failure evidence,
not a completed package gate. The exhausted fixture now obtains a real cursor
from two posts, then deletes both through the owner API before following it;
a single known-final post correctly returned no cursor in the earlier fixture.
The current query refinement batches author seeks inside PostgreSQL when a safe
eligible seed bound exists, retaining per-author Top K otherwise. The full
101-test targeted package gate passes. Benchmark instrumentation now counts
both prepared and plain JDBC Statement executions, including cutoff/JIT SQL;
the intermediate runs' prepared-only counts underreported those plain statements.

The author-batched attempt completed both pools and all scenarios but retained
one pool-10 acquisition timeout (3,199/3,200 successful requests). Warmup planning/
execution was 5.152/59.679ms with 41,540 shared hits. Log
`.local/evidence/p56-r2/benchmark-batched-authors-failed.log`. The current refinement
also batches the 10,000 addressed CUSTOM ID seeks in PostgreSQL, with an explicit
empty-source guard. No graph/recipient array reaches Java, no raw/private LIMIT
or timeout/cache change is introduced. Its 101-test targeted package gate passes;
the load outcome is recorded separately below when complete.

That recipient-batched run still failed two pool-10 first-burst acquisitions;
steady pool 10, both pool-50 bursts and all sparse scenarios succeeded. Log
`.local/evidence/p56-r2/benchmark-batched-recipients-failed.log`. On 2026-10-13 the
same pre-window source passed full verify: **256 tests**, zero failures/errors/skips;
`.local/evidence/p56-r2/verify-before-window.log`. These are intermediate results.
The next measured refinement adds an optional dense-source interval probe capped
at 1,001 chronology keys. Only a complete <=1,000-key interval may supply author
candidates; overflow discards it and uses per-author indexed source seeks. Current
privacy still precedes every eligible Top K and no truncated prefix means exhaustion.
No source/eligible seed or <=1,000 author sources means no chronology probe.
A new regression covers both branches and author deactivation. It caught an
array-seek fallback visiting 2,251 chronology keys; that fallback was replaced by
parameterized per-author seeks, retaining the exact 1,001-work overflow assertion.
There is no additional migration/index or change to the public cursor/work budgets.

## Final round 2 package verification — 2026-10-13

**102 targeted PASS** (23 fast +79 real PG: 22 interactions, 22 reports,
18 feeds, 17 foundation), zero failures/errors/skips; local
`.local/evidence/p56-r2/package-targeted.log`. Final command uses Java 21
`./mvnw -B -ntp test`
`-Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest,ReportTest,CommentTest,PostPolicyTest`
then `failsafe:integration-test failsafe:verify`
`-Dit.test=ReportsIT,InteractionsIT,FeedsIT,FoundationIT`.

Final Java 21 `./mvnw -B -ntp verify`: **257 PASS**, 47 fast +210 integration,
zero failures/errors/skips; `.local/evidence/p56-r2/verify.log`. Real privacy,
current-source hydration, report removal, SQL/object parity, cursor/refill and
named concurrency/revocation tests remain. New regressions cover empty/rare/last/
exhausted source work, >1,000 private candidates/overlap, all interleaved sources,
complete versus overflowing dense work probe/current inactive author, and LOCAL
JIT restoration after success/rollback. Fresh V0026 and V0023/24/25 upgrades retain
all prior applied checksums and existing post data; V0001–V0023 are unchanged.
All Phase4IT/storage/decoder/Compose secret and previous module tests are included.
Formatting applied before the benchmark; full formatting, Enforcer/convergence,
architecture/SQL boundary and OpenAPI pass without exclusions. App/media Compose,
local documentation links and whitespace pass. Pinned redacted 40-commit baseline
history scan and external synthetic positive control pass; default detection and
the two exact historical fingerprints are unchanged. Final staged/code/handoff
scans and actual hash are in the mailbox. No required gate is omitted; no remote
CI/provider/production smoke, push or deployment is claimed.

## Final round 2 benchmark evidence — 2026-10-13

Final source is identified by the real stabilization commit in the
[mailbox](../agent-handoff.md), with local SHA-256 manifest
`.local/evidence/p56-r2/source-sha256.txt`. Logs are in the ignored, mode-700
`.local/evidence/p56-r2/` directory; committed measurements below remain reviewable
without those local artifacts. No executable changes follow this benchmark.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify \
  -Dit.test=FeedBenchmarkTool -Dfeed.benchmark=true
```

**PASS: 14 fast prerequisites +1 heavy tool, zero failures/errors/skips.**
Log `.local/evidence/p56-r2/benchmark.log`. Both pools completed **3,200/3,200
successful measured requests**: 2,000 dense requests (50 distinct readers ×10
iterations ×2 bursts ×2 pools) and 1,200 scenario requests (50 threads ×2 calls
for the same scenario viewer ×6 scenarios ×2 pools). Each dense/overlap response
contains 20 distinct authorized posts; sparse counts and actual encrypted last/
exhausted continuations are asserted, not inferred from timings. Production pool
starts at 10; 50 is explicitly benchmark-only. No timeout increases or caching.

Fixture: 1,000,000 initially published posts, 20k authors, 10k follows/4k blocks/
1k mutes per dense reader, 100 additional friends, skewed 100-community history
and 500k recipient rows. Sparse scenarios add three old posts plus two posts per
pool deleted through the owner API after obtaining a valid cursor: 1,000,007
post rows at completion, including four deleted ones. Pinned PG18.6 image is the
same digest as round 1. Shared i7-1165G7, four cores/eight logical CPUs,
7,848,008kB RAM; JVM max 2,011,168,768 bytes. cgroup CPU/memory limits unavailable.
Seed 198.967s; database 975,787,711 bytes before sparse additions. This is shared
local hardware and a synthetic fixture, not a production capacity/SLA claim.

| Pool/burst | Requests | p50 /p95 /p99 ms | req/s | SQL/request | Mean acquire /candidate execute /all SQL execute ms |
|---|---:|---|---:|---:|---|
| 10 first | 500 | 427.843 /2312.281 /3158.292 | 55.520 | 12 | 599.893 /128.720 /165.476 |
| 10 steady | 500 | 201.394 /2225.788 /3287.783 | 60.332 | 12 | 521.472 /121.140 /152.484 |
| 50 first | 500 | 983.764 /1713.901 /2446.162 | 45.727 | 12 | 107.535 /723.356 /890.288 |
| 50 steady | 500 | 814.555 /1146.091 /1317.305 | 58.987 | 12 | 0.174 /666.500 /784.033 |

App examined/returned ratio **1.000** in each dense burst (10,000/10,000).
JDBC counts include prepared and plain Statement calls (including cutoff/JIT),
not driver's implicit wire-level BEGIN/COMMIT. Acquire includes pool waits;
JDBC execute includes server planning/execution/wire time. **Initial dense p95
<500ms goal remains unmet in all four bursts.** Pool 10 waits and pool 50 shared
execution contention remain material limitations. Successful request checks do
not erase earlier failures or establish reliable production headroom.

| Scenario (100 requests/pool) | Expected result | Post-node visits | SQL/request | p95 pool 10 /50 ms |
|---|---|---:|---:|---|
| No sources | 0, null cursor | 0 | 4 | 66.929 /33.264 |
| Rare old source | 3, null cursor | 3 | 9 | 237.012 /178.717 |
| Last page | 1, null cursor | 1 | 8 | 360.020 /372.195 |
| Mostly blocked/muted/private | 0, null cursor | 4,900 | 4 | 288.197 /191.320 |
| Overlapping dense sources | 20 distinct | 11,598 | 12 | 1436.604 /1136.020 |
| Exhausted valid continuation | 0, null cursor | 0 | 3 | 180.134 /202.147 |

Full actual EXPLAIN ANALYZE/BUFFERS JSON is logged for each case/pool. Post-node
visits sum `(Actual Rows + Rows Removed by Filter) × Actual Loops`, preserving
fractional per-loop averages before rounding the final sum. They count node
visits, not unique posts or disk reads; PostgreSQL's displayed averages are rounded.
No-source/rare/last/exhausted execute **zero chronology probes**, independent of
the unrelated million-row history. Pool 10 planning/execution/shared hits/reads:
no sources 4.682/0.916ms/18/0; rare 5.192/1.182ms/43/0; last
5.391/1.344ms/37/0; exhausted 6.798/1.209ms/18/0. Corresponding pool 50 execution
0.952/1.095/1.299/1.164ms, same buffers/work. Mostly rejected source work is
4,900 related private rows, execution 9.819/10.302ms and 15,111 hits/0 reads.

Final dense candidate planning/execution: pool 10 5.208/54.894ms,
34,173 hits/36 reads; pool 50 7.262/55.874ms, 34,177 hits/36 reads.
This fixture's optional probe **overflows at exactly 1,001 keys and uses the
per-author fallback**; the complete-window fast path is exercised by the permanent
regression, not claimed as the cause of these dense timings. Covering post nodes
visit ~142 community, 10,000 addressed CUSTOM and 455 personal-author rows, plus
the capped probe. No JIT section is present, matching actual owner LOCAL settings.
Hydration remains bounded to 100 IDs: pool 10 4.595/5.527ms planning/execution,
pool 50 2.945/3.484ms, 2,545 hits/0 reads. Community/timeline single probes returned
20 each in 46.953/18.638ms; these are not 50-reader capacity measurements.
The preceding successful pre-counter-fix run is retained at
`.local/evidence/p56-r2/benchmark-before-counter-fix.log`; final numbers above
come only from the current source, after the counter correction.

## Historical round 1 targeted verification

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify -Dit.test=FeedsIT
```

Initial feed gate: **25 pass**, zero failures/errors/skips: 14 fast +11 FeedsIT
against pinned PostgreSQL 18.6. `/tmp/social-p56-feeds-targeted.log`.
Final package targeted gate (also includes ReportTest/CommentTest/PostPolicyTest
and ReportsIT/InteractionsIT/FoundationIT): **93 pass**, 23 fast +70 real PG,
including **12 FeedsIT**, zero failures/errors/skips;
`/tmp/social-p56-package-targeted.log`. Same command as above with those test names.

FeedsIT proves source union/dedup/no unrelated community spill/personal timeline,
mute scope, current blocks/inactive author, empty private/public scope, membership
departure/suspension/archive, all personal audiences and PUBLIC/PRIVATE community
outsider/member/left/suspended/banned candidate/hydrate/object parity,
equal-time 63-row traversal with unexamined tails, cursor tamper/viewer/scope/size/
expiry, after-cutoff exclusion, old newly eligible sources and no edit chronology
bump. Statement barriers revoke unfollow/block/mute/membership/deletion/audience
before real hydration (also independent unfriend, block both directions, inactive
author and report-driven source removal), and narrow CUSTOM then edit after source statement completion
without leaking a newer body. Real 1,001-candidate high rejection produces progressing
empty pages to exhaustion. JDBC execution instrumentation (including Hibernate)
proves statement count independent of 1 versus 50 results and no full eligible-set
sort before candidate LIMIT. Batched enrichment,
low-cardinality metric tags and current HTTP session denial are verified.

FeedsTest is orchestration-only: deterministic monotonic time tests enforce the
1,000-candidate and 200ms budgets, correct examined continuation and fail-closed
nonadvancing order. Real policy/crypto/SQL are not replaced by those mocks.

## Historical round 1 benchmark — completed, latency goal not met

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp test \
  -Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify \
  -Dit.test=FeedBenchmarkTool -Dfeed.benchmark=true
```

FeedBenchmarkTool is intentionally not named *Test/*IT and is outside normal verify;
explicit property acknowledgement is required (failure, not silent skip). It owns a
fresh pinned PostgreSQL container, seeds no production/Compose database and retains
real migrations, constraints/triggers and five-second statement timeout. Dataset:
1M posts, 20k synthetic authors, 50 readers each with 10k follows/4k blocks/1k mutes,
100 communities with 280k hot-community posts, current member/author/account state,
CUSTOM sources, overlapping sources and friendship outside follows. Only generated
synthetic text/UUIDs, no real PII/secrets. Graph follows do not contradict block cleanup.

Run measures actual application feed owner/SQL path (not HTTP/JWT/network latency),
50 concurrent readers ×10 requests per burst, two consecutive bursts after an
initial 50-reader sequential warmup, p50/p95/p99, throughput, executed
JDBC statements including JPA, examined/returned ratio and actual parameterized
candidate/hydration EXPLAIN ANALYZE BUFFERS. Benchmark-only Hikari pool 50, not a
production default change. Hardware: local x86_64 i7-1165G7 @2.80GHz, 8 logical CPUs,
Docker reports 7,664 MiB; JVM max 2,011,168,768 bytes (~1.87 GiB). Host MemTotal
7,848,000 KiB and MemAvailable 1,643,640 KiB at measurement; missing cgroup CPU/
memory limits are reported as unavailable, not fabricated. PostgreSQL 18.6,
database 865,089,215 bytes, seed 266.327s. No timeout/constraint relaxation.

Final run on 2026-10-11: **1 heavy test PASS**, no failure/error/skip, all **1,000
measured requests return 20 authorized items**. Fast prerequisite tests: 14 PASS.

| Scenario, 500 requests /50 readers each | req/s | p50 ms | p95 ms | p99 ms | SQL/request | p95 <500ms |
|---|---:|---:|---:|---:|---:|---|
| first_burst, after sequential warmup | 74.766 | 386.772 | 1723.410 | 2564.168 | 10.000 | **No** |
| post_burst_steady, after first 500 requests | 101.817 | 393.154 | 821.781 | 1216.902 | 10.000 | **No** |

Each burst examines/returns 10,000/10,000 application candidates/items, scan ratio
1.000; that counts authorized SQL keys, not underlying rows rejected inside SQL.
The measured source plan visits **295 chronology index rows for 100 eligible
prefetched keys**, raw ratio 2.95. Feed only examines 20 of those keys per request;
unexamined tail is preserved. Single current COMMUNITY/TIMELINE probes both return
20, at 90.879/43.999ms; they are not separate 50-reader capacity measurements.
The timeline fixture is author 6003 (personal FOLLOWERS posts), not an empty
community-only author. There are 19 SQL shapes after load/probes.

Actual bound-query EXPLAIN ANALYZE BUFFERS after load (full JSON printed by tool):

- Candidate: planning 4.227ms/execution 12.347ms, Limit → Nested Loop → Index Only
  Scan post_chronology; 295 scanned/100 returned, 295 heap fetches, 5,053 shared
  hits/0 reads at root. Owner proof uses post_pkey plus indexed active-account,
  follow/friend/block/mute/recipient facts; no full eligible-set sort.
- Hydration: planning 3.279ms/execution 7.551ms, 100 post_pkey root rows plus a
  100-ID bounded facts subquery, memoized community/member point lookups and
  separate bounded relation aggregates; 2,545 shared hits/0 reads. No global post
  scan or cross-module private-table join. Tiny attachment/mention aggregate sorts
  are not a chronology full-sort.

An earlier completed single-burst run returned all 500 requests, 104.945 req/s,
p50/p95/p99 285.967/1156.072/1737.053ms, 10 SQL/request and scan ratio 1.000
(`/tmp/social-p56-feed-benchmark-first-completed.log`). Its p95 also missed; it is
not replaced by a claim that later warmup meets the goal. Shared-host variation
and warmup effects are not established as the sole cause of the gap.

Initial tooling attempts: one oversized follows seed exceeded the existing 5s
statement timeout, corrected to bounded reader/batches without relaxing timeouts.
A complete 1M-post seed (~236s) then reached an unavailable host cgroup metadata
path before load measurement; metadata collection now handles unavailable files.
Initial candidate-query attempts also hit 5s: EXPLAIN showed a full eligible-set
sort even after point lookups/ordered root, with ~140k eligible rows under Sort.
The final candidate-ID LATERAL/OFFSET 0 owner-proof barrier preserves the ordered
stream with shared FACTS/ELIGIBLE, no pre-visibility LIMIT or authorization shortcut.
A live read-only plan probe guided that refinement; FeedsIT now rejects a full-sort
before LIMIT. None of those failed attempts is claimed as successful evidence.
Final log:
`/tmp/social-p56-feed-benchmark.log`.

## Historical round 1 final package checks and limitations

Final Java 21 `./mvnw -B -ntp verify`: **248 PASS** (47 fast +201 integration),
zero failures/errors/skips; `/tmp/social-p56-package-verify.log`. Includes all 33
Phase4IT, 12 real pinned Silo StorageContractIT, Compose secret isolation, packaged
decoder and all prior identity/community/graph, migration and interaction/report
regressions. Spotless, Enforcer/convergence, architecture, SQL boundary and OpenAPI
PASS. Heavy benchmark is separate, not silently skipped by normal verify.

Exact final targeted and operational commands:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest,ReportTest,CommentTest,PostPolicyTest \
  failsafe:integration-test failsafe:verify -Dit.test=ReportsIT,InteractionsIT,FeedsIT,FoundationIT
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp verify
docker compose --profile app --profile media config --quiet
bash ops/ci/secret-scan-regression.sh
```

Targeted 93 PASS as above. Compose/151 changed-doc local links/whitespace PASS;
pinned Gitleaks 8.28.0 redacted history through report code: 37 commits/no leaks,
only two accepted exact fingerprints; external generic-api-key positive control
PASS (expected detector exit 1, wrapper exit 0, redacted/outside git/cleaned).
Final staged/new-history/delivery hashes are recorded in the mailbox after code
and separate handoff commits. No default rule/exclusion change or push.

**Performance target remains unmet**, not
an omitted benchmark. Before a production SLA, profile statement planning/JVM/PG
waits and revalidate tuned owner SQL, production pool (default 10) and target hardware;
the current shared-laptop/test-pool result is not production capacity. No speculative
cache/fan-out/dependency is added to hide the gap. The package explicitly requires
recording whether the initial goal is met, not asserting it as a successful SLA.
Pagination
is a database cutoff traversal, not a snapshot; new older eligible sources may join,
revocation cannot erase already authorized old responses/downloaded bytes. All
accepted Phase 4 storage/provider/retention/decoder/public-launch gates remain.
