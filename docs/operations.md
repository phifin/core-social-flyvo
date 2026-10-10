# Operations and deployment direction

## Deployment baseline

Phase 4 [media operations](../ops/media.md) covers real local enablement, individual
secret mounts, SDK endpoint split, bounded isolated processing, quota/reconciliation,
lease recovery and restricted audited replay. Post/avatar APIs are implemented;
production S3 IAM/KMS/ingress compatibility remains an explicit deployment gate.

Phase 3 graph uses the existing PostgreSQL worker and platform encryption key;
request expiry, cursor lifetime, limits and recovery are in [graph operations](../ops/graph.md).

Start with one HTTP + worker application process, managed PostgreSQL, private object storage when media begins, and an email transport when authentication begins. Local Docker Compose profiles provide postgres, SMTP sink (Mailpit), then a single PGSTY Silo storage node in Phase 4 per [ADR-013](adr/013-local-object-storage-distribution.md); bind developer storage ports to loopback and separate administrative/runtime credentials. Compose is a development environment, not an implied production HA deployment. Use a non-root container, read-only filesystem where practical, bounded temporary directory for media, resource limits, pinned image digests and graceful shutdown.

Production credentials are separate from local fixtures. App DB role has only runtime DML/view access, migration role owns DDL, worker storage permissions are scoped to required prefixes, public HTTP layer cannot list buckets. Credentials/keys come from a secret manager or mounted secrets; examples contain placeholders. TLS at trusted reverse proxy plus protected database/storage connections. Configure trusted proxy hops explicitly; never accept spoofed forwarded client IP for rate limiting.

Single-instance deployment is a Phase 1 limiter assumption. Before multiple public instances, enable shared Redis rate limits or equivalently enforced centralized edge limits and run multi-worker/connection tests. Use the same artifact with `app.worker.enabled` and `app.http.enabled` configuration when workloads need independent scaling. No service mesh or Kubernetes requirement.

## Logging, audit and observability

Phase 1 setup, provider approval gates, persistent local/mounted production keys,
rotation and worker recovery are in [identity operations](../ops/identity.md).
Its worker exposes queue/dead/retry depth, oldest pending age and failure count.
Mail health is deliberately excluded from aggregate health; PostgreSQL remains
the readiness dependency. SQL statement timeout is five seconds and lock timeout
three seconds. Local process hashing concurrency is four; public traffic remains
single-instance plus deployment-edge limits until shared limits are introduced.

Emit structured JSON with UTC time, level, service/version/environment, requestId, traceId when present, bounded route template, event/job ID and error code. Validate inbound X-Request-ID length/character set or replace it; generate UUID otherwise and return it in responses/Problem Details. Propagate correlation/causation into outbox/jobs. Never log tokens/cookies/passwords/presigned queries/raw private content or email body. User IDs only where necessary for restricted audit, not as high-cardinality metric labels.

Audit domain-sensitive commands synchronously: login/link/recovery/revocation events, community ownership/roles/bans, audience widening, moderation, administrative reads/changes and erasure. Record actor, action, target/scope, reason, result and request ID with minimal metadata. An audit entry is append-only at application DB permissions; this is not proof against a database superuser. Export to restricted immutable storage if compliance requires stronger tamper evidence. Mandatory audit-write failure rolls back the sensitive state change. Failed authentication audit uses a separate deliberately committed security recording path because the business transaction may not exist; avoid leaking identity via the audit API.

Actuator: liveness only process health; readiness requires PostgreSQL and successful migrations. Do not fail liveness when an external provider is down, causing restart storms. Storage/mail outage degrades relevant features, exposes component metrics and lets queues retry; readiness policy does not drop an otherwise useful API due to optional SSE/cache. Protect actuator internals; only minimal health status is anonymously exposed if deployment needs it.

