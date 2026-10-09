# ADR-009 — Outbox and background jobs

Status: Accepted, 2026-10-08.

Context: verification email already requires reliable asynchronous work. In-memory events alone lose effects on process failure, while a broker cannot atomically commit a PostgreSQL change.

Decision: introduce PostgreSQL jobs/outbox in Phase 1, atomically with originating commands. Use a small scheduler, SKIP LOCKED claims, leases/fencing, retries/backoff, terminal failure visibility and idempotent consumers. Producer-owned event records use minimal versioned semantic payloads. Required consumers get delivery rows; new consumers require backfill. DB effects and receipt commit together; external email may deliver more than once.

Consequences: moderate plumbing early is justified by concrete durability. Delivery is at least once, ordering is per consumer contract, not assumed globally. General outbox never contains raw secrets; outbound one-use email links use separate encrypted expiring payload storage. Broker introduction would not remove the outbox.

Revisit a job framework for actual scheduling complexity; a broker for independent consumer throughput/transport; extraction for operational ownership. Never substitute `@Async` or a transaction listener for durability of required work.
