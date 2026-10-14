# Feed design and evolution

Phase 6 was accepted by main on 2026-10-14 after the full Phase 5 internal
gate and independent final review. Source API/HTTP, privacy/cursor/refill tests and completed
isolated representative benchmark are in [Phase 6 evidence](phases/phase-6.md).
The measured 50-reader p95 target <500ms is **not met** on this recorded environment.
Phase acceptance does not imply production capacity.

`feed` is a first-class query module. It owns `GetHomeFeed`, `GetCommunityFeed`, `GetUserTimeline`, candidate orchestration, ranking and cursor encoding. Content owns visibility and hydration. A feed candidate is a post ID, immutable published timestamp and optional scoring facts; it is never a pre-authorized post body.

## Initial read-time implementation (Phase 6)

Home candidates are the UNION of the viewer's personal posts, personal posts from followed users, personal posts from friends, posts in active joined communities, and personal CUSTOM posts explicitly addressed to the viewer. A followed user's community post is included only through a joined community source; do not spill unrelated public community activity into home. Following/friendship are independent sources and IDs are deduplicated before ranking. PUBLIC posts from unrelated people are not home discovery by default. A user's timeline includes personal posts only; community activity is a separate explicit future filter, avoiding accidental disclosure of memberships.

Community feed considers posts in one community after checking scope. User timeline considers one author's personal posts. Mute applies to home and social notifications, not a deliberately visited timeline/community. All surfaces still enforce block, account state, current audience, removal and deletion. Own ONLY_ME posts appear only to their author.

`feed.application` passes viewer, scope, filter, cursor and bounded scan limit to `ContentQueries.scanCandidates`. Content's SQL adapter generates candidates with UNION/EXISTS and the published authoritative views. It applies eligibility/privacy predicates in SQL, returning lightweight authorized candidate facts. Feed applies `ChronologicalRanker` (concrete first implementation), then calls `ContentQueries.hydrateAuthorized` for current source validation. Profile summaries and media descriptors are batched after eligibility; no lazy JPA graph serialization. SQL policy and object policy must agree in generated scenario tests.

Chronological descending ordering is `(published_at,id)`. First page records `asOf=database time`; subsequent queries add `published_at <= asOf` and tuple `< (lastScannedTime,lastScannedId)`. Cursor is signed, viewer/scope/filter-bound and expires after 30 minutes. Return the **last candidate actually examined**, including rejected rows, as next position; never use the end of a prefetched but unexamined batch. Dedup before advancing. Published time never changes on edit. New follows/memberships may add older eligible candidates between pages; this is a bounded-time traversal, not a frozen database snapshot. Deleted/unfollowed content disappears immediately on later pages.

Scan in batches of 100, at most 1,000 examined candidates or 200 ms of query work per request initially. Fill up to requested 20/default, 50/max. If filtering/execution budget exhausts before candidate range does, return a short/empty page with continuation cursor. Do not use LIMIT before visibility and assume a full page, or return null cursor after scanning only one batch. Capture examined/returned ratio and query duration. Guard against cursor loops and unstable equal timestamps.

Use SQL EXISTS to exploit pair indexes; avoid loading the full social graph into application memory. Profile/engagement enrichment must use batch APIs. Query plans should be measured on 1 million posts, 10,000 follows for a test user, skewed communities and a high block/filter ratio. These are load-test fixtures, not claimed production capacity. Set measured latency goals with the deployment hardware before launch; initially target p95 feed latency under 500 ms at 50 concurrent active readers on the recorded test environment.

Counts are optional decoration. Do not delay authorization for an unavailable ranking/enrichment provider. Fail required source authorization closed; fall back to chronological ranking if optional ranker fails. Return no filtered total. A database-backed source-only feed is complete without Redis.

## Implemented Phase 6 refinements

`FeedQueries` provides home/community/personal timeline. `ContentQueries` provides
current scope authorization (also for empty feeds), database-time cutoff, lightweight
scans and scoped authorized hydration. Only the sanctioned content SQL adapter reads
account/graph/community views. HOME uses indexed author/community/recipient branches,
deduplicated before pagination without a Java graph ID list; ordinary
content policy and mute are applied in the same candidate SQL. Follow/friend sources
are personal-only; joined effective communities and direct CUSTOM are separate.

