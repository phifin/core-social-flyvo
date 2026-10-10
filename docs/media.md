# Media lifecycle and object storage

Images are in the first usable content phase. Use an S3-compatible object store, AWS SDK for Java v2 adapter, private quarantine and delivery prefixes/buckets, versioning enabled, server-side encryption and least-privilege credentials. The Phase 4 local Compose/Testcontainers distribution is pinned PGSTY Silo, a MinIO-derived fork selected in [ADR-013](adr/013-local-object-storage-distribution.md); its runtime/security contract must be tested during implementation. Compose adds it in a `media` profile. Validate exact presigning, checksums, versioning and delete behavior against the chosen production S3 implementation before launch. Do not store binaries in PostgreSQL or assume every S3-compatible server has identical behavior.

## Upload sequence

Phase 4 implements this sequence, including source-authorized post/avatar access,
real Silo processing and durable recovery. [Phase evidence](phases/phase-4.md),
[implemented OpenAPI](api/openapi.yaml) and [operations](../ops/media.md) distinguish
local checks from production storage/ingress gates. No owner draft-preview signing
endpoint is exposed.

1. Authenticated verified user requests upload with purpose POST/AVATAR, declared MIME, bytes and SHA-256 checksum. Enforce rate/quota (initial 10 MiB/image, 40 megapixels, 10 attachments/post, 100 MiB pending upload quota/user, 1 GiB retained media/user configurable). Reserve quota and insert UPLOAD_PENDING asset. ID and quarantine key are backend-generated; original filename is metadata at most, never an object key.
2. Return presigned PUT URL expiring in 10 minutes plus required content type/checksum headers. Bucket CORS allows only configured frontend origins. A signed declared size/header is not relied on as the sole upload limit: reject oversized actual objects, configure ingress/storage controls, and account reserved bytes so uploads cannot bypass quotas. Prefer signed POST length conditions if the selected compatible store/SDK supports them consistently; changing transport does not change final validation.
3. Client uploads directly to quarantine, then calls finalize with asset ID and upload result/version identifier. Owner-only finalize probes trusted storage HEAD outside a transaction, checks expected key/version/size/checksum and performs state/version CAS in a short transaction to UPLOADED. No arbitrary external URL or key from the client is trusted.
4. Persist `MediaUploaded` plus durable processing job in that transaction. Return 202 with status URL. Duplicate finalize for the same verified version is idempotent. A competing/different version conflicts; no second unbounded processor run.
5. Worker claims with a lease and fencing generation, streams the **pinned object version** under strict byte/time/decode limits, verifies actual format/magic bytes, dimensions and checksum, decodes and re-encodes images, strips EXIF/location metadata, rejects malformed/polyglot/unsupported files, and produces display/thumbnail variants. Initial types JPEG/PNG/WebP, static images only; SVG/GIF/archives/executables rejected. Do not serve originals. Run processing with memory/CPU/time limits and no unnecessary network credentials. New file/document formats require malware scanning before release; do not enable them via MIME allowlist alone.
6. Write immutable variant keys including processing generation. Commit READY and metadata only if the worker still owns that generation. Partial outputs of failed/stale workers become cleanup candidates; only the committed READY generation is served. Retry transient storage/worker failures, terminal validation failures become REJECTED. Unsupported files never briefly become downloadable.
7. Publish post/update avatar calls `MediaCommands.bind` inside its own transaction. Check owner, intended purpose, READY state and no prior binding, lock all asset IDs in order, insert binding and owner-module FK references. Never trust media IDs just because upload was authenticated. All attachments bind or the post transaction rolls back.

```mermaid
stateDiagram-v2
  [*] --> UPLOAD_PENDING
  UPLOAD_PENDING --> UPLOADED: verified finalize
  UPLOAD_PENDING --> EXPIRED: upload TTL
  UPLOADED --> PROCESSING: durable job claimed
  PROCESSING --> READY: validated variants committed
  PROCESSING --> REJECTED: invalid content
  PROCESSING --> FAILED: retry budget exhausted
  FAILED --> PROCESSING: operator retry / new generation
  READY --> DELETING: unbound expiry or released context
  EXPIRED --> DELETING
  REJECTED --> DELETING
  FAILED --> DELETING: terminal retention expiry
  DELETING --> DELETED: storage deletion verified
```

READY is processing state; binding is a separate orthogonal record. Attached media remains READY until release. No optimistic “ATTACHED means safe to process” conflation.

