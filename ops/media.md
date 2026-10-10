# Local Phase 4 storage prerequisite

This is verified infrastructure, **not** a usable post/image/avatar API. Phase 4
is IN_PROGRESS; lifecycle integration and its tests remain. No production readiness.

```bash
bash ops/local/generate-keys.sh
APP_UID="$(id -u)" APP_GID="$(id -g)" docker compose --profile media up -d --wait silo
APP_UID="$(id -u)" APP_GID="$(id -g)" docker compose --profile media run --rm silo-init
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
DB quotas/reconciliation/ingress controls are not implemented: do not expose this
local node to untrusted users.

Compatibility: the pinned Silo policy library makes GetObjectVersion imply
GetObject ([tagged action mapping](https://github.com/pgsty/silo-pkg/blob/v3.14.1/policy/actionset.go)). A real denial test initially reproduced unversioned delivery GET
succeeding. Adding a non-null `s3:versionid` condition restored denial; pinned GET,
outside-prefix denial and actual capability expiry pass. This is policy hardening,
not a relaxed contract or replacement of ADR-013. Verify production IAM separately.

S3ObjectStorage uses distinct bounded API/worker clients and an API presigner.
Uploads sign MIME/size/SHA-256; HEAD/GET require explicit versions; immutable output
PUT verifies exact metadata on retry; delivery TTL is <=60 seconds. Network methods
reject active transactions. No Spring configuration/use-case/public signing endpoint
is wired yet. Purge deletes at most 50 versions/markers per bucket per call, checks
known deleted versions with HEAD and relists. Truncation returns false; callers must
durably retry. A 20-second work deadline is checked between calls; in-flight calls
remain bounded by configured SDK timeouts. This is not a complete cleanup worker.

Artifact: PGSTY Silo RELEASE.2026-09-16T00-00-00Z, source
`2a4d51406b7ed87af5fe6fe0f801f3290f96eb3c`, manifest
`sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46`.
Image notices/license metadata are AGPL-3.0-or-later; retain upstream/fork attribution
and recheck security/provenance on upgrade. See
[release](https://github.com/pgsty/silo/releases/tag/RELEASE.2026-09-16T00-00-00Z)
and [license](https://silo.pgsty.com/about/license/). No repository license is selected.
