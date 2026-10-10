ALTER TABLE moderation.action DROP CONSTRAINT action_target_type_check;
ALTER TABLE moderation.action DROP CONSTRAINT action_action_check;
ALTER TABLE moderation.action DROP CONSTRAINT action_target_id_fkey;
ALTER TABLE moderation.action ADD COLUMN member_target_id uuid GENERATED ALWAYS AS
 (CASE WHEN target_type='MEMBER' THEN target_id END) STORED REFERENCES account.user_account(id) ON DELETE RESTRICT;
ALTER TABLE moderation.action ADD COLUMN post_target_id uuid GENERATED ALWAYS AS
 (CASE WHEN target_type='POST' THEN target_id END) STORED REFERENCES content.post(id) ON DELETE RESTRICT;
ALTER TABLE moderation.action ADD CONSTRAINT action_typed_target CHECK
 ((target_type='MEMBER' AND action IN ('SUSPEND_MEMBER','BAN_MEMBER','UNBAN_MEMBER')) OR
 (target_type='POST' AND action IN ('REMOVE_CONTENT','RESTORE_CONTENT')));
CREATE INDEX action_member_target ON moderation.action(member_target_id) WHERE member_target_id IS NOT NULL;
CREATE INDEX action_post_target ON moderation.action(post_target_id) WHERE post_target_id IS NOT NULL;
