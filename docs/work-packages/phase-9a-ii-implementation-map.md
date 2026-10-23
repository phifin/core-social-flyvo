# Phase 9A-II implementation map — development working record

Baseline: `6162303` (accepted executable `bbbde93`), clean `master` index and
worktree at start on 2026-10-20. The last applied source migration is V0037.
Only isolated synthetic erasure is in scope. This map is the pre-code gate plan;
actual evidence and any refinement must be recorded in the phase record/mailbox.

## Shared protocol and lock map

`AccountErasure`/`Erasures` owns the fixed twelve-step roster, request, step,
resource chunks and independent archive exports. Owner `JobHandler`s use the
existing `Pass` JSON and `begin`/`advance`/`recordResources` contract. The account
reconciler scans pending and deleted subjects even when event/outbox history is
absent. Enable a step kind only alongside its real registered handler and bounded
residual proof; unknown owner/protocol stays waiting. Carry P9AI-R1 DB-time and
P9AI-R2 exact request/generation/round/execution receipt checks into every new
external ack. `ErasureCertificate` supplies the nonrecursive eleven-proof vector;
the final account transaction alone sets completion DONE and DELETED.

Every DB pass takes the dispatcher job fence, all pre-read account guards in UUID
order (subject included), then the account step, and only then owner locks. Re-read
the selected batch after the guards. No lock upgrade. Community rows are UUID
ordered; graph pair advisory keys are canonical/sorted; content takes post then
comment, then media quota/asset; moderation takes root then payload; notification
takes recipient then source/group/contribution; search takes rebuild/document.
External archive/Silo operations use prepare, outside-transaction effect, fenced
ack transactions. The 100 limit counts collection items, with fixed root/protocol
rows measured separately. A complete post and its unbounded children are never a
single item.

## Gate A: community, graph, content references

Community uses `CommunityAccess`, `CommunityLifecycle`,
`CommunityAdmissions`, `MembershipLifecycle` and `JdbcCommunityRepository`.
Inventory `community.community` ownership, `membership`, `invitation` (invitee
or inviter) and `join_request` by subject. Preserve archived owner and terminal
attribution; active ownership is WAITING/FAILED for operator review. Page each
reverse index by immutable ID, at most 100, and erase request messages while
terminating pending admissions. Check owner creation/transfer, join, accept and
expiry after the same account guards. Completion proves no effective membership,
pending admissions/messages or active ownership.

Graph uses `Relationships`, `GraphRepository`/`JdbcGraphRepository`, canonical
pair locks and graph relation/request sequence rows. Page both endpoints without
an unbounded pair list. Remove follows, blocks, mutes, friendship and pending
requests while retaining pair ID and monotonic sequence; expiry/event replay must
not recreate them. Content's reference stage uses `Posts`/`JdbcPosts` and the
existing recipient/mention reverse indexes. It removes only current references to
the subject. The final CUSTOM recipient removal and audience switch to ONLY_ME
must be one post transaction; revision snapshots, others' text and reply_to IDs
remain immutable.

First migration is V0038. Subsequent migrations are allocated consecutively only
when an owner actually needs a schema/index/guard change. Gate A targets owner
reverse indexes and durable cursor/terminal-shape guards; no placeholder version.
Gate A tests both winner orders for close versus admission/transfer/graph/content
writes, replay/expiry, archived owner, deferred FK/CUSTOM audience, >100 reverse
items, another user's preserved bytes and indexed bounded scans.

## Gate B: authored content and media

Content's owner handler builds on `PostRetention`, `CommentRetention`,
`ContentInteractions`, `PostRepository`, `InteractionRepository` and media's
published `MediaCommands`. Separate prompt post/comment soft deletion and reaction
type erasure from t0+30d post/revision/recipient/mention/media/hashtag/comment
redaction. Use stages and keyset cursors over each collection at most 100 items;
release each owned binding through media before removing its last owner mapping.
Add an erasure origin to the post-retention path so a queued ordinary retention
job cannot redact another author's descendant. Preserve revision UPDATE and
reply_to immutability, and keep reaction IDs/versions as one-way tombstones.

Media's owner handler builds on `Images`, `ImageCleanup`, `ImageWork`,
`MediaRepository` and `ObjectStorage`. Inventory every owned asset state with
keyset scans; export asset-ID chunks before loss of mapping. Wait for profile and
content release. Fence old process/finalize generations, prove controlled writer
drain beyond lease expiry, then list/purge every version and delete marker under
`incoming/<id>` and `variants/<id>/`, verify repeat empty listing, and ack once
under current request/step/job/asset generation. No production drain adapter is
assumed. Missing positive drain/storage evidence means WAITING_EXTERNAL, including
disabled storage with existing assets. Keep once-only quota decrement and durable
minimal purge certificate.

Gate B tests exact 30-day boundaries; >100 revisions/descendants; >50 Silo
versions/markers; listing interruption, storage/archive outage, late accepted PUT
and variant write, stale generations/leases, crash after delete before ack, and
once-only accounting on actual isolated PostgreSQL/Silo/archive.

## Gate C: derived state, moderation and completion

