CREATE TABLE moderation.report (
 id uuid PRIMARY KEY,
 reporter_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 target_type varchar(16) NOT NULL CHECK (target_type IN ('POST','COMMENT','COMMUNITY','USER')),
 target_id uuid NOT NULL,
 community_id uuid REFERENCES community.community(id) ON DELETE RESTRICT,
 source_author_id uuid REFERENCES account.user_account(id) ON DELETE RESTRICT,
 parent_author_id uuid REFERENCES account.user_account(id) ON DELETE RESTRICT,
 category varchar(32) NOT NULL CHECK (category IN ('SPAM','HARASSMENT','HATE','VIOLENCE','SEXUAL_CONTENT','OTHER')),
 evidence_encrypted bytea NOT NULL CHECK (octet_length(evidence_encrypted) BETWEEN 1 AND 65536),
 evidence_key_id varchar(100) NOT NULL,
 status varchar(16) NOT NULL CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED','DISMISSED')),
 resolved_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
 CHECK ((status IN ('RESOLVED','DISMISSED')) = (resolved_at IS NOT NULL)),
 CHECK (target_type<>'USER' OR community_id IS NULL)
);
CREATE UNIQUE INDEX report_unresolved ON moderation.report(reporter_id,target_type,target_id) WHERE status IN ('OPEN','IN_REVIEW');
CREATE INDEX report_reporter_page ON moderation.report(reporter_id,created_at DESC,id DESC);
CREATE INDEX report_scope_queue ON moderation.report(community_id,status,created_at DESC,id DESC);
CREATE INDEX report_global_queue ON moderation.report(status,created_at DESC,id DESC) WHERE community_id IS NULL;
CREATE INDEX report_source_author ON moderation.report(source_author_id);
CREATE INDEX report_parent_author ON moderation.report(parent_author_id);
CREATE FUNCTION moderation.report_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.reporter_id,NEW.target_type,NEW.target_id,NEW.community_id,NEW.source_author_id,NEW.parent_author_id,NEW.category,NEW.evidence_encrypted,NEW.evidence_key_id,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.reporter_id,OLD.target_type,OLD.target_id,OLD.community_id,OLD.source_author_id,OLD.parent_author_id,OLD.category,OLD.evidence_encrypted,OLD.evidence_key_id,OLD.created_at)
 THEN RAISE EXCEPTION 'report identity and evidence are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER report_identity BEFORE UPDATE ON moderation.report FOR EACH ROW EXECUTE FUNCTION moderation.report_identity_guard();
ALTER TABLE moderation.action ADD COLUMN report_id uuid REFERENCES moderation.report(id) ON DELETE RESTRICT;
ALTER TABLE moderation.action ALTER COLUMN community_id DROP NOT NULL;
ALTER TABLE moderation.action DROP CONSTRAINT action_typed_target;
ALTER TABLE moderation.action ADD COLUMN comment_target_id uuid GENERATED ALWAYS AS
 (CASE WHEN target_type='COMMENT' THEN target_id END) STORED REFERENCES content.comment(id) ON DELETE RESTRICT;
ALTER TABLE moderation.action ADD COLUMN report_target_id uuid GENERATED ALWAYS AS
 (CASE WHEN target_type='REPORT' THEN target_id END) STORED REFERENCES moderation.report(id) ON DELETE RESTRICT;
ALTER TABLE moderation.action ADD CONSTRAINT action_typed_target CHECK
 ((community_id IS NOT NULL AND target_type='MEMBER' AND action IN ('SUSPEND_MEMBER','BAN_MEMBER','UNBAN_MEMBER')) OR
 (community_id IS NOT NULL AND target_type IN ('POST','COMMENT') AND action IN ('REMOVE_CONTENT','RESTORE_CONTENT')) OR
 (target_type='REPORT' AND action IN ('REVIEW_REPORT','RESOLVE_REPORT','DISMISS_REPORT') AND report_id IS NOT NULL AND report_id=target_id));
CREATE INDEX action_comment_target ON moderation.action(comment_target_id) WHERE comment_target_id IS NOT NULL;
CREATE INDEX action_report_reference ON moderation.action(report_id) WHERE report_id IS NOT NULL;
CREATE INDEX action_report_target ON moderation.action(report_target_id) WHERE report_target_id IS NOT NULL;