Micrometer metrics: request rate/latency/errors by route, connection pool saturation, DB duration/slow query samples, auth rejection/replay rates, rate-limit decisions, candidate examined/returned ratio, outbox oldest age/dead count, job retries/lease reclamation, media validation failures/processing latency/storage quota, notification delivery lag and SSE connections. No user/post ID or query text labels. Add Prometheus-compatible export in Phase 0; dashboards/alerts grow with each capability. OpenTelemetry tracing is introduced once cross-process/provider/worker diagnosis benefits from it; pass trace context in event metadata, not user content. Error tracking adapter is optional before launch, with redaction tested.

## Failure and recovery contracts

Phase 2 bounds, DB job replay/fencing, private sanction records and current-owner
closure checks are covered by [community operations](../ops/community.md).
No new external infrastructure/dependency or deployment authorization is added.

| Failure | Expected behavior |
|---|---|
| PostgreSQL unavailable | Protected reads/writes fail closed 503; no cached permission bypass; readiness false |
| OAuth provider unavailable | Existing local sessions still work; provider login returns retryable error; no email-based fallback login |
| SMTP unavailable | Registration request remains durably queued, retry bounded by token expiry; no claim email delivered |
| Storage unavailable | New uploads/finalization/download capabilities fail appropriately; text/feed functions continue; jobs retry |
| Worker down | Committed content remains readable, search/notifications/media lag; alert on age; no lost outbox |
| Redis unavailable later | Optional cache/pubsub degrades; sensitive rate-limited writes follow documented fail-closed policy |
| Search engine unavailable later | PostgreSQL fallback or explicit degraded search, never unauthorized stale results |

Expose meaningful async status/failure codes rather than leaving processing forever. Operators need runbooks for DEAD job retry, event replay, media reconciliation, account recovery without implicit merging, signing-key rotation, provider credential rotation, backup restoration and incident response. Runbooks must distinguish replayable projections from irreversible external actions.

## Migrations, backups and retention

Local/test startup can run Flyway. Production runs migrations as a controlled one-shot deployment step with a DDL role, then starts API with schema validation. Prefer backwards-compatible expand/backfill/contract; acquire deployment coordination for migrations, not a lock on all application writes. Tune connection pool so API + worker instances leave PostgreSQL headroom. Set timeouts and bounded executors; retries have jitter and total deadlines.

Before external launch, configure encrypted automated backups with point-in-time recovery, object version/lifecycle rules, keys backup/recovery and a tested restore. Initial operational objectives: PostgreSQL RPO <=15 minutes and RTO <=4 hours, measured by a restore drill; storage recovery objectives must match provider capabilities. These are launch targets, not present guarantees. Restoring DB without compatible object versions can break attachments; reconcile and fail unavailable media closed. Replay erasure manifests after restoring an older backup. Regularly test recovery rather than merely checking backup jobs are green.

Retention defaults are in database-design. Account deletion is an asynchronous bounded workflow, not a cascading SQL DELETE across all modules: revoke session access immediately, reject ownership blockers, emit deletion request, erase credentials/profile, redact/delete authored content and mentions per policy, remove graph/memberships, purge media/projections/notification PII, preserve only permitted audit tombstones. Each owner reports completion and retries safely; account tombstone reaches DELETED once required erasure steps finish. Add owner-specific deletion progress records during the launch-hardening phase. No personal data in deleted-account display names.

## Product decisions before a public launch

No unanswered product question blocks Phase 0–8: use the defaults in the blueprint. Before operating publicly, the owner must confirm jurisdiction/retention and age/minor consent requirements, provider registrations/reviews, outbound email domain, production storage/provider region, capacity/cost budget and administrator access controls. These materially affect operations and policy and cannot be inferred from a family-oriented first application. Do not add family-specific policy to the neutral core. No license is granted merely by calling this reusable: repository owner must choose a license before public redistribution.

Configuration flags are typed, startup-validated and documented. Initial flags enable configured OAuth providers, registration, worker execution and optional realtime/search. Do not hide incomplete authorization under a flag or introduce a feature-flag SaaS before there is an experiment/rollout use case.
