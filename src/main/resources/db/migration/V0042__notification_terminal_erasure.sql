ALTER TABLE notification.recipient_state
 ADD COLUMN terminal boolean NOT NULL DEFAULT false;
CREATE FUNCTION notification.guard_terminal_recipient() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.terminal AND NOT NEW.terminal THEN
   RAISE EXCEPTION 'terminal notification recipient is one-way' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER notification_terminal_guard BEFORE UPDATE ON notification.recipient_state
 FOR EACH ROW EXECUTE FUNCTION notification.guard_terminal_recipient();
CREATE INDEX inbox_erasure_target ON notification.notification(target_type,target_id,recipient_id,id);
CREATE INDEX contribution_actor_erasure ON notification.contribution(actor_id,id);
CREATE INDEX source_state_actor_erasure ON notification.source_state(actor_id,recipient_id,kind,source_type,source_id);
