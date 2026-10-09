# Search and cache strategy

## PostgreSQL search (Phase 8)

Search has a dedicated application API and infrastructure adapter. `search.document` is a rebuildable projection keyed by source type/ID/version, updated from durable events. It stores only text needed for users, communities and posts, with PostgreSQL full-text `tsvector` and GIN indexes. Use the `simple` configuration initially to avoid silently assuming English; support exact/prefix handles and normalized community names. Vietnamese/other language tokenization and accent behavior require relevance fixtures before adding language-specific dictionaries or unaccent. `pg_trgm` is an optional measured enhancement for names, not a default wildcard scan over post bodies.

Search query limits: 2..200 characters, bounded terms, limit default 20/max 50, statement timeout and per-user/IP limits. Parameterize `websearch_to_tsquery`/appropriate query builders. Rank by text match then deterministic ID. PostgreSQL search pages use signed cursor `(rank,id,queryHash,viewer,asOf)`; `asOf` restricts projection updated_at to the first page. Concurrent updates can omit a result until refresh; no stable-result snapshot promise. Do not use an offset into live results for deep pagination. If a truly stable ranked session is needed, use a bounded candidate snapshot as in feed rather than pretending the cutoff is a snapshot.

Index is **not** an authorization database. Search returns candidate IDs/scores internally; source APIs authorize batch hydration before any snippets, names, highlights, media, facet or count is returned. Private-community posts are never discoverable to nonmembers, and profile scopes/blocks apply to users. Refill with bounded scans and advance past rejected candidates. Return no exact global total. Store raw projection text only within restricted infrastructure; telemetry must not log private search results/queries by default.

Deletion/audience/status changes apply immediately to source authorization even while indexing lags. Worker updates only if source version is newer; tombstones prevent an older event from resurrecting deleted text. Account/block/membership changes need not rewrite every index document for privacy correctness. Rebuild: consume live versioned changes, batch current source snapshots through published export APIs, upsert only newer versions, compare counts/checksums, repair missing tombstones, then mark rebuild complete. No repository imports from another module.

## Dedicated engine boundary

`search.application.port.SearchIndex` is justified when the projection exists: query candidates, upsert source version, delete/tombstone, rebuild namespace. PostgreSQL implements it initially. Introduce OpenSearch/Elasticsearch only when measured relevance needs (language analysis, typo tolerance, advanced facets) or query load justify another operated system. At that time dual-write from outbox, backfill, compare authorized results/latency, switch with a reversible configuration flag, and retain PostgreSQL fallback until confidence is established.

A dedicated engine still requires authoritative source filtering. Never export credentials/emails/session data. Protect engine network/auth/backups as private data; source content visibility does not imply the whole index is public. Change-data-capture is not a license to replicate every private table.

## Redis decision and cache ownership

Redis is not foundational. PostgreSQL remains authoritative for sessions/revocation, uniqueness, graph, membership, media lifecycle, notifications, outbox and jobs. Phase 1 uses bounded local rate limiters under a documented single-instance assumption. Introduce Redis **before multiple public API instances** for shared rate limiting, and when Phase 7 multi-instance SSE needs pub/sub. Neither is a reason to cache all objects.

| Candidate | When / TTL | Invalidation and correctness |
|---|---|---|
| Rate-limit buckets | Before multi-instance public deployment; window TTL | Atomic Redis operation; explicit outage policy; no persistent user lockouts |
| Public community name/card, public profile display summary | Only after profiling; 30–60 seconds | Owner update/status event evicts; current privacy/account status still checked before serving; never cache private body as public |
| Feed candidate ID sets | Scale phase; <=30 seconds | Relationship/content events best-effort evict; source eligibility/visibility always live |
| Ranked feed sessions | When nonchronological ranking ships; ~10 minutes | Viewer-scoped immutable order; expire -> restart; source checks every page |
| Provider public verification keys | Provider SDK cache respecting key metadata | Retry bounded JWKS refresh on unknown key; never accept unverifiable token |
| SSE invalidation pub/sub | Multi-instance realtime | No persistence guarantee; REST inbox repairs loss |

Avoid caching permission decisions, refresh-token histories, private signed URLs, full per-user feeds, exact unread counts or membership lists initially. Cache key includes module/version/scope/viewer where applicable; no raw secrets/PII in keys. Bound memory, TTL and negative-cache lifetime. Prevent stampedes with short local single-flight, not a distributed lock around all requests.

Redis outage: source reads fall back to DB with concurrency caps; optional feed cache and SSE degrade to REST polling. Sensitive auth/write rate limiting fails closed (503 or constrained local fallback behind a trusted edge limit); public read limiting may use conservative local limits. Document the deployed policy and test it. A Redis outage never makes a revoked token or forbidden post acceptable.
