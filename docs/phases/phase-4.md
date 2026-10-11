# Phase 4 — posts, privacy and secure images

Complete Phase 4 implementation is locally verified and delivered for main review,
not accepted. Actual code/handoff hashes and routing are recorded in the
[mailbox](../agent-handoff.md). Development cannot accept a phase.
Latest stabilization baseline: `8333028` (main round 2 CHANGES_REQUESTED);
initial full-delivery resume baseline was `44bcc99`. Earlier prerequisite code `8fe7738` was a
checkpoint only. Main accepted ADR-013 at `ab568554cf50cfa5c2024e84c90a16ed234a967c`.
No Phase 5, home-feed/search/notification/video/document feature was introduced.

## Delivered scope

- Personal/community STANDARD posts; PUBLIC/FOLLOWERS/FRIENDS/CUSTOM/ONLY_ME and
  inherited COMMUNITY privacy; active participants, mentions/hashtags, nonempty
  plain text/unfetched links and atomic ten-image binding.
- Current ContentAccessPolicy through direct/batched/SQL/list/history/media paths;
  bilateral block/current membership/account enforcement, archived reads, blind
  owner deletion after departure. Fixed CUSTOM recipients do not expand on mentions.
- Strong version edit/audience/delete, logical create idempotency, immutable initial/
  edit/audience revisions, stable publishedAt, explicit audited audience widening,
  bounded retained history and deleted-body redaction.
- Scoped attributable MODERATOR+ remove/restore with action/audit/outbox atomicity;
  no author-text rewrite, cross-community action or restoration of author deletion.
- Owned upload intents/quota/rate, presigned exact-size/checksum PUT, pinned HEAD/
  finalize CAS, status-only metadata, durable out-of-TX isolated processing,
  private version-pinned PNG display/thumbnail and context-only <=60-second signing.
- Generation-specific immutable output, lease/fencing/terminal recovery, partial/
  stale output cleanup, all-version/marker purge, quota reconciliation and source
  binding alerts. Avatar purpose binding/replacement/release/access uses profile
  version/current policy, with no arbitrary key or uploader bypass.

## Schema, APIs and dependencies

V0015 media quota/asset/variant/binding; V0016 posts/recipient/revision/media/mention/
hashtag; V0017 scalar profile avatar FK; V0018 typed generated moderation target FKs;
V0019 deferred CUSTOM/nonempty and immutable revision guards; V0020 READY/purpose/
pinned-version guards; V0021 immutable relation-parent IDs (replacement uses
DELETE/INSERT, preserving deferred checks on both parents). Applied V0001–V0014 are unchanged, fresh and V0014 upgrades
are tested. No cross-module JPA relationships or foreign base-table SQL access.
The content adapter consumes the existing versioned account/graph/community views.

New published ContentCommands/Queries/PostModeration and MediaCommands/Queries
coordinate owners. Existing account API is reused; community adds bounded scope/
staff guards, profile adds bounded source-authorized attribution/avatar metadata;
graph guard bound is 121 (author +100 recipients +20 mentions), fact batches remain
100. Platform ExternalJobHandler separates fenced prepare/effect/ack without
business dependencies. Existing jobs/outbox/audit/idempotency and module DAG remain.

AWS SDK v2 2.55.14 was already introduced at the checkpoint for the real S3 seam.
New TwelveMonkeys imageio-webp 3.15.3 adds maintained pure Java static WebP decoding;
JPEG/PNG use JDK ImageIO. No broker/Redis/fork SDK/malware-format expansion.
[Content](../content.md), [media](../media.md), [OpenAPI](../api/openapi.yaml),
[database](../database-design.md) and [operations](../../ops/media.md) record exact
limits, API/cursor/retention and operator replay refinements.

## Round 2 stabilization — P4-R3/R4/R5

Findings remain OPEN until main independently checks these proposed fixes; their
original text and per-ID developer responses remain in the mailbox. P4-R1/R2 were
resolved by main at the latest review, not by development.

- R3: one READ COMMITTED statement snapshot for post/ordered recipients/media/
  mentions; batched owner authorization still applies afterward. History is capped
  at the authorized aggregate revision. No old CUSTOM grant can hydrate a newer
  private body, and kind/attachment/mention switches cannot form mixed aggregates.
