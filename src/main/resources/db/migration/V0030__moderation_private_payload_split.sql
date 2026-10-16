-- ADR-016: controlled write-stop upgrade, not a rolling old/new binary schema.
LOCK TABLE moderation.report, moderation.action IN ACCESS EXCLUSIVE MODE;
CREATE TABLE moderation.report_evidence (
 report_id uuid PRIMARY KEY REFERENCES moderation.report(id) ON DELETE RESTRICT,
 ciphertext bytea, key_id varchar(100), redacted_at timestamptz,
 CHECK ((redacted_at IS NULL AND ciphertext IS NOT NULL AND
         octet_length(ciphertext) BETWEEN 1 AND 65536 AND key_id IS NOT NULL AND length(key_id)>0) OR
        (redacted_at IS NOT NULL AND ciphertext IS NULL AND key_id IS NULL))
);
CREATE TABLE moderation.action_detail (
 action_id uuid PRIMARY KEY REFERENCES moderation.action(id) ON DELETE RESTRICT,
 legacy_payload jsonb, ciphertext bytea, key_id varchar(100), redacted_at timestamptz,
 CHECK ((redacted_at IS NOT NULL AND legacy_payload IS NULL AND ciphertext IS NULL AND key_id IS NULL) OR
        (redacted_at IS NULL AND legacy_payload IS NOT NULL AND ciphertext IS NULL AND key_id IS NULL) OR
        (redacted_at IS NULL AND legacy_payload IS NULL AND ciphertext IS NOT NULL AND
         octet_length(ciphertext) BETWEEN 1 AND 65536 AND key_id IS NOT NULL AND length(key_id)>0))
);
INSERT INTO moderation.report_evidence(report_id,ciphertext,key_id)
 SELECT id,evidence_encrypted,evidence_key_id FROM moderation.report;
INSERT INTO moderation.action_detail(action_id,legacy_payload)
 SELECT id,jsonb_build_object('reason',reason,'before',before_state,'after',after_state)
 FROM moderation.action;
DO $$ BEGIN
 IF (SELECT count(*) FROM moderation.report) <> (SELECT count(*) FROM moderation.report_evidence)
 OR EXISTS (SELECT 1 FROM moderation.report r LEFT JOIN moderation.report_evidence e ON e.report_id=r.id
            WHERE e.report_id IS NULL OR e.ciphertext IS DISTINCT FROM r.evidence_encrypted
               OR e.key_id IS DISTINCT FROM r.evidence_key_id)
 THEN RAISE EXCEPTION 'lossless evidence migration proof failed'; END IF;
 IF (SELECT count(*) FROM moderation.action) <> (SELECT count(*) FROM moderation.action_detail)
 OR EXISTS (SELECT 1 FROM moderation.action a LEFT JOIN moderation.action_detail d ON d.action_id=a.id
            WHERE d.action_id IS NULL OR d.legacy_payload IS DISTINCT FROM
             jsonb_build_object('reason',a.reason,'before',a.before_state,'after',a.after_state))
 THEN RAISE EXCEPTION 'lossless action migration proof failed'; END IF;
END $$;
ALTER TABLE moderation.report DROP COLUMN evidence_encrypted, DROP COLUMN evidence_key_id;
ALTER TABLE moderation.action DROP COLUMN reason, DROP COLUMN before_state, DROP COLUMN after_state;
CREATE OR REPLACE FUNCTION moderation.report_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'report identity is retained'; END IF;
 IF ROW(NEW.id,NEW.reporter_id,NEW.target_type,NEW.target_id,NEW.community_id,NEW.source_author_id,NEW.parent_author_id,NEW.category,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.reporter_id,OLD.target_type,OLD.target_id,OLD.community_id,OLD.source_author_id,OLD.parent_author_id,OLD.category,OLD.created_at)
 THEN RAISE EXCEPTION 'report identity is immutable'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER report_identity ON moderation.report;
CREATE TRIGGER report_identity BEFORE UPDATE OR DELETE ON moderation.report
 FOR EACH ROW EXECUTE FUNCTION moderation.report_identity_guard();

CREATE FUNCTION moderation.require_payload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='report' THEN
  IF NOT EXISTS (SELECT 1 FROM moderation.report_evidence WHERE report_id=NEW.id)
  THEN RAISE EXCEPTION 'report evidence/tombstone required'; END IF;
 ELSE
  IF NOT EXISTS (SELECT 1 FROM moderation.action_detail WHERE action_id=NEW.id)
  THEN RAISE EXCEPTION 'action detail/tombstone required'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER report_payload_required AFTER INSERT OR UPDATE ON moderation.report
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION moderation.require_payload();
CREATE CONSTRAINT TRIGGER action_payload_required AFTER INSERT ON moderation.action
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION moderation.require_payload();

CREATE FUNCTION moderation.payload_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'payload tombstone is retained'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.redacted_at IS NOT NULL OR NEW.ciphertext IS NULL OR NEW.key_id IS NULL
  THEN RAISE EXCEPTION 'new payload must be encrypted'; END IF;
  IF TG_TABLE_NAME='action_detail' AND NEW.legacy_payload IS NOT NULL
  THEN RAISE EXCEPTION 'new legacy plaintext is forbidden'; END IF;
  RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='action_detail' THEN
  IF NEW.action_id IS DISTINCT FROM OLD.action_id THEN RAISE EXCEPTION 'payload identity immutable'; END IF;
  IF OLD.legacy_payload IS NOT NULL AND OLD.redacted_at IS NULL AND
     NEW.legacy_payload IS NULL AND NEW.ciphertext IS NOT NULL AND NEW.key_id IS NOT NULL AND NEW.redacted_at IS NULL
  THEN RETURN NEW; END IF;
 ELSE
  IF NEW.report_id IS DISTINCT FROM OLD.report_id THEN RAISE EXCEPTION 'payload identity immutable'; END IF;
 END IF;
 IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
 RAISE EXCEPTION 'private payload is immutable';
END $$;
CREATE TRIGGER evidence_guard BEFORE INSERT OR UPDATE OR DELETE ON moderation.report_evidence
 FOR EACH ROW EXECUTE FUNCTION moderation.payload_guard();
CREATE TRIGGER detail_guard BEFORE INSERT OR UPDATE OR DELETE ON moderation.action_detail
 FOR EACH ROW EXECUTE FUNCTION moderation.payload_guard();
CREATE INDEX action_detail_legacy ON moderation.action_detail(action_id) WHERE legacy_payload IS NOT NULL;
CREATE INDEX action_detail_key ON moderation.action_detail(key_id) WHERE ciphertext IS NOT NULL;
CREATE INDEX report_evidence_key ON moderation.report_evidence(key_id) WHERE ciphertext IS NOT NULL;