Hydration rechecks both current source eligibility (unfollow/membership/mute) and
ordinary privacy with the body/recipients/attachments/mentions in one statement.
Later batched owner checks can only remove a selected old response, never reload
newer text using an old grant. Profile attribution and engagement are batched after
eligibility. Media descriptors are not capabilities. Engagement is optional/null
if its source becomes inaccessible after the coherent body statement.

Round 3 read-resource refinement: feed orchestration does not start a page-wide
transaction. Each content/engagement owner API retains its short read transaction;
sorting, cursor crypto and metrics do not retain that transaction's connection.
Coherent source/body authorization is the same single-statement snapshot, not a
page-wide or cross-page snapshot. Caller-owned transactions still follow the owner
APIs' normal REQUIRED semantics. Command transactions/guard order are unchanged.
This reduces repeated whole-page pool occupancy without changing source SQL,
cursor/refill/privacy, pool sizes/timeouts, authorization caching or API contracts.

Cursors use existing authenticated encryption (integrity plus concealment), binding
viewer/mode/scope/requested size. Cutoff is PostgreSQL clock_timestamp(), expiry is
fixed at first-page time +30 minutes and never renewed during traversal. The concrete
ChronologicalRanker sorts timestamp and PostgreSQL-compatible UUID order descending;
no speculative scoring interface/provider is added. Scan batches/refill and maximum
examined count are the documented 100/1,000. The 200ms measured owner-query-work budget
stops further refill; it is not cancellation of an in-flight SQL/hydration call.
Existing five-second DB timeout remains. Always permit one scan to obtain a progress
position; no zero-progress cursor is issued. Unknown/nonadvancing source order fails
closed, not an infinite loop. Short/empty pages continue at last examined, including
rejects but excluding unexamined prefetched tail.

Low-cardinality `feed.requests`, candidates.examined, items.returned, scan.ratio,
query.calls, query.duration, request.duration and budget.exhausted use mode, and kind
where necessary, never viewer/community/post IDs. query.calls counts owner query API
calls; the benchmark separately instruments executed JDBC statements (including JPA)
for actual SQL count and records actual parameterized candidate/hydration EXPLAIN.
No hidden totals, authorization cache, feed table, Redis, fan-out or Phase 7 consumer.

Feed SQL retains the common owner FACTS/ELIGIBLE predicate. HOME does not traverse
unrelated global history without a work bound (P56-R1). Current sources
are generated inside SQL from the published views; graph facts are computed once
per author in a statement-local CTE, not cached between reads. Current author
account activity is checked on matching post candidates before the eligible LIMIT,
avoiding account probes for sources with no keys in the requested range. Personal branches
seek by author, community branches by effective joined community, direct CUSTOM
by recipient → covering CUSTOM post ID index. Cutoff/seek apply inside each branch. Canonical
owner privacy applies before each branch's eligible LIMIT; then UNION deduplicates
and global ordering/LIMIT selects keys. Keeping K eligible keys per source is safe:
any discarded key already has K distinct earlier keys in that source, so cannot
belong to global Top K even when sources overlap. No truncated candidate set
establishes exhaustion. Sorts merge source-selective keys, not unrelated global
history. One statement preserves a coherent current source/permission snapshot.
The deduplicated eligible own/community/direct-CUSTOM seed also supplies a lower
key bound when it contains at least K keys. Author branches need not read below
that seed's Kth key: K distinct already eligible keys precede anything discarded.
With fewer than K seed keys the bound is negative infinity and no author key is
pruned. This is an exact Top K optimization inside the current statement, not a
cached permission, raw-candidate cutoff or feed snapshot. Seek/cutoff precede the
seed, so each continuation recomputes its own safe bound.
Author sources use parameterized per-author covering-index seeks and eligible
Top K, avoiding a sort of every followed author's whole history. A seed bound
can shorten those seeks. No graph IDs leave PostgreSQL.
Direct CUSTOM IDs likewise use one covering-index array seek inside PostgreSQL;
an explicit empty-addressed-source guard prevents a post scan when no recipients
exist. The array contains only current recipient IDs for this viewer. Owner
privacy still precedes eligible LIMIT; no raw candidate truncation is added.
For more than 1,000 effective author sources and an eligible seed bound, an optional
dense-path probe reads at most 1,001 published chronology keys in that exact
cutoff/seek/seed interval. It is used only if its count is <=1,000, proving the
whole interval was read. Those complete keys are joined to current author sources
and checked by canonical privacy before eligible Top K. On overflow the entire
probe is discarded and parameterized per-author seeks supply candidates. It never infers
exhaustion or advances a cursor from a truncated/private prefix. Empty/sparse/no-seed
sources do not execute the probe. The cap is database work, not an exposed count,
cached grant or new application refill budget. Regression covers complete and
overflow paths, inactive-author denial and no lost older eligible keys.
Explicit community/timeline retain their scoped ordered index path with per-ID
owner proof. Hydration remains unchanged: selected-ID bounded facts, current source
recheck and coherent body/relations. No timeout relaxation, authorization cache,
new dependency/view is introduced. V0024 adds a covering partial personal-author
chronology index, measured against the source-driven plan's random heap lookups.
V0025 adds an ID-keyed covering partial CUSTOM index for addressed-ID chronology
lookups without body heap reads. Source edge flags and denied-author sets use
current statement-local published views, avoiding repeated pair lookups per post.
V0026 covers community chronology/author IDs so policy facts do not force a hot
community body heap/sort. Seed proofs reuse current candidate fields and point owner
facts; direct CUSTOM's recipient fact is earned by the addressed CTE for that same
viewer inside the same statement, not an assumed or cached permission. Canonical
ELIGIBLE and final coherent source/body hydration are retained unchanged.
Actual plans/work are in the phase record.

