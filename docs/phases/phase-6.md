# Phase 6 — chronological source-authorized feeds

Status: **ACCEPTED by main, 2026-10-14**, code `32c1b1e`; P56-R1/R2 resolved.
Independent verify: 260 PASS; benchmark: 16 fast +1 heavy PASS, all 3,200 requests
on pools10/50 succeed. Dense p95 pool10 first/steady 2715.899/2267.505ms, pool50
2316.014/1717.306ms; initial <500ms goal still unmet. Sparse physical post rows
no-source/rare/last/exhausted remain 0/3/1/0. No production capacity/SLA claim.
Complete main evidence and preserved failures are in the
[accepted review archive](../handoffs/phase-5-6-interactions-reports-feeds.md).

Historical developer handoff: implemented, READY_FOR_REVIEW (round 3). P56-R1 resolved
by main; P56-R2 awaits main's disposition. Round 3 passes 105 targeted/260 full
tests and two consecutive final-source benchmarks, each 3,200/3,200 requests.
Main's earlier independent 3,199/3,200 failure and the diagnostic baseline failure
are retained below and in the [mailbox](../agent-handoff.md), not relabeled passes.
The initial dense latency goal remains unmet.
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

## Round 3 reliability — P56-R2

Review baseline `6b90a10`, executable baseline `21f7a5c`. Main's failed
3,199/3,200 run and R1 resolution are preserved in the mailbox. Local logs live in
ignored mode-700 `.local/evidence/p56-r3/`; main's available failed log is copied
there as `main-round2-benchmark.log`. No gate is self-waived.

Safe test-only diagnostics were first run with production SQL/transaction/config
unchanged (SHA-256 evidence `baseline-production.sha256`). That baseline **FAILS**:
4/500 pool-10 first-burst acquisitions fail, overall 3,196/3,200 successes; every
other burst/scenario succeeds. `baseline-diagnostics.log` retains all outcomes.
The cause chain is CannotCreateTransactionException → JDBCConnectionException →
SQLTransientConnectionException, null SQLState/vendor0, with the failure captured
at DataSource acquisition before application SQL. Failed acquisitions take
5,003.067ms mean (max 5,010.935ms), request elapsed 5,001.749–5,082.682ms.
Failure-time pool: active10/idle0/total10/pending33–39. This establishes pool
acquisition timeout, not a PostgreSQL statement timeout or a leaked connection.
All 496 successfully acquired first-burst leases are returned; mean held
216.062ms, max 417.723ms. At the first failure 220 leases had already been acquired
and 210 returned while the failing request was still on its first attempt.
Repeated full-page occupancy starves some waiters beyond 5s even though no one
transaction lasts 5s. About 13.366ms/lease is outside explicit JDBC execution,
including conversion/transaction machinery; it is not labeled pure CPU time.

At failures CPU load is ~0.97–0.99 on the shared host; recent DB samples show
8–9 active/runnable backends plus 1–2 idle-in-transaction/ClientRead, oldest
transaction ~206–313ms. Heap ~66–95MB and cumulative GC ~281–313ms do not show
a five-second JVM pause or OOM in these observations. No diagnostic DB samples
were unavailable. Host contention contributes; it is not used to waive the gate.

The fix removes the feed-owned outer page transaction; existing owner APIs own
short read-only transactions and release connections before orchestration/cursor
crypto. R1 SQL, all V0001–V0026, datasource/pool/timeouts, dependencies, API/cursor
and current body/source policy remain unchanged. Coherence remains per source/body
statement under READ COMMITTED, as before, not a frozen page. No retry/cache/
fan-out/serialization is introduced. A real-PG regression **fails on the old
transaction** at cursor encryption (`regression-before-fix.log`, active transaction
true) and passes after the fix, while asserting candidate/body SQL is transactional
and read-only and actual encrypted continuation has no missing/duplicate item.
EXPLAIN setup now invokes the actual matching candidate or hydrate owner API,
avoiding an inherited candidate LOCAL setting when measuring separate hydration.

