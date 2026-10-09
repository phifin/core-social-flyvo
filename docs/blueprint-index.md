# Blueprint coverage and document ownership

This index maps the master design request to its concrete deliverables. These are accepted planning artifacts. For implementation status, use README and [phase completion evidence](phases/phase-0.md); Phase 0 foundation is now implemented, while social features remain planned.

| Requested area | Authoritative document |
|---|---|
| A. System architecture | [architecture](architecture.md): style, module ownership/DAG, contracts, sync/async, transactions |
| B. Repository/package structure | [architecture](architecture.md#proposed-repository-tree): complete target tree and package example |
| C. Domain model | [domain-model](domain-model.md): ownership, aggregates, lifecycles/invariants |
| D. Preliminary database | [database-design](database-design.md): tables/types/FKs/indexes, constraints, locks, views, retention |
| E. REST API | [api-conventions](api-conventions.md): resource surface, pagination, errors, idempotency, authorization |
| F. Authentication | [security](security.md): local/provider flows, session rotation, identity collision/linking table |
| G. Authorization/privacy | [security](security.md#authorization-placement): decision precedence, community matrix, cross-surface rules |
| H. Feed | [feed](feed.md): candidates/ranking/pagination, initial queries, measured hybrid evolution |
| I. Events and async | [events-and-jobs](events-and-jobs.md): catalog, durability, retries/order, notifications |
| J. Media pipeline | [media](media.md): lifecycle, failure handling, authorization/cleanup, future types |
| K. Cache | [search-and-caching](search-and-caching.md#redis-decision-and-cache-ownership): Redis timing, TTL/invalidation/outage |
| L. Search | [search-and-caching](search-and-caching.md): PostgreSQL projection, authorization, engine migration seam |
| M. Testing | [testing-strategy](testing-strategy.md): behavior/security/concurrency/architecture/CI gates |
| N. Observability/operations | [operations](operations.md): telemetry, audit, health, recovery/retention and launch gates |
| O. Complete phase roadmap | [roadmap](roadmap.md): Phases 0–8, mandatory 9A and conditional 9B, per-phase acceptance/edges |
| P. Dependency introduction | [roadmap](roadmap.md#dependency-introduction-matrix): timing and concrete justification |
| Q. Architectural decisions | [ADR index](adr/README.md): twelve accepted decisions with rationale/revisit triggers |
| R. Agent development rules | [AGENTS.md](../AGENTS.md) and [implementation handoff](implementation-handoff.md) |

## Decision status and assumptions

No product decision blocks implementation of the baseline phases. Explicit defaults: browser/same-site client first; private communities are minimally discoverable; PRIVATE+OPEN is valid; personal follows are open; owner is unique; comments have depth two; custom post audience is an explicit bounded list; same-email identities never auto-link; media URL revocation is bounded by 60-second expiry. These choices are intentional, not gaps for the next model to fill.

Before public operation the owner must settle retention/jurisdiction/age rules, licensing, real provider approvals/credentials, production hosting/storage/email region, budget/capacity and administrative security. These are deployment/product gates in [operations](operations.md), not excuses to ask about routine naming or stall Phase 0.

The reference links in the documents are primary technical sources checked during blueprint research. Concrete patch versions/provider settings must be verified again when introduced; no version marked as “latest” is frozen forever. Meta's official manual-flow page was unavailable to the research tool, so Phase 1 explicitly requires a fresh official provider validation check.
