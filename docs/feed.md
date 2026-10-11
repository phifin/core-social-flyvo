# Feed design and evolution

Phase 6 implementation/package gates are complete after the full Phase 5 internal
gate, pending main review. Source API/HTTP, privacy/cursor/refill tests and completed
isolated representative benchmark are in [Phase 6 evidence](phases/phase-6.md).
The measured 50-reader p95 target <500ms is **not met** on this recorded environment.
No acceptance or production capacity is implied.

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
account/graph/community views. The home source union is an EXISTS/OR union over each
post, naturally deduplicated before pagination without a Java graph ID list; ordinary
content policy and mute are applied in the same candidate SQL. Follow/friend sources
are personal-only; joined effective communities and direct CUSTOM are separate.

Hydration rechecks both current source eligibility (unfollow/membership/mute) and
ordinary privacy with the body/recipients/attachments/mentions in one statement.
Later batched owner checks can only remove a selected old response, never reload
newer text using an old grant. Profile attribution and engagement are batched after
eligibility. Media descriptors are not capabilities. Engagement is optional/null
if its source becomes inaccessible after the coherent body statement.

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

Feed SQL retains the common owner FACTS/ELIGIBLE predicate. Candidate traversal
orders cutoff/seek/scope through existing indexes and parameterizes the entire
owner proof by candidate ID beneath a LATERAL/OFFSET 0 barrier. There is no LIMIT
before visibility: outer LIMIT stops the ordered stream after eligible keys.
This prevents account/relationship joins from forcing a full eligible-set sort;
it is one statement, not per-post JDBC calls. Hydration explicitly bounds the fact
subquery to selected IDs and uses point lookups. No timeout relaxation, policy
shortcut or new index is introduced; representative EXPLAIN is in the phase record.

## Later ranking

Add a scoring interface only when a second algorithm is implemented. Inputs can include recency, interaction affinity, community preference and engagement; exclude hidden actors/content from features that could disclose private activity. Persist a ranker version and explainable scoring metrics. Product experiments use explicit configuration and deterministic cohorts, not an ungoverned feature flag service.

Live recalculated scores cannot use chronological cursors safely. For ranked paging introduce a short-lived viewer-bound ranking session: ordered candidate IDs/scores with stable tie-break and model version, TTL ~10 minutes. Redis can store it, but permission/deletion checks remain live. On eviction/expiry return a restart-required cursor error; do not silently switch ordering mid-pagination. Do not use OFFSET over a moving score function.

## Scale-triggered evolution (Phase 9)

Only after explain plans, indexes, query shape and batching are sound, consider a rebuildable `feed.inbox_entry(recipient_id,post_id,published_at,source,source_version)` projection, PK(recipient_id,post_id), index(recipient_id,published_at DESC,post_id DESC). A durable PostCreated consumer performs write-time fan-out to bounded audience sets. Per-recipient write cost, distribution of follower counts, worker lag and PostgreSQL IO determine the trigger; there is no arbitrary “10,000 users means Kafka” rule.

High-degree authors and large communities stay read-time sources; ordinary authors may fan out. Home feed merges inbox and read-time candidates, deduplicates, ranks and reauthorizes. Audience changes, blocks and deletions are immediately safe because content remains authoritative, even if inbox cleanup is delayed. Follow/membership changes need backfill or a temporary read-time source for the recent horizon; removing relations suppresses stale entries at eligibility filtering. Preserve source provenance so losing one source does not incorrectly remove a post still eligible through another.

Use Redis only for candidate IDs/ranked sessions and hot non-sensitive summaries, with bounded TTL and rebuild paths. Cache miss queries PostgreSQL. Privacy/session status is not accepted from stale caches. A broker enters only if independent consumers/processes and sustained event throughput justify operating it; outbox remains the commit-to-delivery bridge. A dedicated feed service requires replacing published SQL dependencies, defining lag/privacy behavior and an ADR; it is not Phase 6 work.
