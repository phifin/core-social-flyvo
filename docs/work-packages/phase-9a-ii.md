# Phase 9A-II — source erasure, media and strict completion

**ACTIVATED by main, 2026-10-20**, after G1 review `faf0ad8` accepted executable
`bbbde93c170246372895907dd4e446696521452d`. Local synthetic scope only.
Use a new user-opened chat: **`Develop Phase 9A-II — Sources, Media & Completion`**.
One writer; main reviews and alone may push. No agents or concurrent checkout work.

## Authority and prerequisites

Read the fixed mailbox first, then AGENTS, thread/git workflows, README,
implementation handoff, architecture/security/database contracts, roadmap9A,
[ADR-017 C01–C05](../adr/017-account-erasure-and-recovery.md), ADR-016,
[inventory](phase-9a-data-inventory.md), [full plan and T01–T20](phase-9a-plan.md),
and each affected owner subsystem/runbook. Inspect actual code before assuming
the older design inventory describes today's schema. Package I added V0033–V0037;
the R1/R2 repair adds no migration. Preserve its proof-chain and exact-request
receipt fences, bounded worker budget, default-off synthetic policy and archive.

This assignment implements **all II.1–II.4**, as three cohesive capability groups
with sequential internal gates below, not one tiny handoff per module. Commit
verified slices locally and continue through the assigned package. A genuine
architecture/security conflict requires a concrete decision request, not a silent
waiver. Do not activate III or production to demonstrate II.

Before coding, record a concrete implementation map: actual entry points, affected
owner tables/indices, lock sets/order, step/checkpoint shapes, proof invalidation,
API changes, migration order and test cases. Refresh inventory for new protocol
tables without deleting the historical 56-table baseline. No speculative module,
generic workflow engine, new dependency/DAG edge or foreign SQL consumer.

## Group A — participation and reference safety (II.1)

- Community: clear effective subject membership, cancel pending inviter/invitee
  admissions and subject request messages, retain archived-owner membership/FKs
  and terminal attributable history. Unexpected ACTIVE ownership is a visible
  blocker, never automatic transfer/archive. Check creation/transfer/join/accept/
  invitation/request/expiry paths against terminal status.
- Graph: bounded removal of inbound/outbound follow/block/mute/friendship and
  terminal pending requests under canonical pair locks. Preserve pair IDs and
  monotonic sequence; no old event/expiry may recreate a relation.
- Content references: remove current addressed mentions/CUSTOM recipients;
  atomically narrow the last-recipient audience to ONLY_ME. Preserve other
  authors' text, immutable revisions/reply references and shared community data.

Gate A: real-PG races in both winning orders, deferred FK/ownership constraints,
no audience widening, other-user preservation, bounded indexed reverse scans and
replay/expiry denials. Complete sorted account guard set must precede step and
community/pair/content locks; re-read after locking, no lock upgrades or N+1.

## Group B — authored bytes and physical media (II.2)

- Content: prompt author denial/soft deletion; t0+30d redaction in nested stages
  of at most100 collection items, not100 posts each loading unlimited descendants.
  Include body/link/revisions/comments/relations/alt text and terminal reaction
  choice. Preserve other authors' replies. Add an erasure-origin distinction so
  already queued ordinary PostRetention cannot erase those descendants. Keep
  revision UPDATE and reply_to immutability protections.
- Media: enumerate all owned assets, including FAILED/DELETED states and old
  generations. Verify external resource chunks before losing the last mapping.
  Wait for content/profile release, fence generations and old finalize/process
  commits, prove writer/capability drain, then purge all versions/delete markers
  and verify absence. External calls stay outside business transactions; stale
  ack cannot READY or release quota. Quota accounting is once-only.
- A periodic purge or elapsed lease is not writer quiescence. Existing Silo idle
  timeout does not prove a total write bound. Use explicitly controlled synthetic
  writers/deadlines for positive local tests; missing real drain evidence remains
  WAITING_EXTERNAL. No production configuration or fake runtime drain adapter.

Gate B: exact retention boundaries, >100 revisions/descendants, >50 object versions
and markers, interrupted listing, archive/storage outage, disabled storage with
existing assets, late accepted PUT/variant writes, crash after delete before ack,
stale lease/generation and once-only accounting. Use actual pinned Silo and real
archive. Measure fixed root/protocol rows separately from collection bounds;
the documented one-post+100-recipient atomic exception is not a bulk loophole.

## Group C — projections, preservation and finalization (II.3–II.4)

Install terminal write fences early; final projection proofs wait for all source
predecessors. Implement these capabilities sequentially within this group:

1. Notification/search: account guards before recipient/rebuild/document locks,
   revalidate captured snapshots, suppress terminal users even with absent index/
   source rows. Erase subject inbox/preferences, actor contributions and target
   residues while preserving other eligible actors/read state. Preserve V0032
   origin/decided-through semantics. Search blanks all subject USER/POST text and
   vectors; stale rebuild/live snapshot cannot repopulate an empty index. Feed
   remains source-authorized and gains no artificial persistent erasure step.