Diagnostics are bounded metadata only: cause types/SQLState/vendor code, pool/
acquisition/lease counters and failed duration, JVM/host resource values and
PostgreSQL wait aggregates. Message/SQL bindings/credentials/content are omitted;
unit tests verify JSON redaction and cycle/length bounds. One independent observer
connection reads no business rows/SQL text and does not supply application slots.
Sampling every 200ms is part of both baseline/final measurements; SQL counts omit
that observer and driver implicit transaction commands. Means use all attempts;
percentiles explicitly use successful requests, errors are retained separately.

Current final-source gates: **105 targeted PASS** (25 fast +80 PG: 22 interactions,
22 reports, 19 feeds, 17 foundation) and **260 full verify PASS** (49 fast +211
integration), zero failures/errors/skips. Logs `package-targeted.log`/`verify.log`;
formatting, architecture/SQL allowlist, OpenAPI and all previous privacy/concurrency/
storage/decoder/migration cases pass.

### Final-source repeatability and delivery gates

Measurements on 2026-10-13, delivery on 2026-10-14. Two consecutive complete tool
runs **PASS**, each 16 fast prerequisites +1 heavy test, no failures/errors/skips;
each records **3,200/3,200 successes, zero request errors**. Logs
`benchmark-final-1.log` / `benchmark-final-2.log`, finishing at 20:06:27 /20:11:12
+07:00. Both runs execute pool10 then pool50, 50 concurrent readers, two dense
500-request bursts and six 100-request sparse scenarios per pool. No retries,
reduced load, concurrent Maven checks or intervening executable/config changes.
`final-source.sha256`, `source-check-between.log` and `source-check-after.log`
bind production feed/source/config plus all four changed/new test files to the
same final bytes; the mailbox records the real verified code commit.

Reproduce with Java21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, that JDK's
`bin` first on PATH), Docker available, no concurrent Maven workload:

```bash
./mvnw -B -ntp spotless:apply test \
  -Dtest=FeedBenchmarkDiagnosticsTest,FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest,ReportTest,CommentTest,PostPolicyTest \
  failsafe:integration-test failsafe:verify -Dit.test=ReportsIT,InteractionsIT,FeedsIT,FoundationIT
./mvnw -B -ntp verify
# Run this complete command twice consecutively without modifying source/config:
./mvnw -B -ntp test \
  -Dtest=FeedBenchmarkDiagnosticsTest,FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify -Dit.test=FeedBenchmarkTool -Dfeed.benchmark=true
```

Same shared i7-1165G7 four-core/eight-thread host, 7,848,008kB physical RAM,
JVM max 2,011,168,768 bytes, pinned PostgreSQL 18.6, fresh isolated million-post/
10k-follow skewed fixture per run. Cgroup CPU/memory limits were unavailable;
seed time 201.071 /206.867s, MemAvailable ~2.29 /2.67GB. Host CPU peaks ~0.99–1.00
in dense bursts. Pool10 remains the default, pool50 the explicitly labeled
comparison, each with one additional metadata-only observer connection. Original
5s acquisition/statement limits and all application settings remain unchanged.

Dense latency in ms (success-only; all 500 attempts succeeded in every row):

| Run /pool /burst | p50 | p95 | p99 | Success/s | Acquire /candidate execute ms per attempt |
|---|---|---|---|---|---|
| 1 /10 /first | 1064.155 | 2018.699 | 2694.553 | 42.219 | 856.080 /160.401 |
| 1 /10 /steady | 817.831 | 1753.182 | 2220.474 | 51.219 | 698.741 /137.400 |
| 1 /50 /first | 1082.009 | 2042.176 | 2467.590 | 41.291 | 115.224 /751.392 |
| 1 /50 /steady | 884.869 | 1510.017 | 1783.557 | 51.788 | 0.367 /716.670 |
| 2 /10 /first | 947.973 | 2044.213 | 2421.286 | 44.759 | 811.483 /149.772 |
| 2 /10 /steady | 839.050 | 1673.081 | 2188.254 | 51.002 | 694.420 /140.017 |
| 2 /50 /first | 1119.559 | 1903.670 | 2112.712 | 41.332 | 117.845 /772.861 |
| 2 /50 /steady | 777.742 | 1609.878 | 1851.527 | 52.669 | 0.383 /668.649 |

