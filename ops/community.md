# Community operations

Phase 2 uses the existing PostgreSQL worker, no external service or new dependency.
See [subsystem](../docs/community.md), [schema](../docs/database-design.md),
[worker runbook](identity.md) and [phase evidence](../docs/phases/phase-2.md).

- `app.worker.enabled` / `WORKER_ENABLED` controls the existing scheduler.
  `app.worker.poll-ms` defaults to 1,000. Each tick handles at most one mail,
  one outbox delivery and one registered DB job; each effect has its own transaction.
- Invitation lifetime is seven days; suspension ceiling 365 days, API size 100,
  offset 10,000, batch facts 100, optional idempotency retention 24 hours. These
  product/input defaults are fixed in Phase 2; no extra configuration service.
- All unsafe routes retain CSRF, trusted Origin, 16-KiB body and current Bearer
  checks plus existing 60 writes/minute/user limiter. Idempotency-Key is explicitly
  CORS allowlisted; it never bypasses authorization/rate limits.
- Queue/retry/DEAD gauges, oldest pending age and `social.work.failures` cover the
  new DB handler. Inspect jobs by kind COMMUNITY_INVITE_EXPIRY and error code
  JOB_HANDLER_FAILED/ATTEMPTS_EXHAUSTED. Monitor PostgreSQL guard timeouts and
  HTTP 409/412/503; do not log request messages/reasons or use UUID metric labels.

The worker can be stopped without permitting expired invitation acceptance.
Restart claims due PENDING/RETRY or expired RUNNING leases. Lease loss during a DB
handler rolls back its effect/acknowledgement; a newer generation can reclaim.
Fenced status updates require owner/generation/unexpired lease. Ten failures
reach DEAD and remain for operator review. COMPLETED jobs prune after 30 days.
Idempotency cleanup is bounded to 500 expired records per prune tick.

For a DEAD expiry job, first inspect its source invitation via restricted operator
tools. Already terminal/replaced invitations need no repair. For a still-PENDING,
expired source, an authorized operator may audit and reset only that job to RETRY,
attempts 0, available_at current time, with lease fields cleared. Preserve ID,
kind/dedup key and payload; do not regenerate invitations or extend expiry to
hide a worker failure. Re-execution resolves the source idempotently. API denial
is the safety boundary even before repair. No automatic destructive repair API.

Production runtime role must deny UPDATE/DELETE on moderation.action and audit
tables; append-only triggers are a further backstop, not superuser protection.
Separate migration/runtime roles and restrict moderation reasons/snapshots.
All Phase 1 SMTP/provider/key/TLS/rate-limit and Phase 9A launch gates remain.
Full membership/admission/action retention and erasure requires the approved
launch-hardening workflow; this phase retains attributable history.
