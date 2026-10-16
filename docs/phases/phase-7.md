# Phase 7 — semantic notifications and authenticated SSE

Status: **CHANGES_REQUESTED by main, 2026-10-17**, P78-R1 open: initial notification
activity can be lost under delayed/reordered updates. See the [mailbox](../agent-handoff.md)
for exact code paths and regression assignment. No phase acceptance.

Historical delivery: implemented; internal gate passed2026-10-15. This is slice B
evidence within the activated [Phases 7–8 package](../work-packages/phase-7-8.md),
not a handoff or authorization for Phase9. A commits: `beebb0a` (envelope) and
`ecb11f6` (inbox). Verified B hash will be recorded in the fixed mailbox after
the code commit. Main alone reviews/accepts phases.

## Delivered scope

V0027 adds semantic groups, actor contributions, serialized versioned source
state, per-kind preferences and recipient watermark. All seven kinds are routed
through current content/graph/community owner proofs, batched account/graph facts
and authorized profile attribution. Mentions use the existing explicit post
relation; comments have no mention relation and handles are never inferred.
Self is suppressed; routing precedence is MENTION > REPLY > COMMENT. Current
preferences, actor/account state, block/mute, target visibility and request/invite
expiry apply even with delivery stopped. A replacement friendship cannot revive
the notification for a different, earlier request.

Reaction identity/version survives type changes/delete/reactivate across UTC
hours. Type change does not create unread activity. Reordered deletes/creates and
reactivation before its preceding delete are tested. Read/bulk read use observed
versions and leave concurrent activity unread. Receipt, effects and fenced ack
remain atomic in the existing worker. The immutable envelope preserves stored
schema/source versions, sequences, correlation and nullable historical causation;
no historical metadata is fabricated.

REST uses bounded keyset scans and encrypted viewer/size/cutoff cursors. Unread
and bulk read refuse oversized exact scans with503 rather than returning partial
counts. Current eligible actor count and at most three summaries are returned;
no stored body/rendered message or unfiltered fallback. Recipient IDs are never
client-selected. Retention is90 days for groups,30-day accepted replay, at least
120 days for inactive unreferenced ordering; active ordering survives group purge.
No security-email preference, social push/email, foreign SQL consumer or broker.

SSE uses ADR-015's narrow verified actor/expiry and current-session APIs. Actual
exp, not skew allowance, bounds lifetime. Five-second current checks plus a
three-second deadline include queueing/unavailability. Default2/user and100/global,
four shared check threads, one timer and two-frame nonblocking queues. Frames
contain generic invalidations/random IDs/comments only. Periodic current-source
repair covers policy changes that do not change the notification watermark.
Disconnect/lost hints cannot lose the durable REST inbox. Source/account policy
is never cached positively, and sockets retain no DB transaction/connection.

See [notification contract](../notifications.md), [operations](../../ops/notifications.md),
[plan/complete intermediate failures](../work-packages/phase-7-8-plan.md) and
[OpenAPI](../api/openapi.yaml) for exact bounds, routes and failure codes.

## Verification and measured limits

Environment: Java21.0.12.1, Boot4.1.1, Tomcat11.0.27 patch, Docker29.8.2,
PostgreSQL18.6 pinned digest; i7-1165G7/8 logical CPUs, about7.5GiB RAM/4GiB swap,
shared local development host. No production capacity/SLA claim. Existing pool
sizes and acquisition/statement timeouts are unchanged.

Checkpoint `.local/evidence/p78/phase7-targeted.log`:110 PASS (14 fast +96 PG),
no errors/skips. Includes all19 feed regressions,18 foundation,30 authentication,
3 envelope,1 shutdown,15 inbox and10 stream tests before the later regressions.
Subsequent full native-abort verify:298 PASS (54 fast +244 integration), no
failures/errors/skips. It includes16 inbox,12 HTTP stream executions (three slow
reader repetitions), standalone real open-stream shutdown, native-buffer deadline
and late-error unit regressions, fresh/upgrade27 migrations with prior checksums
and data preserved, OpenAPI, architecture and all accepted Phases1–6 tests.

That full run still forced its JVM to exit at30s. The dump locates Hikari shutdown
waiting for cached test pools whose Testcontainers DBs had already stopped,
not a Tomcat stream leak. The three new cached test contexts now evict after
class. Final `.local/evidence/p78/phase7-gate-final.log` passed all298 tests in
5:59, without forced JVM exit. Spotless, Enforcer, OpenAPI and architecture pass.
The intervening `phase7-gate.log` compile failure was an incorrect test annotation
import, corrected before this final run; it is not a passing checkpoint.

Measured on the final298-test run:

| Case | Observed |
|---|---:|
| Logout / own revoke / logout-all / suspension | 5,029 /5,076 /5,083 /5,093ms |
| Actual token expiry | 2,417ms after connection; retained DB connections0 |
| Paused real PostgreSQL | fail-closed8,074ms; ordinary REST recovers after unpause |
| Slow reader, three repetitions | 363 /239 /223ms; queue≤2, native counters0 |
| Context close with live five-minute token | 54ms (five-second regression bound) |
|100 reactions /25 posts /4 actors, worker drained per command | max lag17.352ms; total1,791ms |

The last workload measures synchronous DB projection/drain costs, **not**
scheduler burst throughput or a production delivery SLA. SSE checks are bounded
fail-closed, not a promise every stream remains open at configured maximum load.
An50-group inbox regression asserts one batch owner proof, not one call per row.
Exact unread/bulk scan exhaustion is a named denied case, not silently truncated.

## Failures retained and fixes

Earlier full verifies failed native slow-reader teardown despite app streams0.
An application late-error regression first failed (native complete wanted twice,
observed once); terminal release is now idempotent but later native error cycles
still complete. This alone did not fix native resources.

IntelliJ conditional logpoint captured Tomcat11.0.24 recycling `COMPLETING`,
error `NONE`, through ConnectionHandler.release, with native async count1 and
zero sockets/waiting processors. The compatible11.0.27 patch handles the late
write-notification/completion race; patch-only verification still failed1/3 slow
readers. A stream-only existing-container abort hook now prevents blocked final
flush before backpressured completion, serialized against terminal callbacks.
Native buffered work keeps the same unsent deadline even with an empty Java queue.
Missing adapter fails SSE503; ordinary REST remains independent. Container changes
need an equivalent verified adapter, not a silent portable-completion assumption.

The standalone open-stream shutdown regression failed its5s bound before moving
SmartLifecycle stop ahead of Tomcat's graceful drain, then passed18–29ms. It owns
its context explicitly; latest final run54ms under heavier host contention. Manually closing a Spring TestContext-managed context
inside a test conflicts with restart callbacks. Every native HTTP test asserts
both async request count0 and servlet allocations0, not just the application map.
All agent debugger probes/sessions are removed; user breakpoints are unchanged.

Logs in ignored `.local/evidence/p78/` preserve failed full gates, failed unit/
shutdown regressions, patch-only failure, focused native-abort PASS and final
full-suite attempts. Final package evidence must additionally record app/media
Compose, heavy pool10/50 feed benchmark and current-history/staged secret scans.
No push, phase acceptance, deployment or Phase9 work is authorized.