Install terminal guards before projection materialization. Notification uses
`NotificationProjector`/`JdbcInbox` with sorted actor/recipient account guards
before recipient rows, then deletes subject inbox/preferences/actor contributions
and target residues while retaining V0032 ordering/decision watermarks and other
actors' read state. Search uses `SearchProjector`, `SearchRebuildWorker`,
`SearchAccountRepair` and `PostgresSearchIndex`; live and rebuild snapshots take
owner account guards before rebuild/document locks. Blank subject USER/POST text
and vectors even if old index/source rows are absent; stale captured snapshots
cannot repopulate them. Feed relies on current source authorization, with no new
durable step.

Moderation uses `ReportWorkflow`, `CaseWorkflow`, `ModerationRetention` and the
owner case/action stores. Page structural report/action roots by reporter, actor,
assignee, target, source and parent at most 100; do not decrypt arbitrary foreign
records to find names. Observe ADR-016 root-to-payload retention and C01: an
existing subject case remains reviewable with mandatory ciphertext. Unresolved,
held and unexpired payloads yield WAITING_CASE/HOLD/RETENTION. New applicable
payload invalidates an old completion proof while account guards precede case
locks. Moderation writes an independent PRESERVE intent before a hold/renewal
commit, appends outside locks and rechecks root/version/authority on ack; failed
append leaves prior preservation effective.

Account completion requires all eleven current step proofs, all resource receipts,
exact request/generation/protocol/policy/round, and a certificate excluding itself.
Export outside locks; subject exclusive then ordered step locks compare the captured
vector and atomically commit completion DONE, account DELETED, audit and event.
Replace V0033's negative guard through a new account-owned migration only after
these invariants are executable and tested. A held account stays PENDING.

Gate C tests old event replay after 30/120 days, empty-index rebuild, SSE/cursor
and media URL denial, case append versus finalization in both orders, C01 case
resolution, 365-day/hold boundaries, preservation receipt outage/authority change,
stale certificate and rollback of all final side effects.

## Delivery gates

For each gate: targeted real-PG integration/race tests and measured large skewed
fixtures (at least 10,000 collection items where applicable), then a coherent
verified local slice commit. At package end: migration fresh/V0037/V0032 and
prefix/checksum/rollback checks; formatting and full Java 21 verify; architecture,
OpenAPI/admin security and SQL/object policy parity; isolated native app/media
probes; unchanged 3,200-request pool10/50 benchmark; pinned staged/history
redacted Gitleaks and positive control. Preserve failed attempts. Update owner,
schema/API/security/runbook/inventory/phase docs and durable synthetic evidence.
Commit code and feature docs first, then mailbox with its real hash, checks,
limitations, READY_FOR_REVIEW and next owner main. No push, III, or real-data run.


## Actual implementation refinements

The complete package is one cohesive code delivery after the three internal gates;
failed targeted attempts were repaired before continuing. No intermediate module
handoff or new dependency was needed. The twelve fixed handlers and actual owner
stores use the existing dispatcher and account API, not a second workflow engine.

Actual migration allocation (all consecutive, earlier checksums preserved):

| Migration | Owner/change |
|---|---|
| V0038 | Community inviter reverse index |
| V0039 | Content origin and one-way erased reaction choice |
| V0040 | Media claim/accounting/purge tombstone metadata |
| V0041 | Content all-state author keyset |
| V0042 | Notification terminal recipient and reverse scans |
| V0043 | Media live/claimed/purged shape |
| V0044 | Search permanent blank erasure marker |
| V0045 | Account C01 moderation/completion proof invalidation |
| V0046 | Moderation structural indices and two independent preservation tables |
| V0047 | Account eleven-proof certificate/final status guards |
| V0048 | Account missing-JSON/NULL-safe guards and invalidation transitions |
| V0049 | Content terminal comments and root-locked relation insert fences |
| V0050 | Account resource owner/kind allowlist |
| V0051 | Media claimed-asset variant metadata insert fence |

Later corrections use later migrations after local application. No V0001–V0037
checksum changes or reserved III numbers. The I binary is not compatible with II;
partial prefixes keep ingress closed and use forward recovery.

Graph's fixed page is12 pairs (at most96 relation collection items), retaining its
pair ID/sequence roots. Content stages separate all child collections; the one-post
plus100-recipient audience repair is the only documented atomic exception. Media
also prunes old-generation variant rows100 at a time after claiming; its verified
asset-ID manifest retains the purge mapping, and unknown writers still prevent
quota release/proof. No runtime drain adapter is supplied.

Preservation has a short prepare/external/recheck hold boundary. Disabled-policy
calls preserve the existing ambient transaction contract; enabled-policy calls
require the outside-transaction export boundary. ADMIN PRESERVATION resumes DEAD
exports explicitly and leaves immutable attempts/failures visible. No automatic
retry budget increase or restore/import implementation is introduced.

Actual checks, large-fixture counts, failed-attempt explanations, executable
manifest and operational limitations are in [the phase record](../phases/phase-9a-ii.md).

Reference repair counts repeated mention rows and the subject recipient together
(max100), rather than counting one post with many offsets as one item. Soft-post
prefetch uses9 roots: the existing position0..9/unique constraint bounds their
complete root+attachment collection to99 even before the mutation budget applies.
The skewed current-mention regression exercises10,001 historical offset rows and
last-recipient narrowing without changing another author's body.
