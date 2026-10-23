ALTER TABLE media.asset ADD CONSTRAINT media_live_metadata_shape CHECK
 (erasure_purged_at IS NOT NULL OR (declared_mime IS NOT NULL AND sha256 IS NOT NULL));
ALTER TABLE media.asset ADD CONSTRAINT media_claimed_terminal_shape CHECK
 (erasure_request_id IS NULL OR
  (erasure_purged_at IS NULL AND state='DELETING') OR
  (erasure_purged_at IS NOT NULL AND state='DELETED' AND declared_mime IS NULL
   AND sha256 IS NULL AND declared_bytes=0 AND retained_bytes=0
   AND uploaded_version_id IS NULL AND width IS NULL AND height IS NULL AND failure_code IS NULL));