Measured compilation refinement: HOME owner scans use transaction-local
`set_config('jit','off',true)` before their short OLTP statement. Server/session
defaults remain unchanged and commit/rollback restore them; real PG regression
tests verify both paths. It is not a timeout increase, cache, changed query work
bound or permission shortcut. The additional setting statement is included in
actual JDBC counts. EXPLAIN tooling runs through the actual owner API in a read
transaction before measuring, so the measured planner/execution settings match
the application, not a benchmark-only tuning. See [PostgreSQL's JIT guidance](https://www.postgresql.org/docs/18/jit-decision.html)
and [transaction-local settings](https://www.postgresql.org/docs/18/functions-admin.html).

## Later ranking

Add a scoring interface only when a second algorithm is implemented. Inputs can include recency, interaction affinity, community preference and engagement; exclude hidden actors/content from features that could disclose private activity. Persist a ranker version and explainable scoring metrics. Product experiments use explicit configuration and deterministic cohorts, not an ungoverned feature flag service.

Live recalculated scores cannot use chronological cursors safely. For ranked paging introduce a short-lived viewer-bound ranking session: ordered candidate IDs/scores with stable tie-break and model version, TTL ~10 minutes. Redis can store it, but permission/deletion checks remain live. On eviction/expiry return a restart-required cursor error; do not silently switch ordering mid-pagination. Do not use OFFSET over a moving score function.

## Scale-triggered evolution (Phase 9)

Only after explain plans, indexes, query shape and batching are sound, consider a rebuildable `feed.inbox_entry(recipient_id,post_id,published_at,source,source_version)` projection, PK(recipient_id,post_id), index(recipient_id,published_at DESC,post_id DESC). A durable PostCreated consumer performs write-time fan-out to bounded audience sets. Per-recipient write cost, distribution of follower counts, worker lag and PostgreSQL IO determine the trigger; there is no arbitrary “10,000 users means Kafka” rule.

High-degree authors and large communities stay read-time sources; ordinary authors may fan out. Home feed merges inbox and read-time candidates, deduplicates, ranks and reauthorizes. Audience changes, blocks and deletions are immediately safe because content remains authoritative, even if inbox cleanup is delayed. Follow/membership changes need backfill or a temporary read-time source for the recent horizon; removing relations suppresses stale entries at eligibility filtering. Preserve source provenance so losing one source does not incorrectly remove a post still eligible through another.

Use Redis only for candidate IDs/ranked sessions and hot non-sensitive summaries, with bounded TTL and rebuild paths. Cache miss queries PostgreSQL. Privacy/session status is not accepted from stale caches. A broker enters only if independent consumers/processes and sustained event throughput justify operating it; outbox remains the commit-to-delivery bridge. A dedicated feed service requires replacing published SQL dependencies, defining lag/privacy behavior and an ADR; it is not Phase 6 work.
