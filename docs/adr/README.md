# Architecture decision records

All records below are **Accepted for implementation** as of 2026-10-08, not claims of deployed features. Preserve history; supersede a decision with a new numbered ADR when its tradeoff changes. Each ADR records context, decision, consequences and a revisit trigger. Detailed contracts remain in the linked subsystem documents; do not duplicate mutable endpoint/schema lists here.

| ADR | Decision |
|---|---|
| [001](001-runtime-and-modularity.md) | One Java/Maven/Spring Boot modular monolith with tested package boundaries |
| [002](002-module-and-persistence-boundaries.md) | Owned aggregates, API-only collaboration, scoped SQL read contracts |
| [003](003-postgresql-and-transactions.md) | PostgreSQL/Flyway, constraints plus guarded local transactions |
| [004](004-identity-and-sessions.md) | Separate account/identity/profile, explicit identity linking, rotating sessions |
| [005](005-authorization-and-audience.md) | Owner-module policies, current source visibility and deny precedence |
| [006](006-social-graph-and-interactions.md) | Distinct graph relations, bounded comments, typed reactions |
| [007](007-feed-and-pagination.md) | Read-time feed first, signed keyset pagination, measured hybrid evolution |
| [008](008-media-lifecycle.md) | Private direct upload, pinned versions, asynchronous validated variants |
| [009](009-events-and-durable-work.md) | Local ACID outbox/jobs, at-least-once delivery, no initial broker |
| [010](010-notifications-and-realtime.md) | Semantic persistent inbox, contribution aggregation, SSE hints |
| [011](011-search-and-caching.md) | PostgreSQL search, source authorization, conditional Redis/engine |
| [012](012-quality-and-operations.md) | Real-PostgreSQL tests, architecture gates, observability and release discipline |

Future ADRs are required before approved-follow workflows, SaaS tenancy, native-client credential transport, account merging, rich HTML/documents/video, nonchronological ranking, broker introduction, service extraction, stricter media revocation, or changes to retention/privacy guarantees. Not every new endpoint needs an ADR.
