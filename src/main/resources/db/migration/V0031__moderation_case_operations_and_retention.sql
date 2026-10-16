ALTER TABLE moderation.report
 ADD COLUMN assigned_to uuid REFERENCES account.user_account(id) ON DELETE RESTRICT,
 ADD COLUMN hold_until timestamptz, ADD COLUMN hold_reason varchar(64),
 ADD COLUMN retention_state varchar(16) NOT NULL DEFAULT 'NONE'
  CHECK (retention_state IN ('NONE','STARTED','DONE')),
 ADD CONSTRAINT report_hold_shape CHECK ((hold_until IS NULL)=(hold_reason IS NULL));
CREATE INDEX report_assignee_queue ON moderation.report(community_id,assigned_to,status,created_at DESC,id DESC);
CREATE INDEX report_assignee_reference ON moderation.report(assigned_to) WHERE assigned_to IS NOT NULL;
CREATE INDEX report_retention_due ON moderation.report(resolved_at,id) WHERE retention_state<>'DONE' AND resolved_at IS NOT NULL;
CREATE INDEX report_retention_partial ON moderation.report(id) WHERE retention_state='STARTED';
CREATE TABLE moderation.action_retention (
 action_id uuid PRIMARY KEY REFERENCES moderation.action(id) ON DELETE RESTRICT,
 hold_until timestamptz, hold_reason varchar(64),
 retention_state varchar(16) NOT NULL DEFAULT 'NONE' CHECK(retention_state IN ('NONE','STARTED','DONE')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK ((hold_until IS NULL)=(hold_reason IS NULL))
);
INSERT INTO moderation.action_retention(action_id) SELECT id FROM moderation.action WHERE report_id IS NULL;
CREATE INDEX action_retention_partial ON moderation.action_retention(action_id) WHERE retention_state='STARTED';
CREATE INDEX action_retention_due ON moderation.action(created_at,id) WHERE report_id IS NULL;
CREATE TABLE moderation.case_history (
 id uuid PRIMARY KEY,
 report_id uuid NOT NULL REFERENCES moderation.report(id) ON DELETE RESTRICT,
 case_version bigint CHECK(case_version>=0),
 actor_id uuid REFERENCES account.user_account(id) ON DELETE RESTRICT,
 operation varchar(40) NOT NULL,
 action_id uuid UNIQUE REFERENCES moderation.action(id) ON DELETE RESTRICT,
 assigned_to uuid REFERENCES account.user_account(id) ON DELETE RESTRICT,
 hold_until timestamptz, created_at timestamptz NOT NULL,
 UNIQUE(report_id,case_version)
);
-- Legacy action order exists, but its exact old case version does not. Do not invent it.
INSERT INTO moderation.case_history(id,report_id,actor_id,operation,action_id,created_at)
 SELECT id,report_id,actor_id,action,id,created_at FROM moderation.action WHERE report_id IS NOT NULL;
CREATE INDEX case_history_page ON moderation.case_history(report_id,created_at,id);
CREATE INDEX case_history_actor ON moderation.case_history(actor_id);
CREATE INDEX case_history_assignee ON moderation.case_history(assigned_to) WHERE assigned_to IS NOT NULL;
CREATE TRIGGER case_history_immutable BEFORE UPDATE OR DELETE ON moderation.case_history
 FOR EACH ROW EXECUTE FUNCTION moderation.reject_action_mutation();

ALTER TABLE moderation.action DROP CONSTRAINT action_typed_target;
ALTER TABLE moderation.action ADD COLUMN account_target_id uuid GENERATED ALWAYS AS
 (CASE WHEN target_type='ACCOUNT' THEN target_id END) STORED REFERENCES account.user_account(id) ON DELETE RESTRICT;
ALTER TABLE moderation.action ADD CONSTRAINT action_typed_target CHECK
 ((community_id IS NOT NULL AND target_type='MEMBER' AND action IN ('SUSPEND_MEMBER','BAN_MEMBER','UNBAN_MEMBER')) OR
 (community_id IS NOT NULL AND target_type IN ('POST','COMMENT') AND action IN ('REMOVE_CONTENT','RESTORE_CONTENT')) OR
 (target_type='REPORT' AND action IN ('REVIEW_REPORT','RESOLVE_REPORT','DISMISS_REPORT') AND report_id IS NOT NULL AND report_id=target_id) OR
 (community_id IS NULL AND report_id IS NULL AND target_type='ACCOUNT' AND action IN ('SUSPEND_ACCOUNT','REINSTATE_ACCOUNT')));
CREATE INDEX action_account_target ON moderation.action(account_target_id) WHERE account_target_id IS NOT NULL;

CREATE FUNCTION moderation.root_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE until_time timestamptz; terminal boolean; now_time timestamptz := clock_timestamp();
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'retention root is retained'; END IF;
 IF TG_TABLE_NAME='action_retention' THEN
  IF NOT EXISTS(SELECT 1 FROM moderation.action WHERE id=NEW.action_id AND report_id IS NULL)
  THEN RAISE EXCEPTION 'case-linked actions use case root'; END IF;
  IF TG_OP='INSERT' THEN
   IF NEW.retention_state<>'NONE' OR NEW.hold_until IS NOT NULL OR NEW.hold_reason IS NOT NULL OR NEW.version<>0
   THEN RAISE EXCEPTION 'invalid initial retention root'; END IF;
   RETURN NEW;
  END IF;
  IF NEW.action_id IS DISTINCT FROM OLD.action_id THEN RAISE EXCEPTION 'root identity immutable'; END IF;
  SELECT created_at+interval '365 days' INTO until_time FROM moderation.action WHERE id=NEW.action_id;
  terminal := true;
 ELSE
  until_time := NEW.resolved_at+interval '365 days';
  terminal := NEW.status IN ('RESOLVED','DISMISSED');
  IF OLD.status IN ('RESOLVED','DISMISSED') AND
     ROW(NEW.status,NEW.resolved_at) IS DISTINCT FROM ROW(OLD.status,OLD.resolved_at)
  THEN RAISE EXCEPTION 'terminal case is immutable'; END IF;
  IF NEW.assigned_to IS DISTINCT FROM OLD.assigned_to AND NEW.status NOT IN ('OPEN','IN_REVIEW')
  THEN RAISE EXCEPTION 'terminal assignment denied'; END IF;
 END IF;
 IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'root version must advance'; END IF;
 IF ROW(NEW.hold_until,NEW.hold_reason) IS DISTINCT FROM ROW(OLD.hold_until,OLD.hold_reason) THEN
  IF OLD.retention_state<>'NONE' THEN RAISE EXCEPTION 'redaction already begun'; END IF;
  IF NEW.hold_until IS NOT NULL AND (NEW.hold_until<=now_time OR NEW.hold_until>now_time+interval '365 days'
    OR NEW.hold_reason IS NULL OR NEW.hold_reason !~ '^[A-Z_]{1,64}$')
  THEN RAISE EXCEPTION 'hold must have bounded future expiry and reason code'; END IF;
 END IF;
 IF NEW.retention_state IS DISTINCT FROM OLD.retention_state THEN
  IF NOT terminal OR until_time IS NULL OR until_time>now_time OR NEW.hold_until>now_time
  THEN RAISE EXCEPTION 'retention not eligible'; END IF;
  IF NOT ((OLD.retention_state='NONE' AND NEW.retention_state='STARTED') OR
          (OLD.retention_state='STARTED' AND NEW.retention_state='DONE'))
  THEN RAISE EXCEPTION 'retention transition must be one way'; END IF;
  IF NEW.retention_state='DONE' THEN
   IF TG_TABLE_NAME='report' THEN
    IF EXISTS(SELECT 1 FROM moderation.report_evidence WHERE report_id=NEW.id AND redacted_at IS NULL)
     OR EXISTS(SELECT 1 FROM moderation.action a JOIN moderation.action_detail d ON d.action_id=a.id
               WHERE a.report_id=NEW.id AND d.redacted_at IS NULL)
    THEN RAISE EXCEPTION 'case still has private payload'; END IF;
   ELSE
    IF EXISTS(SELECT 1 FROM moderation.action_detail WHERE action_id=NEW.action_id AND redacted_at IS NULL)
    THEN RAISE EXCEPTION 'action still has private payload'; END IF;
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER report_control_guard BEFORE UPDATE ON moderation.report FOR EACH ROW EXECUTE FUNCTION moderation.root_guard();
CREATE TRIGGER action_root_guard BEFORE INSERT OR UPDATE OR DELETE ON moderation.action_retention FOR EACH ROW EXECUTE FUNCTION moderation.root_guard();

CREATE OR REPLACE FUNCTION moderation.require_payload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='report' THEN
  IF NOT EXISTS(SELECT 1 FROM moderation.report_evidence WHERE report_id=NEW.id)
  THEN RAISE EXCEPTION 'report evidence/tombstone required'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM moderation.action_detail WHERE action_id=NEW.id)
   OR (NEW.report_id IS NULL AND NOT EXISTS(SELECT 1 FROM moderation.action_retention WHERE action_id=NEW.id))
  THEN RAISE EXCEPTION 'action payload/root required'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE FUNCTION moderation.redaction_eligible(p_report uuid, p_action uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE result boolean;
BEGIN
 IF p_report IS NOT NULL THEN
  SELECT retention_state='STARTED' AND status IN ('RESOLVED','DISMISSED') AND
    resolved_at+interval '365 days'<=clock_timestamp() AND
    (hold_until IS NULL OR hold_until<=clock_timestamp()) INTO result
   FROM moderation.report WHERE id=p_report FOR UPDATE;
 ELSE
  SELECT r.retention_state='STARTED' AND a.created_at+interval '365 days'<=clock_timestamp() AND
    (r.hold_until IS NULL OR r.hold_until<=clock_timestamp()) INTO result
   FROM moderation.action_retention r JOIN moderation.action a ON a.id=r.action_id
   WHERE r.action_id=p_action FOR UPDATE OF r;
 END IF;
 RETURN COALESCE(result,false);
END $$;
CREATE FUNCTION moderation.initial_report_control() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.retention_state<>'NONE' OR NEW.hold_until IS NOT NULL OR NEW.hold_reason IS NOT NULL OR NEW.assigned_to IS NOT NULL
 THEN RAISE EXCEPTION 'invalid initial case control'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER initial_report_control BEFORE INSERT ON moderation.report
 FOR EACH ROW EXECUTE FUNCTION moderation.initial_report_control();
CREATE FUNCTION moderation.history_action_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.action_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM moderation.action WHERE id=NEW.action_id AND report_id=NEW.report_id)
 THEN RAISE EXCEPTION 'history action must belong to case'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER history_action_scope BEFORE INSERT ON moderation.case_history
 FOR EACH ROW EXECUTE FUNCTION moderation.history_action_scope();