- R4: all cleanup states defer purge/accounting until upload expiry +15-minute
  settling; prepare-only deferral persists RETRY/available_at without spending the
  failure budget, retaining monotonically increasing lease_generation. Immediate
  deletion/rejection still denies issuance/binding. Hourly bounded per-hour/keyset
  storage reconciliation rechecks READY quarantine and DELETED tombstones, including
  exceptional late completions, without releasing quota twice. FAILED retains its
  full 24-hour operator recovery window from the terminal transition.
- R5: a single reviewed historical fingerprint in `.gitleaksignore`, not rule/path
  exclusions or history rewrite. CI keeps its exact pinned full-history/redacted
  scan and adds an external, cleaned-up synthetic generic-api-key positive control.
- README/implementation-handoff and ADR-013's current runtime status now describe
  implemented Phase 4 awaiting review. No Phase 5–6 code, acceptance or push.

Seven new regressions were executed against the old root/relation/history/cleanup
implementations: **six assertion failures and one invalid-audience error**, then
the implementations were restored. This is intentional red evidence, not a passing
baseline or a skipped test. Log `/tmp/social-core-p4-r345-negative.log`; old direct
CUSTOM read actually returned B-only text, old history returned a post-departure
edit, and old cleanup completed/releases while the upload window remained open.
The current implementation also tests a twenty-asset sweep/replay and FAILED hold.

Settling is not a total PUT timeout: pinned Silo's rolling idle reads can admit a
request before signature expiry that completes later. The tested durable sweep
repairs an exceptional late SDK write; it is not a claim of synchronous bounded
physical-byte erasure or that an expired presign starts a new write. Production
must enforce the total ingress completion bound within settling and verify its
provider; logical quotas/lifecycle alone remain insufficient for hostile traffic.

Round 2 checks on Java 21, 2026-10-11:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=OpenApiContractTest,ImageProcessorTest,PostPolicyTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify \
  -Dit.test=Phase4IT,StorageContractIT,ComposeSecretsIT,FoundationIT
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply verify
docker run --rm -v "$PWD:/repo:ro" \
  zricethezav/gitleaks:v8.28.0@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854 \
  git /repo --redact --no-banner
bash ops/ci/secret-scan-regression.sh
```

PASS targeted **76** (18 unit/architecture/contract +58 integration); full verify
**182** (39 unit/architecture/contract +143 integration), zero failures/errors/skips.
Includes 33 Phase4IT, twelve real pinned storage contracts, Compose isolation,
packaged decoder and all previous real PostgreSQL/Mailpit suites. Formatting,
Enforcer/convergence, final OpenAPI parser/reference test, Compose config, script
syntax and changed Markdown local links/whitespace pass. Logs:
`/tmp/social-core-p4-r345-targeted.log`, `/tmp/social-core-p4-r345-verify.log`,
`/tmp/social-core-p4-r345-doc-format.log`. No exclusions/migration edits/skip-to-green.

Exact CI-pinned full-history scan is clean with the one fingerprint exception;
repository is not shallow. The original 26-commit scan reproduced exactly one
non-secret historical match before the exception; the same scan after it returns
zero. The external positive control still detects generic-api-key/exit 1 and its
wrapper passes. New committed history is rescanned after delivery; exact code
hash/results are in the mailbox. Logs `/tmp/social-core-p4-r345-history.log` and
`/tmp/social-core-p4-r5-positive-final.log`. Remote CI success is not claimed.
Actual Compose startup/replay and Docker app image build below remain historical
initial-delivery checks, not rerun in this stabilization; runtime config is unchanged.

## Initial full-delivery verification evidence (historical)

Use installed JDK 21 explicitly in this workspace (shell default is Java 8):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp spotless:apply test \
  -Dtest=ImageProcessorTest,PostPolicyTest,ArchitectureTest,PersistenceBoundaryTest \
  failsafe:integration-test failsafe:verify \
  -Dit.test=Phase4IT,StorageContractIT,ComposeSecretsIT,FoundationIT
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./mvnw -B -ntp verify
```

On 2026-10-11: targeted gate **68 pass** (18 unit/architecture/OpenAPI +50 real
integration), zero failures/errors/skips; additional OpenAPI/Compose non-default
Origin regression **2 pass**. Formatting, Enforcer and dependency convergence pass.
Final full `./mvnw -B -ntp verify`: **174 pass** (39 unit/architecture/contract,
135 integration), zero failures/errors/skips; includes 25 Phase4IT, 12 storage,
one Compose isolation, one packaged decoder and all prior real PostgreSQL/Mailpit
tests. Temporary evidence: `/tmp/social-core-p4-final-targeted.log`,
`/tmp/social-core-p4-compose-origin.log`, `/tmp/social-core-p4-full-verify.log`.
Earlier 142-test checkpoint results are historical, not this final gate.

