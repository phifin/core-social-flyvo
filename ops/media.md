# Phase 4 local images and recovery

Posts/privacy/revisions/basic moderation/images/avatar are implemented; verification
and independent review state is in the mailbox. No self-acceptance or production
readiness is implied by this local setup.

```bash
bash ops/local/generate-keys.sh
APP_UID="$(id -u)" APP_GID="$(id -g)" docker compose --profile media up -d --wait silo
APP_UID="$(id -u)" APP_GID="$(id -g)" docker compose --profile media run --rm silo-init
# Host JVM, after PostgreSQL/Mailpit startup:
MEDIA_ENABLED=true STORAGE_ENDPOINT=http://localhost:9000 STORAGE_PUBLIC_ENDPOINT=http://localhost:9000 \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# Combined HTTP/worker container, after Silo/bootstrap:
APP_UID="$(id -u)" APP_GID="$(id -g)" MEDIA_ENABLED=true \
  docker compose --profile app --profile media up -d --build app
```

The generator preserves existing secrets and creates `.local/storage-data` owned
by the developer. Use that non-root UID/GID. Silo has a read-only root filesystem,
bounded tmpfs/resources and loopback-only ports 9000/9001. `.env` may override
STORAGE_PORT/STORAGE_CONSOLE_PORT and exact HTTP(S) FRONTEND_ORIGIN without a path.
Change frontend origin and CORS together, then rerun bootstrap. No wildcard/public
object policy. Preserve `.local/storage-data` and all `.local/secrets/storage-*.b64`,
especially the KMS key: replacing it can make encrypted objects unreadable.
`docker compose --profile media stop silo` stops without deleting data/keys.

Shared Compose/Testcontainers bootstrap uses the exact accepted ADR-013 digest,
bundled mcli, private/versioned SSE-S3 quarantine/delivery buckets, two-day current
and noncurrent quarantine lifecycle and separate social-admin/social-api/social-worker
credentials. API cannot list/delete storage; worker cannot upload quarantine.
Neither may administer storage or operate outside its prefixes. Never use admin
credentials in the application; production requires TLS/managed KMS/restricted roles.
Both buckets have 10 GiB local quota. Its storage scan is asynchronous, **not a
synchronous hard disk cap**. Reusable signed PUTs can accumulate versions before
expiry; DB quotas, signed size/checksum, pinned finalize/read, expiry purge, bucket
quota/lifecycle and loopback isolation are defense in depth. Strict physical storage/
ingress limits and adversarial overwrite budgeting remain production gates; do not
expose this developer node to untrusted users.

Local media defaults off; upload then fails 503 STORAGE_DISABLED, never mock READY.
Compose SDK endpoint defaults to http://silo:9000; browser presigner defaults to
http://localhost:9000. Host JVM uses localhost for both. Enabled non-local deployment
requires explicit endpoint/runtime secret files. Individual mounts: app has JWT
private/public, mail, limiter and API/worker runtime keys (same process runs worker);
Silo has admin/KMS; bootstrap has admin/API/worker. No directory-wide secret mount.
Use the developer UID/GID and preserve existing 0600 files. App and Silo each have
1 GiB/two-CPU cgroup bounds. Decoder heap is separately 384 MiB, cleared environment,
15-second total deadline and one reported processor; it never loads Spring/storage
credentials. It still has its UID's filesystem rights, not an OS sandbox. Production
must apply process/filesystem/egress isolation and separate secret access.

