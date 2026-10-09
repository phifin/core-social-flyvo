# ADR-003 — Relational invariants and concurrency

Status: Accepted, 2026-10-08.

Context: uniqueness, token rotation, block races and membership authority require stronger guarantees than CRUD service checks.

Decision: PostgreSQL 18, Flyway-only schema mutation, Hibernate validation/OSIV off, UUID public IDs, UTC Instant/timestamptz. Use PK/FK/CHECK/partial unique constraints, a few narrow tested constraint triggers, optimistic versions for edits, and deliberate row/advisory guards for cross-row transitions. One application command owns a local transaction; module API commands join it. Network effects run outside locks using durable jobs/outbox.

Use READ COMMITTED by default with the documented global lock order. Pair advisory locks serialize graph mutations and interaction conflicts; they are transaction scoped, not distributed locks. Use the same production PostgreSQL major in Testcontainers. [PostgreSQL locking reference](https://www.postgresql.org/docs/18/explicit-locking.html).

Consequences: correctness survives duplicate requests and concurrency. Some post/community rows serialize writes initially; measure contention before adopting finer-grained machinery. DB constraints are a backstop, not a substitute for useful domain validation/errors. Read pagination does not claim snapshot isolation across requests.

Revisit hot aggregates based on lock-wait metrics; service extraction requires replacing local invariants with explicit distributed policies. No silent switch to eventual authorization.
