-- Legacy immutable export bodies remain format1; only newly captured records use format2.
ALTER TABLE moderation.preservation_export ADD COLUMN format_version smallint NOT NULL DEFAULT 1
 CHECK(format_version IN (1,2));
ALTER TABLE moderation.preservation_state ADD COLUMN format_version smallint NOT NULL DEFAULT 1
 CHECK(format_version IN (1,2));
CREATE OR REPLACE FUNCTION moderation.guard_preservation_export() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'preservation record is retained'; END IF;
 IF (NEW.id,NEW.format_version,NEW.root_type,NEW.root_id,NEW.root_version,NEW.operation,NEW.actor_id,NEW.community_id,NEW.hold_until,NEW.record,NEW.digest,NEW.created_at)
 IS DISTINCT FROM (OLD.id,OLD.format_version,OLD.root_type,OLD.root_id,OLD.root_version,OLD.operation,OLD.actor_id,OLD.community_id,OLD.hold_until,OLD.record,OLD.digest,OLD.created_at)
 THEN RAISE EXCEPTION 'preservation intent is immutable'; END IF;
 IF OLD.receipt_digest IS NOT NULL AND (NEW.receipt_digest,NEW.verified_at) IS DISTINCT FROM (OLD.receipt_digest,OLD.verified_at)
 THEN RAISE EXCEPTION 'preservation receipt is immutable'; END IF;
 RETURN NEW;
END $$;
