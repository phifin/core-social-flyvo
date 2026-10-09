# ADR-005 — Source-authoritative authorization

Status: Accepted, 2026-10-08.

Context: scattered controller checks and stale feed/search caches can expose private material, even if primary resource reads are correct.

Decision: named policies in owning application modules compose current account, graph, membership and ownership facts. Content policy is shared by single reads, queries and media/notification/search hydration. Community posts use COMMUNITY and inherit current community visibility; personal posts use PUBLIC/FOLLOWERS/FRIENDS/CUSTOM/ONLY_ME. Block takes precedence over ordinary visibility; mute changes discovery/notifications only. Current source authorization always runs after projections/cache candidate generation.

Exactly one effective community OWNER comes from community.owner_user_id with a valid active membership; persisted membership roles are ADMIN/MODERATOR/MEMBER. Explicit audited moderation endpoints may inspect otherwise hidden material in their scope. Ordinary endpoints never inherit a broad moderator bypass. Anonymous PUBLIC visibility is an honest confidentiality limit.

Consequences: current privacy wins over stale projections, at the cost of batched authoritative reads and careful SQL parity tests. Previously downloaded bytes and in-flight responses cannot be revoked; presigned media capabilities have a documented short expiry. OWNER is a derived role, avoiding redundant divergent authority columns.

Revisit hidden communities, approval-required follows, custom audience lists larger than 100, or strict proxy-mediated media revocation only as explicit product/security changes.
