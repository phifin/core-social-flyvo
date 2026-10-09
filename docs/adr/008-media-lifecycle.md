# ADR-008 — Private upload and processed delivery

Status: Accepted, 2026-10-08.

Context: media is an early real feature and untrusted uploads introduce storage, parsing, overwrite and privacy failure modes.

Decision: presigned direct upload to private versioned quarantine, owner finalize, pin uploaded version, durable bounded processing, immutable re-encoded display/thumbnail variants, READY-only transactional binding to one context. Content/profile authorize short-lived access URLs. No raw database blobs, arbitrary URL import, public bucket or client-selected storage key. Local MinIO exercises the S3 adapter.

Consequences: SQL and storage cannot commit atomically; generation fencing, idempotent writes, row-state claims and cleanup/reconciliation handle partial failures. URLs are bearer capabilities with up to 60 seconds of post-issuance access; immediate revocation would need a delivery proxy. Initial format support is static JPEG/PNG/WebP, with strict size/pixel/decode limits.

Revisit video/documents when their processing/security budgets exist; add processors rather than bypassing validation. Changing storage provider requires versioning/checksum/presign contract tests.
