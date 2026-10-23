CREATE INDEX report_erasure_source ON moderation.report(source_author_id,id);
CREATE INDEX report_erasure_parent ON moderation.report(parent_author_id,id);
CREATE INDEX report_erasure_reporter ON moderation.report(reporter_id,id);
CREATE INDEX action_erasure_actor ON moderation.action(actor_id,report_id,id);
CREATE INDEX action_erasure_target ON moderation.action(target_type,target_id,report_id,id);
CREATE INDEX history_erasure_actor ON moderation.case_history(actor_id,report_id);
CREATE INDEX history_erasure_assignee ON moderation.case_history(assigned_to,report_id);
CREATE INDEX history_export_page ON moderation.case_history(report_id,id);

CREATE TABLE moderation.preservation_export (
 id uuid PRIMARY KEY,root_type varchar(8) NOT NULL CHECK(root_type IN ('REPORT','ACTION')),
 root_id uuid NOT NULL,root_version bigint NOT NULL CHECK(root_version>=0),
 operation varchar(16) NOT NULL CHECK(operation IN ('BASELINE','PRESERVE','COMMITTED')),
 actor_id uuid REFERENCES account.user_account(id),community_id uuid,
 hold_until timestamptz,record text NOT NULL CHECK(octet_length(record)<=65536),
 digest varchar(64) NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),
 receipt_digest varchar(64),verified_at timestamptz,
 failures bigint NOT NULL DEFAULT 0,execution_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((receipt_digest IS NULL)=(verified_at IS NULL)),
 CHECK(receipt_digest IS NULL OR receipt_digest=digest)
);
CREATE TABLE moderation.preservation_state (
 root_type varchar(8) NOT NULL,root_id uuid NOT NULL,root_version bigint NOT NULL,
 after_history uuid,complete boolean NOT NULL DEFAULT false,
 pending_export uuid REFERENCES moderation.preservation_export(id),pending_after uuid,
 pending_complete boolean NOT NULL DEFAULT false,PRIMARY KEY(root_type,root_id)
);
CREATE INDEX preservation_backlog ON moderation.preservation_export(created_at,id) WHERE receipt_digest IS NULL;
CREATE FUNCTION moderation.guard_preservation_export() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'preservation record is retained'; END IF;
 IF (NEW.id,NEW.root_type,NEW.root_id,NEW.root_version,NEW.operation,NEW.actor_id,NEW.community_id,NEW.hold_until,NEW.record,NEW.digest,NEW.created_at)
 IS DISTINCT FROM (OLD.id,OLD.root_type,OLD.root_id,OLD.root_version,OLD.operation,OLD.actor_id,OLD.community_id,OLD.hold_until,OLD.record,OLD.digest,OLD.created_at)
 THEN RAISE EXCEPTION 'preservation intent is immutable'; END IF;
 IF OLD.receipt_digest IS NOT NULL AND (NEW.receipt_digest,NEW.verified_at) IS DISTINCT FROM (OLD.receipt_digest,OLD.verified_at)
 THEN RAISE EXCEPTION 'preservation receipt is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER preservation_export_guard BEFORE UPDATE OR DELETE ON moderation.preservation_export
 FOR EACH ROW EXECUTE FUNCTION moderation.guard_preservation_export();