2. Moderation: bounded structural-root inventory for reporter/actor/assignee/
   target/source/parent relationships, with ADR-016 root→payload order. Preserve
   legitimate OPEN/held/unexpired private payload and key availability; report
   WAITING_CASE/HOLD/RETENTION. C01 requires existing pending-subject cases to
   remain reviewable/resolvable with mandatory encrypted detail, not metadata-only
   shortcuts. Sorted subject account guards and step invalidation precede case
   locks; new applicable payload invalidates any old completion proof atomically.
   Ordinary new roots for pending subjects and all new private payload for final
   DELETED subjects are denied. No premature redaction, forced dismissal or hold
   release to expedite completion.
3. Preservation exports: moderation owns its conservative independent PRESERVE
   intent before hold/renewal commit, external append outside locks, followed by
   current authority/version/root recheck. Preserve old hold on failure and export
   bounded baseline/control history; no account decryption/foreign SQL. An old
   backup cannot justify shortening known preservation. Import/restore is III,
   but II must deliver the truthful export side required by ADR-017 D08.
4. Account finalization: fixed12-step roster, eleven current prerequisite proofs,
   all resource receipts, exact request/generation/protocol/policy/round and
   nonrecursive certificate. Export outside locks; exclusively guard subject then
   ordered step rows, compare captured vector and atomically set completion step
   DONE plus account DELETED/audit/outbox. Changed vector rejects stale receipt;
   use a new immutable attempt. Replace I's negative DB guard only via a new
   migration with complete tested invariants; never disable constraints to pass.

Gate C: replay before/after30d receipt and120d source-state pruning; empty index,
live rebuild and snapshot races; closed SSE and old cursors/media URLs; C01 case
resolution after close and append-versus-finalization in both orders; held/365d
boundary and late structural association; preservation receipt outage/authority
change; stale certificate and audit/checkpoint/fenced rollback. A held account
must remain PENDING, and no-data accounts still require every owner proof.

## Cross-cutting integration and schema gates

- Reconcile historical pending/deleted subjects even without outbox/delivery
  history. Missing/corrupt provenance remains visible; never fabricate t0/version.
  Every newly enabled owner gets real bounded handler registration and residual
  checks. Unknown owners/protocol or disabled required storage cannot become DONE.
- Keep exact intent/request/execution/round matching from P9AI-R2 on all new
  exports/acks/failures. Preserve P9AI-R1 DB-time/inherited-deadline tests. New
  proof invalidation must not let an old round certify a successor.
- All new migrations start at **V0038** after inspecting HEAD. The older plan's
  V0037–V0042 suggestions are superseded: allocate consecutive actual owner files
  in implementation order and record their ownership. Separate owner changes;
  no empty reserved slots and no edits to V0001–V0037. III numbers are unallocated.
- Test fresh install, upgrade from accepted I/V0037 and original V0032, every new
  prefix/checksums and migration-failure rollback/forward recovery. Do not claim
  old I binary compatibility after terminal source shapes or finalization activate;
  keep service closed and forward-fix unless that exact pair is demonstrated safe.
- End-to-end synthetic fixtures cover all owner categories, historical requests,
  an unaffected user, archived owner, held case and active-ownership anomaly.
  At least10000-item skewed fixtures exercise posts/revisions/comments/edges/
  notification actors/assets as applicable. Record per-pass item/row/lock/query/
  memory/queue observations and restart convergence; no arbitrary latency SLA.

## Delivery, evidence and boundaries

Targeted tests per gate, then Java21 formatting and full `./mvnw -B -ntp verify`;
real PostgreSQL/Silo, architecture and published-view SQL/object-policy parity,
OpenAPI/admin CSRF/IDOR/If-Match/audit checks. No skipped Docker tests, exclusion,
pool/timeout/lease increase, new automatic retry budget or weakened negative.

Build/start an explicitly isolated app/media Compose project (never existing
developer volumes). Record fresh/upgrade native HTTP/security/progress probes,
actual runtime isolation, and cleanup/retention of synthetic resources. Run the
unchanged heavy pool10/diagnostic50 feed benchmark: all3200 requests, complete
distributions/failures and shared-resource effects. The500ms goal remains open.
No simultaneous heavy build/benchmark/verify; preserve every failed/interrupted
attempt and distinguish repeat evidence from prior phase evidence.

Update affected owner/API/schema/events/security/runbook contracts, inventory,
`docs/phases/phase-9a-ii.md` and an executable evidence manifest. Run pinned
redacted staged/full-history Gitleaks plus synthetic positive control. Preserve
existing exact exceptions; report any new finding to main, never broaden ignores.

Commit verified scoped implementation first, then separate mailbox documentation
with actual code hash, each gate's results/limitations and READY_FOR_REVIEW, next
owner main. Main alone accepts G2/pushes. Do not stop merely after Group A/B if
safe assigned work remains; hand back a concrete blocker with evidence and a
proposed bounded decision when authority or architecture genuinely prevents work.

Only isolated synthetic erasure tests are authorized. No real account deletion,
restore, provider contact, production retention/IAM/key/archive selection, release,
deployment, III/9B or public-launch claim. U01–U06 stay open. Unproven physical
writer drain, applicable private moderation bytes or missing independent receipt
must remain pending/failed, never be waived for a green end-to-end test.