Quota checks serialize on a per-owner media quota row before locking assets; a sum-then-insert query without a guard is insufficient. Accounting and storage lifecycle controls are specified in [database design](database-design.md#media). Generated variants count toward retained bytes. Binding does not charge a second copy. Concurrent uploads cannot each reserve the same remaining allowance.

## Overwrite, retries and failure safety

A presigned URL is a reusable bearer capability during its lifetime and may overwrite a key. [Amazon S3 presigned URL documentation](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html). Therefore quarantine versioning/pinned versions are mandatory: worker never validates one version then serves another mutable key. Final URLs reference immutable processed versions, not the upload URL/key. Retry and duplicate-finalize tests must upload a later version after finalize and prove it cannot alter delivered bytes.

Object storage is not in the SQL transaction. Uploaded objects with no committed READY row stay quarantined; outputs without a committed row are orphans; a database rollback cannot make a partially uploaded file public. Use deterministic generation keys, idempotent operations, leases and reconciliation rather than distributed transactions. Storage failure during finalize leaves state retryable. Worker crash after writing variants but before commit reuses/verifies outputs or rewrites a fresh generation; stale generation cannot commit readiness.

Cleanup uses database-driven state claims and storage listing reconciliation. Pending intents expire after 15 minutes; READY unbound assets expire after 24 hours. Claims use row lock/CAS to DELETING before external delete so attach cannot race cleanup. Processing leases have bounded retries before terminal state; never delete a live processing generation based solely on created time. Delete all object versions/delete markers where policy requires erasure; deleting only the latest version is insufficient. Retry DELETING jobs until deletion is confirmed, then mark DELETED. Keep tombstone metadata for reconciliation without serving content.

Post deletion/removal immediately denies media URL issuance. Soft-deleted post bindings are released/purged after the content retention window; moderation hold may retain private evidence. Avatar replacement releases the old binding in the same transaction; purge runs later. Reconciliation finds context references without matching binding and fails them closed, emitting an alert; it does not infer permission from storage existence. Owner-only metadata status endpoint does not return bound download URLs. An uploader cannot bypass post removal via `/media/{id}`.

## Download authorization and future scope

Content/profile endpoints first authorize current resource access, then ask media for READY variant capabilities, max 60 seconds. Media has no dependency on content/profile: the owning module is the orchestrator, and there is no public “sign any media ID” API. Non-bound owner preview may use a separate owner-only capability for READY drafts, never rejected/quarantined content. Application responses use `Cache-Control: private, no-store` for signed URLs. Storage/CDN rules must not extend private capability TTL or expose public fallback paths. Previously issued URLs/bytes remain accessible until expiry/already downloaded; strict immediate byte revocation would require an authenticated delivery proxy, a future explicit tradeoff.

Thumbnails (e.g. max 320px) and display images (max 2048px) are generated together initially, preserving aspect ratio. Exact quality/dimension defaults are configuration after visual/size evaluation. Video later adds multipart upload, duration probing, isolated transcoding, poster frames, multiple renditions and significantly different quotas/lease times. Documents add antivirus/content-disposition policies and no inline active content. These add processors/kinds without changing post ownership or audience.

## Implemented Phase 4 refinements

- The only new decoder dependency is TwelveMonkeys `imageio-webp` 3.15.3 (pure
  Java static WebP support; JPEG/PNG use JDK ImageIO). Both output variants are
  fresh PNGs, without copied metadata, max sides 2048/320, no enlargement. Original
  orientation metadata is stripped, not copied into output. The strict container
  parser checks PNG CRC/end, JPEG segment/scan/end and WebP RIFF/chunks; rejects
  animation, trailing/polyglot data, format mismatch and dimensions over 40 MP
  before pixel allocation. Input and each output are capped at 10 MiB.
- Decoder runs in a separate headless JVM, 384 MiB heap, one reported processor,
  cleared environment and a 15-second total IPC/process deadline. It does not load
  Spring or storage credentials. Compose caps the complete app/child cgroup at
  1 GiB/two CPUs. This is resource/credential minimization, not a claim of an OS
  network/filesystem sandbox; production must apply its execution/egress isolation.
- `app.media.limits.pending-bytes`, `retained-bytes`, `uploads-per-minute` configure
  accounting defaults (100 MiB, 1 GiB, ten/minute). Initial pixel/input/output and
  resize safety ceilings are reviewed fixed defaults, not request-controlled.
  Retained accounting includes both variants and is released only on confirmed
  all-version purge. Quota reconciliation runs hourly, twenty owners per job.
- External jobs have short fenced claim/prepare/ack transactions and out-of-TX
  effects. Every generation schedules a durable orphan check; READY schedules
  quarantine purge after upload expiry and unbound cleanup after 24 hours.
  Retry is bounded to ten attempts; expired final leases become FAILED/DEAD rather
  than stuck PROCESSING. FAILED allows a 24-hour restricted operator repair window.
  DELETING/DEAD retains quota until confirmed deletion; replay never resets generation.
- Owner modules schedule binding checks with source transitions. A missing/wrong
  context binding already denies access synchronously; checks emit attributable
  technical `MEDIA_BINDING_MISMATCH` audit alerts, never infer access from storage.
  Restore drills can enqueue these checks per bounded source batch.
- Released edit history remains private for 30 days. Removal denies ordinary and
  history access immediately and privately holds its images for 30 days; restore
  after this window restores text, not purged image bytes. There is no indefinite
  evidence hold/report workflow in Phase 4. Avatar replacement releases immediately.
- Compose/bootstrap pin ADR-013's exact digest and mount individual secret files
  only. Separate internal SDK and browser presigner endpoints are required when
  the application runs inside Compose. Bucket quotas and lifecycle are defense
  in depth, not a synchronous hard disk/adversarial overwrite budget; see operations.
