CREATE SCHEMA notification;
CREATE TABLE notification.recipient_state (
 recipient_id uuid PRIMARY KEY REFERENCES account.user_account(id) ON DELETE RESTRICT,
 watermark bigint NOT NULL DEFAULT 0 CHECK(watermark>=0), changed_at timestamptz NOT NULL
);
CREATE TABLE notification.notification (
 id uuid PRIMARY KEY, recipient_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 kind varchar(32) NOT NULL CHECK(kind IN ('FRIEND_REQUEST','FRIENDSHIP_ACCEPTED','COMMUNITY_INVITATION','COMMENT_ON_POST','REPLY_TO_COMMENT','MENTION','REACTION_ON_CONTENT')),
 target_type varchar(32) NOT NULL CHECK(target_type IN ('POST','COMMENT','FRIEND_REQUEST','COMMUNITY_INVITATION')),
 target_id uuid NOT NULL, grouping_key varchar(250) NOT NULL, group_window_start timestamptz,
 activity_version bigint NOT NULL CHECK(activity_version>0), read_version bigint NOT NULL DEFAULT 0,
 last_activity_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
 CHECK(read_version>=0 AND read_version<=activity_version),
 CHECK((kind='REACTION_ON_CONTENT')=(group_window_start IS NOT NULL)),
 UNIQUE(recipient_id,grouping_key)
);
CREATE INDEX inbox_recipient_page ON notification.notification(recipient_id,last_activity_at DESC,id DESC);
CREATE INDEX inbox_unread ON notification.notification(recipient_id,last_activity_at DESC,id DESC) WHERE read_version<activity_version;
CREATE INDEX inbox_retention ON notification.notification(last_activity_at,id);
CREATE TABLE notification.contribution (
 id uuid PRIMARY KEY, notification_id uuid NOT NULL REFERENCES notification.notification(id) ON DELETE CASCADE,
 source_event_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 source_type varchar(32) NOT NULL CHECK(source_type IN ('POST','COMMENT','REACTION','GRAPH','INVITATION')),
 source_id uuid NOT NULL, source_version bigint NOT NULL CHECK(source_version>=0),
 active boolean NOT NULL, occurred_at timestamptz NOT NULL, reaction_type varchar(16),
 UNIQUE(notification_id,source_event_id), UNIQUE(notification_id,source_type,source_id,actor_id),
 CHECK(reaction_type IS NULL OR reaction_type IN ('LIKE','LOVE','CELEBRATE','SUPPORT','INSIGHTFUL'))
);
CREATE INDEX contribution_actor ON notification.contribution(actor_id);
CREATE INDEX contribution_source ON notification.contribution(source_type,source_id);
CREATE INDEX contribution_page ON notification.contribution(notification_id,id) WHERE active;
CREATE TABLE notification.source_state (
 recipient_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 kind varchar(32) NOT NULL, source_type varchar(32) NOT NULL, source_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 latest_source_version bigint NOT NULL CHECK(latest_source_version>=0), active boolean NOT NULL,
 notification_id uuid REFERENCES notification.notification(id) ON DELETE SET NULL,
 updated_at timestamptz NOT NULL,
 PRIMARY KEY(recipient_id,kind,source_type,source_id,actor_id),
 CHECK(kind IN ('FRIEND_REQUEST','FRIENDSHIP_ACCEPTED','COMMUNITY_INVITATION','COMMENT_ON_POST','REPLY_TO_COMMENT','MENTION','REACTION_ON_CONTENT')),
 CHECK(source_type IN ('POST','COMMENT','REACTION','GRAPH','INVITATION'))
);
CREATE INDEX source_state_notification ON notification.source_state(notification_id);
CREATE INDEX source_state_actor ON notification.source_state(actor_id);
CREATE INDEX source_state_retention ON notification.source_state(updated_at,recipient_id) WHERE NOT active;
CREATE TABLE notification.preference (
 user_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 kind varchar(32) NOT NULL CHECK(kind IN ('FRIEND_REQUEST','FRIENDSHIP_ACCEPTED','COMMUNITY_INVITATION','COMMENT_ON_POST','REPLY_TO_COMMENT','MENTION','REACTION_ON_CONTENT')),
 in_app_enabled boolean NOT NULL, PRIMARY KEY(user_id,kind)
);
