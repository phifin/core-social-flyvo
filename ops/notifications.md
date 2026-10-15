# Notification inbox and single-instance SSE

The durable inbox and REST API are authoritative. `notification-inbox-v1` uses the
existing outbox worker, versioned source state and receipts. Start the normal app
with its existing PostgreSQL/worker configuration; no Redis/broker/new service.
See [inbox semantics](../docs/notifications.md) and [worker recovery](identity.md).

Use an Authorization-capable fetch stream, never EventSource with a URL token.
Keep the access token in client memory; parse SSE frames, refresh REST on every
connect/reconnect/change hint, and abort the old stream before refreshing its
token. JWT actual exp ends the stream; JWT clock skew cannot extend it. Browser
cookies/CSRF rules for the other REST commands remain unchanged. Last-Event-ID
may be sent but is ignored; it does not request a durable event replay.

Identity's ADR-015 proof contains verified actor/session IDs and exp only. Streams
retain neither raw token nor credential-bearing Authentication. One shared timer
ticks every 100ms; four shared check threads plus a queue bounded by the global
stream cap run current session/account and owner eligibility reads every five
seconds. The three-second check deadline includes queueing, and closes on missing
or unavailable auth state. Connections are returned between short owner reads;
there is no DB connection/transaction for the socket lifetime. Ordinary REST uses
its original current-session stack independently of optional transport failure.
The stream lifecycle stops one phase before Boot's graceful web-server drain and
rejects new openings while stopping. An open stream must not make shutdown wait
for JWT expiry; the native HTTP shutdown regression closes within five seconds.

Servlet WriteListener/isReady handles nonblocking output. At most two small frames
are queued per stream; a full queue or three-second unsent frame closes it. Native
async completion/error/timeout and token-expiry checks release reservations. The
transport sends only generic invalidations/random IDs and heartbeat comments.
Keep the verified Tomcat 11.0.27 patch override aligned across artifacts: 11.0.24
has a write-notification/completion race that can strand its native async counter
and delay graceful shutdown even after app stream reservations are released.
The patch is necessary but not sufficient for bounded completion of a blocked
native flush. This Tomcat adapter also installs a stream-only CLOSE_NOW hook,
used before completion for backpressure. It drops optional hints rather than
waiting to flush them. Only authenticated stream code invokes it, synchronized
with terminal callbacks to prevent use after native request recycling. Unsent
native-buffer work keeps the same deadline even when the Java queue is empty.
Changing servlet containers requires an equivalent verified abort adapter;
missing support returns503 for SSE. No global connector timeout is changed.
While an inbox is eligible, periodic repair also catches actor/source policy
changes that do not mutate notification's durable watermark; disappearance sends
one final generic repair. Hidden activity does not expose actor IDs or counts.

Configuration bounds (startup validated):

| Property | Default | Allowed |
|---|---:|---:|
| app.notification.stream.max-global | 100 | 1–1,000 |
| app.notification.stream.max-per-user | 2 | 1–5 |

Environment overrides: NOTIFICATION_STREAM_MAX_GLOBAL and
NOTIFICATION_STREAM_MAX_PER_USER.

Caps return 429/Retry-After5. Stream attempts have independent ten/minute user/IP
budgets (429/Retry-After60). Defaults do not demonstrate production capacity;
raising caps requires measurements against the same DB pool and check deadline.
Do not increase DB pool/timeouts or add positive permission/session caches.

Monitor low-cardinality `social.notification.delivery.lag`,
`social.notification.sse.active`, `social.notification.sse.pending` and
`social.notification.sse.closed{reason}`, plus existing durable retry/DEAD depth.
`social.notification.sse.abort.failures` must stay zero; inspect container lifecycle
if it increases, without logging credential-bearing request objects.
Metrics remain on the protected operational surface; do not enable anonymous
Prometheus or label by recipient, source ID, query text or token. Auth timeout and
capacity closures mean clients must fall back to REST and reconnect with backoff.
Reverse proxies must preserve streaming/disable buffering, bound connections and
avoid recording Authorization or query strings containing supplied secrets.

Notification replay is limited to 30 days; older DEAD events are acknowledged
without creating social activity. Do not rewrite an event, delete its receipt or
reset source versions to recreate old unread activity. Recover a recent DEAD
delivery through the existing restricted audited operator procedure, preserving
event ID and inspecting current owner source first. A stale source cannot grant
access. Groups expire after 90 days; inactive ordering survives at least120 days,
and active ordering survives group deletion. NOTIFICATION_RETENTION is a bounded
minute-keyed DB job. Unresolved DEAD work remains visible and retained.

`503 NOTIFICATION_SCAN_LIMIT` is explicit bounded-work exhaustion (10,000 groups/
contributions), never a partial unread badge. Inspect the recipient workload under
restricted operational access and define a reviewed capacity change. Do not bypass
source filtering or mark all future activity read to clear it. These are engineering
retention defaults, not a legal erasure or production readiness statement.
