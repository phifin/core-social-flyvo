# Graph operations

Phase 3 uses PostgreSQL and the existing worker; no dependency or service added.
See [graph contract](../docs/graph.md) and [worker operations](community.md).

- FRIEND_REQUEST_EXPIRY jobs carry only a request UUID, dedup key that same UUID,
  availableAt its 30-day expiry. API checks expiry independently of cleanup.
- Existing WORKER_ENABLED/poll interval, 60-second leases, ten-attempt retry/DEAD,
  job fencing, queue gauges and failure counter apply unchanged.
- DEAD expiry repair follows the existing restricted audited worker procedure:
  inspect source, reset only that job's retry metadata if needed; do not extend
  request expiry or create replacement invitations/requests. Duplicate handling is safe.
- Graph pages/fact batches cap at 100. Opaque page cursors last 15 minutes and use
  the mounted platform encryption key with a separate purpose; rotation invalidates
  cursors and clients restart traversal. Never log cursor/token/actor payloads.
- Existing 16-KiB request body, CSRF/Origin/current Bearer and per-user write limits
  apply to all graph commands. Monitor pair lock contention/503 retries and queue
  lag before introducing finer locks or infrastructure.
- Pair revisions are retained across relation deletion; do not prune independently
  of the event replay horizon. Request/relationship erasure and production retention
  are Phase 9A gates. No production launch or live-provider readiness implied.


## Phase9A-II terminal relations

GRAPH_ERASURE locks sorted accounts, its step, then canonical pair advisory keys.
At most12 pairs/96 possible relation items are removed per pass; pair UUID and
monotonic sequence remain. Pending requests terminate, accepted history remains
pseudonymous. New inbound block creation is denied for pending/deleted targets;
removals and blocks of suspended targets retain their existing behavior. Expiry/
old delivery cannot recreate an edge or reset a sequence.
