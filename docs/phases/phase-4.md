# Phase 4 — checkpoint, not completion

**IN_PROGRESS, not READY_FOR_REVIEW or accepted.** Resume baseline `0193bd4`, clean
checkout/index; main accepted ADR-013 at `ab568554cf50cfa5c2024e84c90a16ed234a967c`.
Posts/privacy/revisions/basic moderation/secure images/avatar remain the full task.

Implemented prerequisites: exact pinned Silo in opt-in Compose and Testcontainers;
shared private/versioned/encrypted bootstrap, exact CORS, lifecycle and separated
credentials; SDK storage adapter for size/checksum presign, pinned HEAD/GET,
immutable retryable variant PUT, <=60-second access and bounded version/marker purge.
Network operations reject active transactions. AWS SDK v2 BOM 2.55.14 aligns modules;
S3 and explicit URL-connection transport implement the required real technical seam.
No fork SDK, broker or unrelated infrastructure dependency.

Content primitives now validate audience/scope, fixed CUSTOM recipients, plain body
(10,000 Unicode code points), unfetched HTTP(S) links, UTF-16 mention spans and
NFKC/ROOT hashtags. Nonempty checks include attachment count. Canonical ordinary
read/history policy covers current account/block/audience/community/member facts,
active-only suspension, archived reads and author departure; mute never grants/denies
read. These primitives are not wired to persistence/SQL/API yet.

No new migration, lifecycle use-case/worker/controller, publish/remove/restore,
decoder, avatar or OpenAPI route is delivered. V0001–V0014 and the accepted HTTP
surface remain unchanged. The storage adapter is not a Spring configuration bean.

Checks with explicit installed JDK 21:

- Fresh baseline `./mvnw -B -ntp verify`: 124 pass, no failures/errors/skips.
- Targeted StorageContractIT: 12 real Silo tests pass using Compose bootstrap/policies.
  Covers private/encrypted/versioned state, exact CORS, restricted credentials,
  checksum/signature/header denials, overwrite/pinned read, unsigned-trailer and
  Snowball invalid-signature paths, replication metadata isolation, version-only
  delivery, real URL expiry, all-version/marker cleanup, bootstrap replay and adapter
  immutable retry/transaction flag guard. Synthetic bytes do not prove image decoding.
- Targeted PostPolicyTest/ArchitectureTest/PersistenceBoundaryTest: 14 pass, including
  looped personal/community denial matrices. No SQL/object parity or DB race claim.
- Full `./mvnw -B -ntp spotless:apply verify`: **142 pass** (35 unit/architecture,
  107 integration: prior 95 PostgreSQL/Mailpit tests plus 12 Silo), no failure/error/skip.
  This is a checkpoint gate, not Phase 4 acceptance.
- Compose real startup/bootstrap and replay succeeded; formatting/config/whitespace
  checks pass. Testcontainers clean up their fixtures; local keys/data persist.

Initial test compilation/null-list issues and missing sed in the selected image
were fixed. A guessed replication-header rejection was replaced after source review
with verification of safe encrypted bytes. The genuine implicit GetObjectVersion
compatibility difference was reproduced and hardened with required version context;
the denial assertion remains. See [operations](../../ops/media.md). No denial is
disabled and no infrastructure check is skipped.

Still required: schema/owner APIs; guarded publication/edit/audience/delete and
attributable remove/restore; persisted immutable revisions/retention; SQL candidates,
hydration/parity and cursor/query/history APIs; quota/asset/binding states; external
prepare/effect/ack worker, isolated static JPEG/PNG/WebP processing, finalize CAS,
generation fencing, durable cleanup/reconciliation and avatar replacement/access.
Complete their HTTP/migration/privacy/concurrency/fault matrix and implemented
OpenAPI/docs/config, then targeted/full verify and separate code/handoff commits.

No unresolved external blocker at this checkpoint; remaining work is unimplemented,
not BLOCKED or complete. Production storage compatibility remains an external gate.
No Phase 5, agents, self-acceptance, push or deployment.
