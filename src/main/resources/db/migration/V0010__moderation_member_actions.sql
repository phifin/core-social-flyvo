CREATE SCHEMA moderation;
CREATE TABLE moderation.action (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES account.user_account(id),
 community_id uuid NOT NULL REFERENCES community.community(id), target_type varchar(16) NOT NULL CHECK(target_type='MEMBER'),
 target_id uuid NOT NULL REFERENCES account.user_account(id), action varchar(32) NOT NULL CHECK(action IN ('SUSPEND_MEMBER','BAN_MEMBER','UNBAN_MEMBER')),
 reason varchar(2000) NOT NULL CHECK(length(trim(reason))>0), before_state jsonb NOT NULL, after_state jsonb NOT NULL,
 request_id varchar(64) NOT NULL, created_at timestamptz NOT NULL
);
CREATE INDEX moderation_action_target_idx ON moderation.action(target_type,target_id,created_at);
CREATE INDEX moderation_action_scope_idx ON moderation.action(community_id,created_at,id);
CREATE INDEX moderation_action_actor_idx ON moderation.action(actor_id);
CREATE FUNCTION moderation.reject_action_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'moderation action is append only'; END $$;
CREATE TRIGGER moderation_action_immutable BEFORE UPDATE OR DELETE ON moderation.action
 FOR EACH ROW EXECUTE FUNCTION moderation.reject_action_mutation();
