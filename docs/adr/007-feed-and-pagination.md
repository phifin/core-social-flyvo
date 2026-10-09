# ADR-007 — Feed and cursor strategy

Status: Accepted, 2026-10-08.

Context: a feed deserves explicit orchestration, but initial fan-out infrastructure would multiply operational work and privacy cleanup paths.

Decision: read-time home/community/timeline candidates, deduplicate, chronological ranking, bounded batch hydration and current source authorization. Published SQL views support high-cardinality membership/graph filtering. Signed viewer/scope-bound keyset cursor contains immutable timestamp/ID ordering, asOf cutoff and expiry. Advance by last examined candidate and permit short/empty continued pages. Use offset only for bounded administrative lists.

Consequences: no inbox storage amplification and no Redis dependency initially. Read-time joins must be indexed/measured; mutable graph/privacy can change results between pages. A cutoff is not a snapshot. Nonchronological ranking requires a separate stable ordering session rather than reusing a changing score cursor.

Revisit hybrid write/read fan-out, candidate caching and ranking when query/traffic/relevance measurements justify them. Even an eventual inbox may never become an authorization source. See [feed](../feed.md).