CREATE OR REPLACE FUNCTION moderation.payload_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_report uuid; eligible boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'payload tombstone retained'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.redacted_at IS NOT NULL OR NEW.ciphertext IS NULL OR NEW.key_id IS NULL
  THEN RAISE EXCEPTION 'new payload must be encrypted'; END IF;
  IF TG_TABLE_NAME='action_detail' THEN
   IF NEW.legacy_payload IS NOT NULL THEN RAISE EXCEPTION 'new legacy plaintext forbidden'; END IF;
   SELECT report_id INTO parent_report FROM moderation.action WHERE id=NEW.action_id;
   IF parent_report IS NOT NULL AND EXISTS(SELECT 1 FROM moderation.report WHERE id=parent_report AND retention_state<>'NONE')
   THEN RAISE EXCEPTION 'case redaction already begun'; END IF;
  END IF;
  RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='action_detail' THEN
  IF NEW.action_id IS DISTINCT FROM OLD.action_id THEN RAISE EXCEPTION 'payload identity immutable'; END IF;
  IF OLD.legacy_payload IS NOT NULL AND OLD.redacted_at IS NULL AND NEW.legacy_payload IS NULL AND
     NEW.ciphertext IS NOT NULL AND NEW.key_id IS NOT NULL AND NEW.redacted_at IS NULL
  THEN RETURN NEW; END IF;
  IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  SELECT report_id INTO parent_report FROM moderation.action WHERE id=OLD.action_id;
  eligible := moderation.redaction_eligible(parent_report,OLD.action_id);
  IF OLD.redacted_at IS NULL AND NEW.redacted_at IS NOT NULL AND
     NEW.legacy_payload IS NULL AND NEW.ciphertext IS NULL AND NEW.key_id IS NULL AND eligible
  THEN NEW.redacted_at := clock_timestamp(); RETURN NEW; END IF;
 ELSE
  IF NEW.report_id IS DISTINCT FROM OLD.report_id THEN RAISE EXCEPTION 'payload identity immutable'; END IF;
  IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  eligible := moderation.redaction_eligible(OLD.report_id,NULL);
  IF OLD.redacted_at IS NULL AND NEW.redacted_at IS NOT NULL AND NEW.ciphertext IS NULL AND NEW.key_id IS NULL AND eligible
  THEN NEW.redacted_at := clock_timestamp(); RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'private payload replacement/restoration forbidden';
END $$;
