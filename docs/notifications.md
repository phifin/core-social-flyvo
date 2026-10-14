# Semantic inbox — Phase 7 slice A

This slice delivers the durable REST inbox, not the Phase 7 SSE gate or phase
acceptance. [OpenAPI](api/openapi.yaml) specifies its implemented routes. The
activated [package](work-packages/phase-7-8.md) still requires B, C and D.

V0027 adds notification groups, active actor contributions, monotonic source_state,
explicit per-kind preferences and a recipient watermark. No rendered message,
private source body, credential or foreign SQL is stored/read by notification.
Content, graph and community publish bounded current routing/proof APIs, not HTTP
lookup bypasses. Profile attribution follows containing-source proof. All seven
kinds are implemented. Post mentions use existing explicit validated offsets;
comments route to post/root/addressed authors with REPLY over COMMENT precedence.
The route planner applies MENTION > REPLY > COMMENT for any same-event overlap and
suppresses self. Mentions cannot expand the current post audience.

The live `notification-inbox-v1` consumer joins the existing receipt/effect/fenced
ack transaction. It accepts v1 source facts; unsupported schema retries/DEAD under
the existing ten-attempt policy. Historical events older than 30 days are consumed
without social activity; operator replay cannot revive them. Notification delivery
does not promise replay of events before registration or indefinite backlog recovery.
No social email/push channel or new worker framework is introduced.

Reaction grouping uses recipient/kind/target/current processing UTC hour. Stable
source ID/version serializes type/delete/reactivate across groups. A type update
changes the contribution without activity/read increments. Deletion deactivates;
reactivation creates current-hour activity and deactivates the old contribution,
including when its intervening delete arrives later. Last activity uses monotonic
processing time. Other keys use kind/event ID. Receipt and source version both
prevent replay inflation. Current owner source, account, block/mute and preference
checks can hide a group/actor immediately while the worker is stopped.

All mutators serialize the recipient row first, then source_state/group/contribution;
multi-recipient routes take recipient UUID order. This avoids reverse watermark/
group acquisition between read commands and projection. REST mutations take the
shared account guard before recipient. Projection owner inspections are bounded
read queries, not source locks or network calls. Cross-module SQL allowlist remains
content-only. Source APIs use current canonical content SQL policy in one statement.

Inbox scans at most 1,000 group candidates, in batches of 100. Actor proof scans
100 contributions at a time across the whole group batch, with at most 10,000 per
request. Counts are distinct currently eligible actors; at most three names are
returned. Unread/bulk-read scan up to 10,000 groups and contributions using bounded
memory, and return explicit `503 NOTIFICATION_SCAN_LIMIT` if exhausted; never a
truncated/unfiltered badge. No hidden total is returned. A cursor binds viewer,
size, initial cutoff and 30-minute expiry with the existing platform encryption
primitive. Activity can move an existing group ahead of a cursor; refresh recovers
it. Pagination is weakly consistent and may return an empty continued page.

Read advances only through the supplied observed activityVersion (future version
is 400). Bulk read captures current eligible rows/versions before recipient locking;
updates use those versions, leaving new activity unread. Preferences atomically set
typed in-app kinds; security email is independent. Recipient UUID never comes from
request JSON or paths. IDOR and hidden groups receive 404.

Groups disappear from live scans after 90 days and bounded retention removes at
most 100 per recipient/20 recipients per job. Contribution cascades are strictly
inside the notification aggregate; source ordering survives group removal. Active
source_state is retained; inactive state is pruned only after 120 days and no group
reference. The durable minute-keyed NOTIFICATION_RETENTION job uses the existing
DB-only worker and retry/DEAD semantics. These are engineering defaults, not a
legal erasure/compliance claim. Monitor projection lag and explicit scan-limit
failures; large unread sets require an operationally reviewed capacity decision.