Dense SQL is **15 statements/attempt**, 5 acquisitions/attempt, 10,000 candidates
examined /10,000 items returned per burst (20/20 per request, metric ratio 1).
Examined counts consumed candidates, not every prefetched key or physical SQL row;
the bounded candidate/body batch still holds up to 100. Source SQL is unchanged;
separate owner transactions no longer share the page-wide persistence context.
The measured statement count includes owner/enrichment/setup queries and is not
claimed lower than round2. Driver implicit BEGIN/COMMIT and observer SQL are
excluded from statement counts, not from elapsed
time. All dense counters acquired=returned=2,500 per burst, acquisitionFailures=0.
Pool10 first/steady mean lease hold is 45.954 /38.046ms in run1 and 43.751 /38.852ms
in run2, versus baseline 216.062 /184.227ms. These are **per lease**, not per
request; acquisition count increases from one page lease to five owner leases.
Maximum pool10 acquisition wait is 1,574.574 /1,378.049ms (run1 first/steady),
1,658.211 /1,459.666ms (run2), below the unchanged 5s bound. Peak pending remains
43 /40, so contention is reduced, not eliminated. Pool50 shifts contention to DB
execution (CPU/runnable and BufferMapping waits); its larger pool is not the fix.

All sparse scenarios return **100/100** per pool/run, zero failures. Success-only
p95 ms and SQL statements per attempt:

| Scenario | Run1 pool10 /50 | Run2 pool10 /50 | SQL/attempt |
|---|---|---|---|
| no_sources | 227.481 /86.451 | 263.028 /81.387 | 6 |
| rare_old | 432.638 /238.865 | 470.607 /255.080 | 12 |
| last_page | 500.866 /394.967 | 514.710 /431.247 | 11 |
| mostly_rejected | 254.871 /129.782 | 273.532 /125.522 | 6 |
| overlap | 1528.155 /1419.634 | 1592.547 /1520.010 | 15 |
| exhausted | 241.092 /225.762 | 261.702 /242.949 | 5 |

R1 physical post-node work remains **0 /3 /1 /0** for no_sources /rare_old /
last_page /exhausted on both pools in both runs; corresponding shared hit/read
buffers are **18/0, 43/0, 37/0, 18/0**. Their EXPLAIN execution ranges
0.937–2.492ms (planning 3.891–8.017ms). Mostly-rejected work 4,900 and overlap
11,598 remain source-local work, not hidden by pre-privacy LIMIT. Candidate/body
plans and all buffers are retained in both logs. Dense candidate execution
58.094–77.705ms and hydrate 3.436–4.628ms are serial EXPLAIN observations, not
50-reader request latency. Both runs' captured plans have no JIT compilation;
the original transaction-local candidate setting and restoration regression remain.
Every diagnostic phase reports zero unavailable DB samples and no unreturned
leases/acquisition errors. Sampling can miss brief waits and is not a complete trace.

App/media Compose config, changed-doc local links and whitespace pass. Pinned
default redacted Gitleaks scans 43 baseline commits with no leaks; external
synthetic positive control passes, only the two accepted historical fingerprints
remain. Staged/final history scans and actual commit routing are in the mailbox.
No source/API/schema/dependency/permission or migration change beyond the read
transaction boundary; no production diagnostics endpoint or launch-capacity claim.
**Dense p95 <500ms is still unmet in all eight bursts** (and pool10 last-page /
overlap can exceed 500ms). All-success reliability now passes twice, but this
synthetic shared-host evidence is not an SLA or phase acceptance. Failed baseline,
negative regression and main's failed run remain available alongside both passes.

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
