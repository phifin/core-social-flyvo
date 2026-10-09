# ADR-011 — Search and conditional caching

Status: Accepted, 2026-10-08.

Context: a small team benefits from PostgreSQL search and avoiding an extra source of stale privacy decisions. Multiple public instances eventually need shared abuse controls.

Decision: dedicated search API/port with a rebuildable PostgreSQL tsvector/GIN projection and source-versioned events. Source APIs authorize before any snippet/count/name returns. Redis is conditional: shared rate limiting before multi-instance public traffic, optional SSE pub/sub and measured candidate caches/ranked sessions. Keep permissions, refresh history, notification truth and lifecycle uniqueness database-backed.

Consequences: projection lag affects freshness, not permission. Cache/engine outage degrades optional features with explicit policies. Dedicated engine migration uses outbox dual projection/backfill and shadow comparisons; no direct domain dependency on engine SDKs. Language relevance is evaluated using realistic multilingual fixtures.

Revisit OpenSearch/Elasticsearch only when search relevance or measured PostgreSQL load justifies operating it. No “cache everything” phase; each cache must define owner, key scope, invalidation, TTL and miss/outage behavior.
