# ADR-002 — Ownership, APIs and persistence

Status: Accepted, 2026-10-08.

Context: controller-service-repository layering alone permits business coupling; exhaustive ports/DTO mappings can also make a student project unmaintainable.

Decision: domain-oriented modules with web/application/domain/infrastructure packages. Export immutable API contracts and event types; forbid cross-module entity/repository imports. Put comments/reactions inside content because they share its invariants. Account, identity and profile have separate ownership. Allow Jakarta Persistence annotations on domain aggregates, but no Spring/HTTP/security framework logic. Repository/provider/storage boundaries get ports; trivial internal helpers do not need interfaces.

Use database schema ownership and explicit cross-module FKs. High-cardinality content queries may consume only documented, versioned read-only views published by account/graph/community. These are intentionally SQL module APIs, not hidden access to base tables. All other consumers call viewer-aware content APIs. See [architecture](../architecture.md).

Consequences: good query plans without giant ID lists, N+1 calls, duplicate domain/JPA models or unsafe eventually consistent privacy projections. SQL contracts add a real extraction cost; their schema and policy parity need tests. Same-database FKs do not imply Java object associations.

Revisit a port/view only with query evidence or extraction requirements. Adding a cross-module import/view needs an explicit allowlist/documentation update, not an exception suppressed in a test.
