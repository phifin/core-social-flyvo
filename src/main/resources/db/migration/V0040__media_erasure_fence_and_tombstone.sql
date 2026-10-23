ALTER TABLE media.asset
 ADD COLUMN erasure_request_id uuid REFERENCES account.erasure_request(id),
 ADD COLUMN erasure_purged_at timestamptz,
 ADD COLUMN erasure_drain_digest varchar(64) CHECK(erasure_drain_digest ~ '^[a-f0-9]{64}$'),
 ADD COLUMN erasure_reserved_due bigint NOT NULL DEFAULT 0 CHECK(erasure_reserved_due>=0),
 ADD COLUMN erasure_retained_due bigint NOT NULL DEFAULT 0 CHECK(erasure_retained_due>=0),
 ADD CONSTRAINT media_erasure_receipt_shape CHECK
  (erasure_purged_at IS NULL OR (erasure_request_id IS NOT NULL AND state='DELETED'
   AND erasure_drain_digest IS NOT NULL
   AND erasure_reserved_due=0 AND erasure_retained_due=0));
ALTER TABLE media.asset ALTER COLUMN declared_mime DROP NOT NULL;
ALTER TABLE media.asset ALTER COLUMN sha256 DROP NOT NULL;
ALTER TABLE media.asset DROP CONSTRAINT asset_declared_bytes_check;
ALTER TABLE media.asset ADD CONSTRAINT asset_declared_bytes_check CHECK
 ((erasure_purged_at IS NULL AND declared_bytes BETWEEN 1 AND 10485760)
  OR (erasure_purged_at IS NOT NULL AND declared_bytes=0));
CREATE INDEX media_erasure_owner_scan ON media.asset(owner_id,id);
CREATE INDEX media_erasure_pending ON media.asset(owner_id,id)
 WHERE erasure_purged_at IS NULL;

CREATE FUNCTION media.guard_erasure_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.erasure_request_id IS NOT NULL AND NEW.erasure_request_id IS DISTINCT FROM OLD.erasure_request_id
 THEN RAISE EXCEPTION 'media erasure request is immutable' USING ERRCODE='23514'; END IF;
 IF OLD.erasure_purged_at IS NOT NULL AND
   (NEW.erasure_purged_at IS DISTINCT FROM OLD.erasure_purged_at OR NEW.state<>'DELETED'
    OR NEW.sha256 IS NOT NULL OR NEW.declared_mime IS NOT NULL OR NEW.declared_bytes<>0
    OR NEW.retained_bytes<>0 OR NEW.erasure_drain_digest IS DISTINCT FROM OLD.erasure_drain_digest)
 THEN RAISE EXCEPTION 'media erasure tombstone is one-way' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_erasure_tombstone_guard BEFORE UPDATE ON media.asset
 FOR EACH ROW EXECUTE FUNCTION media.guard_erasure_tombstone();
