# ADR-013 — Maintained local object-storage distribution

Status: **Accepted for implementation**, 2026-10-10, by main orchestrator under
the user's request to decide the Phase 4 blocker. Runtime integration is now
implemented and locally tested; Phase 4 was accepted by independent main review
on 2026-10-11. Production readiness remains separately gated. Decision-time evidence
below is historical.

Scope: the Phase 4 Compose/Testcontainers storage distribution only. This
supersedes ADR-008's local MinIO distribution choice, not its media lifecycle
or production S3 compatibility requirements.

## Context and observed blocker

Phase 4 requires real local versioned private storage. On this checkout, Docker
Engine 29.8.2 is available and the Java 21 PostgreSQL/Mailpit baseline passes all
124 tests. The storage prerequisite cannot currently be satisfied using the
documented upstream MinIO CE distribution:

- Pulling both `RELEASE.2025-04-22T22-12-26Z` and the latest upstream CE tag
  `RELEASE.2025-10-15T17-29-55Z` from `minio/minio` fails with pull-access denied.
  Both corresponding `quay.io/minio/minio` pulls fail with HTTP 401. These are
  observed unauthenticated availability failures here, not proof that every
  privately authenticated registry/mirror is unavailable.
- The official archived binary URL for the April 2025 release returns HTTP 410.
- Upstream's public repository is archived. Its final CE releases are affected by
  [unsigned-trailer signature bypass](https://github.com/minio/minio/security/advisories/GHSA-hv4r-mvr4-25vw),
  [Snowball signature bypass](https://github.com/minio/minio/security/advisories/GHSA-9c4q-hq6p-c237)
  and [encryption metadata injection](https://github.com/minio/minio/security/advisories/GHSA-3rh2-v3gr-35p9).
  The first two allow object writes without a valid signature; the third can
  corrupt readable encrypted objects. Upstream identifies patched AIStor releases,
  not a patched final CE tag. Rebuilding that CE tag or finding an old mirror
  alone therefore does not resolve the security prerequisite.

Official reverse-proxy mitigations exist for the known header paths. Adopting a
frozen CE server plus a new mandatory security proxy and maintaining its coverage
would be an additional operational/security decision, not merely retrying a pull.
No vulnerable server was started and no source implementation was changed.

## Decision

Use the maintained MinIO-derived **PGSTY Silo** distribution for local development
and integration tests, with the verification gates below required for Phase 4
acceptance. Pin the same artifact in Compose and Testcontainers:

```text
docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46
```

The [release record](https://github.com/pgsty/silo/releases/tag/RELEASE.2026-09-16T00-00-00Z)
identifies source `2a4d51406b7ed87af5fe6fe0f801f3290f96eb3c` and the multi-platform
digest above. Registry manifest lookup succeeded here and exposes amd64 manifest
`sha256:39aab3c365848e32aca7b76c5b57f86d1cbca02d7b8f24e1fd1feff9fedddae5` and arm64
manifest `sha256:1ff329e4507f742a8437f1773d43cf6adb0131477074dd45c770d1a1db19e99c`.
The development proposal established manifest availability. Main's subsequent
independent pull and provenance checks are recorded below; the storage contract
still requires implementation tests.

Silo is an independent fork, not upstream MinIO endorsement. Its
[security ledger](https://silo.pgsty.com/about/security-advisories/) and tagged
source provide reviewable patch provenance. Preliminary inspection found required
signature validation on unsigned-trailer readers and separation of trusted
replication metadata from ordinary uploads. This is not a complete security audit;
the integration/adversarial checks below remain mandatory.

Keep the AWS SDK v2 S3 adapter as the application boundary. Neither application
policy nor database ownership depends on the local vendor. Preserve private,
versioned quarantine/delivery, encryption, upload checksums, exact frontend CORS,
least-privilege runtime credentials, immutable processed variants, quota guards,
generation fencing, all-version cleanup and owner-authorized 60-second delivery.
No original-object fallback or skipped storage tests is permitted.

Adoption applies only to local Compose and CI/Testcontainers. It does not choose a
production provider, authorize deployment, or satisfy production S3 compatibility.
Record the fork's artifact/source/license notices and recheck its supported release
line when upgrading. No fork SDK, broker, cache or additional business module is
introduced.

Use a single local storage node. Bind developer-facing ports to loopback and keep
administrative credentials separate from application/worker credentials. Do not
enable federation, site replication, external identity providers or distributed
storage topology as part of this phase. The release documents distributed listing
and purge limitations; those are outside this local topology. Test cleanup against
actual version/delete-marker state, rather than treating a successful listing alone
as proof of erasure. Local adoption does not select this topology for production.

## Main decision evidence — 2026-10-10

Main reviewed the official upstream advisories, the tagged Silo release and its
security ledger independently. The ledger records inherited-path fixes and the
release records operation-header/presign fixes, source and artifact identities.
These are publisher evidence, not a repository exploit reproduction or complete
security audit. They support choosing the distribution while retaining adversarial
tests as an acceptance requirement.

The exact pinned image above was pulled successfully on this checkout. Docker
reported digest `635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46`;
image inspection confirmed linux/amd64, version RELEASE.2026-09-16T00-00-00Z,
source revision `2a4d51406b7ed87af5fe6fe0f801f3290f96eb3c` and
`AGPL-3.0-or-later` license metadata. Version/source labels match the release;
labels/digests establish
artifact identity, not the correctness of all runtime behavior. Preserve source
and license notices when documenting the local artifact.

Local evidence: `/tmp/social-core-phase4-storage-review-pull.log`. No storage
service was started, no application/storage configuration was implemented and no
Phase 4 storage test passed in this decision round. Decision blocker P4-B1 is
resolved; development owns the actual security/compatibility gates.

## Alternatives considered

1. **Selected:** use the exact maintained fork above for local storage. This
   changes the distribution/provenance specified by the blueprint, retaining the
   S3/media/security contracts and a real local test server.
2. Supply an accessible supported **MinIO AIStor** artifact and required operator
   setup; check its use/configuration requirements before pinning it. This retains
   the upstream vendor but adds an external setup dependency not currently present.
3. Approve a separate frozen-CE/source-build-and-proxy design, with pinned source,
   reproducible image and adversarial tests for all mitigated paths. This creates
   repository responsibility for maintaining that security boundary. It is not the
   proposed default and must not be introduced implicitly.

Changing to an unrelated S3-compatible server is another explicit decision, not an
assumed fallback. Adding a mock storage adapter does not unblock Phase 4 acceptance.

## Verification and consequences

Before accepting Phase 4, demonstrate against the chosen real artifact:

- Private buckets, versioning and encryption; CORS and restricted credentials;
  correct SHA-256/presign headers; rejection of tampered signatures/checksums,
  unsigned operation/copy/encryption headers and relevant unsigned-trailer paths.
- Finalize pins the verified version; overwrite afterward cannot alter processed
  bytes; duplicate/different-version finalize semantics and short capability TTL.
- Bounded static JPEG/PNG/WebP processing, metadata stripping, stale/partial output
  cleanup, quota concurrency, atomic ten-image binding, attach/cleanup races,
  avatar replacement and complete object-version/delete-marker deletion.
- Targeted PostgreSQL/storage tests, formatter and full `./mvnw verify`, plus
  Compose validation and operational recovery evidence. Record any unavailable
  production-provider checks as deployment gates, never local-test substitutes.

The decision references in ADR-008, media/testing/roadmap and operations are updated
with this acceptance; executable adapter/configuration and operational evidence
remain development work. The full Phase 4 content scope
(posts, privacy, revisions, moderation, images and avatar) remains assigned.

Revisit if the fork loses maintenance, a required security/compatibility test fails,
or the owner selects a supported production storage provider with different local
contract requirements. Main/user must approve a replacement decision; development
cannot self-accept a failed storage gate or the phase.
