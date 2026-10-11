# Next package: interactions, reports and chronological feeds

Status: ACTIVATED by main on 2026-10-11 at the user's request to review and continue.
Phase 4 code `9bdc999` was accepted in `26d60ce`; see the fixed mailbox and
[accepted review](../handoffs/phase-4-posts-media.md). Phase 5/6 remain unimplemented.

New development chat to open: `Develop Phases 5–6 — Interactions, Moderation & Feeds`.
One chat implements the entire package sequentially; no spawned agents or pushes.
This doubles the roadmap-phase scope of a normal assignment, not a promise of
twice the throughput. Keep internal slices green without frequent user handoffs.

## Scope, owners and dependency order

Read the active mailbox, AGENTS, implementation handoff, architecture, security,
roadmap Phases 5/6, domain/database contracts, feed, events/jobs, API conventions,
testing strategy and relevant accepted ADRs. Verify the accepted Phase 4 code hash
and all resolved findings before edits. Detailed subsystem contracts prevail.

1. **Content interactions:** two-level comments/replies, edit/deletion/tombstones;
   typed single-current reactions for post/comment, replacement/removal/reactivation;
   bounded actor lists and viewer-filtered batched counts. These are content
   subdomains, not new comment/reaction modules. Reuse current parent-post policy,
   account/community/graph guards and media/source query seams.
2. **Moderation reports:** submission, duplicate unresolved-case semantics, scoped
   staff queue/evidence/actions/resolution and mandatory audit. Moderation calls
   content/community owner commands in one transaction; no foreign writes or policy
   duplication. Extend existing typed action model rather than replacing it.
3. **Feed:** home/community/personal timeline, candidate orchestration, chronological
   ranking, scoped cursor, bounded refill, source-authorized batch hydration and
   metrics/representative performance tooling. Feed calls content APIs; only the
   approved content SQL adapter consumes published graph/community/account views.

Comments/reactions and report transitions first; run complete Phase 5 verification
before dependent feed integration. This internal gate allows progressing inside
the assigned package, not self-acceptance. Main reviews both phases at final handoff.

## Plan to record before code

- List actual existing APIs to extend, owned new tables, endpoint DTOs/errors and
  event types/versions. Specify source-authorized comment summaries and batch
  engagement contracts needed by feed; no notification/search stubs.
- Inspect migration maximum at activation (currently V0021); allocate new globally
  ordered versions in this one writer. Never edit existing migrations. Include
  comment-root cross-post integrity, reaction uniqueness across inactive tombstones,
  unresolved-report concurrency constraints and justified query indexes.
- Write a lock-order table per command: sorted account guards, community, graph,
  post, comment, media only if required. Request final shared/exclusive modes at
  first acquisition; no lock upgrades or reverse-order callbacks. Document bounded
  retry only for idempotent operations and persist action/audit/outbox atomically.
- Map each acceptance criterion to an actual test and fixture, including the
  representative feed benchmark. Assign sequential slice commits, not separate
  chat handoffs. Stop only for a concrete contract conflict/external prerequisite.

## Internal delivery gates

### A. Content interactions complete

Verify HTTP and real PostgreSQL paths for normalized reply-to-reply roots,
cross-post root denial, blocked/deleted root tombstones with visible children,
private membership loss, public-community nonmember write denial, parent
deletion/removal races and bilateral blocks. Tombstones cannot leak root identity
or text. Reaction PUT/type-change/DELETE/recreate preserves stable row identity and
monotonic event version. Concurrent unique semantics, filtered counts/actor lists,
no N+1, duplicate/reordered lifecycle events and no-op effects need evidence.

### B. Report workflow and complete Phase 5 gate

Test reporter/staff IDOR, wrong community, role hierarchy/self/owner conflicts,
duplicate concurrent reports, bounded private evidence after target deletion,
restore versus author deletion, membership/sanction races and audit failure rollback.
Do not expose evidence in ordinary content/events or grant staff global access.
Run targeted tests, formatting and full verify; update Phase 5 evidence before C.

### C. Feed implementation and measured integration

Follow docs/feed.md exactly: home unions own/follow/friend/joined-community/direct
CUSTOM sources, deduplicated before pagination. Followed users' unrelated community
posts are not home sources. Timeline is personal only. Mutes filter home but not
deliberately visited timeline/community; blocks/current audience/deletion affect all.

Use database-time cutoff, immutable `(published_at,id)` ordering, authenticated
viewer/scope/filter-bound 30-minute cursor, last **examined** candidate, 20 default/
50 maximum results, 100-candidate batches, at most 1,000 examined or 200 ms query
budget. Empty filtered pages can continue; no false snapshot promise or hidden
totals. Reauthorize coherent current content; no 10,000-ID Java graph materialization.

Test equal timestamps, overlapping sources, cursor tampering/scope changes,
unexamined prefetched tails, high rejection, no cursor loops, after-cutoff posts,
membership/block/unfollow changes, source deletion before hydrate, newly eligible
older posts and no chronology bump on edits. Enrichment is batch-authorized.

Provide reproducible isolated test-tooling datasets for one million posts, 10,000
follows, skewed communities and blocks, with measured EXPLAIN/query count/scan ratio,
latency and 50-reader concurrency evidence on recorded hardware. Record whether the
initial p95 <500 ms target is met; do not substitute tiny fixtures or claim production
capacity. Keep heavy benchmark outside normal test setup, with exact run command.
If resources prevent it, report the gate as unrun, not a successful phase completion.

### D. Package handoff

Run final targeted security/race/SQL-object parity tests, formatting, full verify,
OpenAPI/Compose and full-history redacted secret scan. Update API/schema/subsystem/
operations/README and separate phase-5/phase-6 records with exact checks/limitations.
Commit verified slices; then separately commit mailbox referencing their real code
hashes. Set READY_FOR_REVIEW, next owner main, respond to all findings without
self-resolving. Main reviews and alone accepts/pushes.

## Non-goals

No Phase 7 notifications/SSE, Phase 8 search or advanced moderation operations,
Redis, broker, feed inbox/fan-out, ranking sessions, microservices, rich media,
production release or deployment. Report unresolved architecture conflicts with a
specific proposed ADR; do not broaden scope to keep working around a blocker.