Compatibility: the pinned Silo policy library makes GetObjectVersion imply
GetObject ([tagged action mapping](https://github.com/pgsty/silo-pkg/blob/v3.14.1/policy/actionset.go)). A real denial test initially reproduced unversioned delivery GET
succeeding. Adding a non-null `s3:versionid` condition restored denial; pinned GET,
outside-prefix denial and actual capability expiry pass. This is policy hardening,
not a relaxed contract or replacement of ADR-013. Verify production IAM separately.

S3ObjectStorage uses distinct bounded API/worker clients and an API presigner.
Uploads sign MIME/size/SHA-256; HEAD/GET require explicit versions; immutable output
PUT verifies exact metadata on retry; delivery TTL is <=60 seconds. Network methods
reject active transactions. Spring lifecycle/worker integration uses the configured
real adapter; no public arbitrary-key signing endpoint exists.
Purge deletes at most 50 versions/markers per bucket per call, checks
known deleted versions with HEAD and relists. Truncation returns false; callers must
durably retry. A 20-second work deadline is checked between calls; in-flight calls
remain bounded by SDK timeouts (connect three seconds, socket five, API ten/attempt
seven). The durable cleanup worker retries incomplete purge, keeps DELETING/quota
and marks DELETED only on a fenced confirmation. Query-credential/no-Authorization
unsigned trailers return 400 InvalidRequest (unsupported auth mode); bad header
signatures return 403 SignatureDoesNotMatch. Well-formed SDK controls succeed,
including nonempty Snowball extraction; denied cases create neither versions nor
extracted entries. Tampered presign retains all other signed headers.

## Accounting, queues and alerts

`app.media.limits.pending-bytes`, `retained-bytes`, `uploads-per-minute` default to
100 MiB, 1 GiB total pending+retained accounting and ten/minute. Input/each output
10 MiB, 40 MP and display/thumbnail 2048/320 are fixed reviewed safety defaults.
Worker polling/enablement, queue/DEAD depth, oldest pending age and failure counters
reuse platform observability. Inspect safe asset state/generation/failure_code and
job ID/kind/status/attempts/lease_generation/last_error_code, not private content,
presigned queries or keys. One external job/process/tick; ten attempts with bounded
jittered retry. Expired final processing claims become FAILED/DEAD.

Per-generation orphan jobs preserve current READY output. Full and quarantine-only
purge wait until persisted upload expiry plus fifteen-minute settling, even after
owner DELETE or early image rejection. A timing wait defers the same durable job
without burning attempts; DELETING/reservations stay charged until confirmation.
The hourly MEDIA_STORAGE_RECONCILE keyset sweep schedules twenty assets per DB job,
preserves its sweep hour across pages/replays and includes DELETED tombstones.
Late storage completions are repurged without a second quota decrement. FAILED
keeps a 24-hour repair window from its terminal transition, not upload creation.
Unbound READY expires after 24 hours; edit/deletion history
after 30 days; moderation privately holds images for 30 days, not indefinitely.

The pinned Silo [HTTP server](https://github.com/pgsty/silo/blob/RELEASE.2026-09-16T00-00-00Z/internal/http/server.go)
uses rolling idle upload read deadlines; it does not supply an absolute inbound
PUT duration. Fifteen-minute settling is conservative, not proof that every
adversarial in-flight upload has ended. Hourly tombstone/READY reconciliation is
the durable late-completion repair path; temporary bytes may persist until that
sweep and storage outages/DEAD work require audited operator replay. Production
must enforce a total upload duration within settling at ingress (and verify the
selected provider's completion/cancellation behavior) before claiming bounded
all-version erasure. The local developer node remains loopback-only/untrusted-
traffic unsupported; lifecycle expiry is defense in depth, not finalization proof.
Hourly MEDIA_QUOTA_RECONCILE scans twenty owners/job under ordered quota guards,
computes logical non-DELETED charges and audits ACCOUNTING_DRIFT repairs.
Source transitions durably enqueue POST_BINDING_RECONCILE/AVATAR_BINDING_RECONCILE.
MEDIA_BINDING_MISMATCH audit entries require operator alerting/repair; signing already
fails closed. Restore drills enqueue these owner checks in bounded source batches
before enabling traffic. No media reverse-join into content/profile is allowed.

## Restricted audited replay

There is no public image repair endpoint. Restricted operator DB authority diagnoses
exact job/asset IDs, repairs infrastructure first and inserts actor/action/target/
request/reason audit in the same recovery transaction. Never reset counters because
files look absent; use real listing/confirmation. Preserve dedup/payload, pinned
uploaded_version_id, variant versions and monotonically increasing lease_generation.

For cleanup/generation/quarantine DEAD: lock the exact job, verify no live lease,
reset attempts to zero/status RETRY, clear owner/until and set available_at to now,
with audit. Worker revalidates source/READY generation; DELETING stays charged until
acknowledged purge. For MEDIA_PROCESS, FAILED has a 24-hour repair window: lock its
DEAD job first, owner quota then asset; require FAILED, pinned source availability
and no cleanup claim. Set FAILED to UPLOADED with version increment, clear failure
code and replay the **same job**, preserving generation, with attributable audit.
The next claim strictly increases generation. Never revive REJECTED/DELETING/DELETED
or create a second generation-one job. Reupload when repair is unsafe. Tests exercise
this restricted synthetic operator procedure. Binding mismatch repair belongs to
the source owner under a fresh explicit audited decision, not raw storage authority.

Artifact: PGSTY Silo RELEASE.2026-09-16T00-00-00Z, source
`2a4d51406b7ed87af5fe6fe0f801f3290f96eb3c`, manifest
`sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46`.
Image notices/license metadata are AGPL-3.0-or-later; retain upstream/fork attribution
and recheck security/provenance on upgrade. See
[release](https://github.com/pgsty/silo/releases/tag/RELEASE.2026-09-16T00-00-00Z)
and [license](https://silo.pgsty.com/about/license/). No repository license is selected.

Production gates: S3 signature/checksum/versioned IAM/delete compatibility, TLS/KMS,
separate runtime roles, strict ingress/volume/overwrite controls, processor sandbox/
egress, dependency/image security review, DB/storage/key restore and erasure replay,
alert export/retention/legal decisions and shared limits for multiple public instances.
Local checks do not prove those external gates passed.


## Phase9A-II strict physical erasure

The owner scans all asset states in100-ID pages and verifies independent resource
chunks before physical work. Profile/content release must finish first. An asset
claim stores request/generation and once-only accounting due amounts. Processor
prepare/complete/failure now guard terminal owners before quota/asset rows; old
external writes can finish but cannot READY/charge after close.

MEDIA_ERASURE requires a positive external MediaWriterDrain attestation for every
accepted ingress and variant writer. No runtime implementation is configured.
WRITER_DRAIN_UNPROVEN/disabled storage stays pending even for old DELETED rows.
Elapsed leases, persisted settling and periodic sweeping remain useful ordinary
cleanup but cannot certify account erasure. Only explicitly closed/joined synthetic
writers provide positive test evidence.

Outside SQL locks, purge lists/deletes at most50 versions/markers per prefix page
and repeats until empty. Ack rechecks exact job/step/request/round/asset generation;
then removes variants/old metadata and releases quota exactly once. A crash before
ack repeats absence verification. The immutable minimal purge digest/time remains;
periodic tombstone rechecks never charge/release again. Production drain/ingress/
IAM/latest-cut and restore remain unapproved gates.
