# Phase 6 — chronological source-authorized feeds

Status: implemented and package checks complete, READY_FOR_REVIEW; latency goal unmet.
**Not accepted**. Phase 5's full internal gate passed before feed implementation:
70 targeted/231 full tests at report code `19849c96307d687958ede54df0b4fbd9cbf132a7`.
See [Phase 5 evidence](phase-5.md), [package](../work-packages/phase-5-6.md) and
[mailbox](../agent-handoff.md). No Phase 7 or automatic acceptance/push.

## Delivered behavior

Home unions own personal/follow/friend/joined-community/direct CUSTOM sources,
deduplicated by SQL EXISTS before pagination. Unrelated followed-user community
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
Measured query-shape refinement reuses existing indexes; no V0024 is allocated.
Actual migration maximum remains V0023.

## Targeted verification

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

## Representative benchmark — completed, latency goal not met

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

## Final package checks and limitations

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