Compose app/media resolved configuration, shell syntax and diff whitespace pass.
Changed documentation local link paths and OpenAPI YAML/local refs/unique operation
IDs pass. Actual non-root app image builds and, under read-only root, 1 GiB/two CPUs,
dropped capabilities/no-new-privileges, its packaged child emits PACKAGED_DECODER_OK.
No secret directory is mounted for this synthetic probe. Evidence:
`/tmp/social-core-p4-image-build.log`, Compose startup/init/replay logs under `/tmp`.
The pinned CI Gitleaks 8.28.0 image scans the staged patch through stdin with redaction:
zero leaks, evidence `/tmp/social-core-p4-staged-secrets.log`.

Real-infrastructure regression coverage:

- Canonical personal/community policy and actual SQL/object/API parity across
  follow/friend/bilateral-block/membership/suspension states; current history/media
  privacy, inactive author, source-bound/tampered/expired cursor and equal-time UUID
  ordering; HTTP CSRF/session/unknown privilege/If-Match/idempotency/nonempty paths.
- Named separate-transaction races with barriers and real PostgreSQL lock-wait
  evidence: membership departure/block versus publication in both orders,
  ten-image rollback/concurrent reuse, quota reservation and attach/cleanup.
  Same-version/different-version finalize CAS preserves one job/event.
- Real pinned Silo presign/checksum/size/auth denial, versioned encrypted buckets/
  least privilege/CORS/expiry/bootstrap replay, overwrite after finalize, partial
  output failure, stale fenced generation, tenth-lease crash recovery/audited replay,
  incomplete cleanup accounting and version/marker confirmation; JPEG/PNG/WebP
  pipeline, avatar replacement/purpose/privacy, source mismatch/quota reconciliation.
- Actual isolated decoder checks malformed/polyglot/animated/type mismatch/CRC/
  truncated/oversized/dimension-first and synthetic EXIF/location stripping.
  PackagedImageProcessorIT proves the Boot jar's child launcher and WebP dependency.
- Fresh/upgraded PostgreSQL migrations preserve fourteen old checksums and profile;
  immutable revisions/CUSTOM/deferred guards and moderation rollback/append-only
  action/history audit. Candidate EXPLAIN on 12,000 skewed rows (10% selected author)
  uses post_author/LIMIT (~1.8 ms locally, not an SLA); hydrate has a bounded constant
  JDBC statement count for one versus one hundred posts.

P4-R1: individual per-service file mounts, synthetic resolved-mount/runtime absence
regression and real non-root Compose startup/bootstrap replay pass. All eight
existing key file inode/size/mtime/mode metadata remained unchanged; no key contents
were printed, rotated or deleted. Silo was stopped without deleting local data.

P4-R2: no-Authorization/query-credential unsigned trailers use a valid scoped API
identity, malformed signature and well-formed payload. Pinned server rejects this
auth mode as 400 InvalidRequest; invalid header signatures reject as 403
SignatureDoesNotMatch. SDK-signed otherwise identical ordinary/Snowball controls
succeed; Snowball uses nonempty valid tar and signed extraction prefix. Before
controls, denial asserts no object versions or extracted entry. Tampered presign
keeps every other signed header. No vulnerable image or relaxed denial.

Main resolved P4-R1/P4-R2 at round 2; the checks above are initial development
evidence, with the main disposition preserved in the mailbox. Compose pins
the exact accepted PGSTY Silo digest, identical to Testcontainers, with AGPL notices.

## Material limitations and external gates

No new proven infrastructure blocker. Production S3 signature/checksum/IAM/version/
delete compatibility is unrun and deployment-blocking, independently from real
local Silo. TLS/KMS/runtime secret separation, strict ingress/physical storage and
reusable-PUT overwrite budgeting, stronger processor OS/egress isolation, dependency/
image security review, restricted alert export and restore/legal-retention gates
remain production responsibilities. Local 10 GiB bucket quota is asynchronously
scanned, not a synchronous hard physical cap. See operations for precise controls.

Initially both variants are fresh PNG; input/output ceilings and resize defaults
are fixed reviewed safety defaults. Decoder strips metadata rather than preserving
EXIF orientation. Capabilities cannot revoke previously issued/downloaded bytes.
Moderation image hold is thirty days; late restore cannot resurrect purged images.
No public draft-preview/repair/evidence/report endpoint or future-phase scaffolding.
