CREATE SCHEMA content;
CREATE TABLE content.post (
 id uuid PRIMARY KEY, author_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 community_id uuid REFERENCES community.community(id) ON DELETE RESTRICT,
 kind varchar(16) NOT NULL DEFAULT 'STANDARD' CHECK(kind='STANDARD'),
 body text NOT NULL CHECK(char_length(body)<=10000), link_url varchar(2048),
 audience varchar(16) NOT NULL CHECK(audience IN ('PUBLIC','FOLLOWERS','FRIENDS','CUSTOM','ONLY_ME','COMMUNITY')),
 status varchar(16) NOT NULL CHECK(status IN ('PUBLISHED','DELETED','REMOVED')),
 revision_number int NOT NULL CHECK(revision_number>0), published_at timestamptz NOT NULL,
 edited_at timestamptz, deleted_at timestamptz, removed_at timestamptz, removal_action_id uuid,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CHECK((audience='COMMUNITY')=(community_id IS NOT NULL)),
 CHECK(status=CASE WHEN deleted_at IS NOT NULL THEN 'DELETED' WHEN removed_at IS NOT NULL THEN 'REMOVED' ELSE 'PUBLISHED' END),
 CHECK((removed_at IS NULL)=(removal_action_id IS NULL))
);
CREATE INDEX post_chronology ON content.post(published_at DESC,id DESC) WHERE status='PUBLISHED';
CREATE INDEX post_author ON content.post(author_id,published_at DESC,id DESC) WHERE status='PUBLISHED';
CREATE INDEX post_community ON content.post(community_id,published_at DESC,id DESC) WHERE status='PUBLISHED';
CREATE TABLE content.post_recipient (
 post_id uuid NOT NULL REFERENCES content.post(id) ON DELETE RESTRICT,
 user_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT, PRIMARY KEY(post_id,user_id)
);
CREATE INDEX recipient_user ON content.post_recipient(user_id,post_id);
CREATE TABLE content.post_revision (
 post_id uuid NOT NULL REFERENCES content.post(id) ON DELETE RESTRICT, revision_number int NOT NULL CHECK(revision_number>0),
 editor_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 body text NOT NULL CHECK(char_length(body)<=10000), link_url varchar(2048), audience_snapshot jsonb NOT NULL,
 media_ids_snapshot jsonb NOT NULL, mentions_snapshot jsonb NOT NULL, edited_at timestamptz NOT NULL,
 PRIMARY KEY(post_id,revision_number), CHECK(jsonb_typeof(media_ids_snapshot)='array' AND jsonb_array_length(media_ids_snapshot)<=10)
);
CREATE TABLE content.post_media (
 post_id uuid NOT NULL REFERENCES content.post(id) ON DELETE RESTRICT,
 media_id uuid NOT NULL UNIQUE REFERENCES media.asset(id) ON DELETE RESTRICT,
 position smallint NOT NULL CHECK(position BETWEEN 0 AND 9), alt_text varchar(1000),
 PRIMARY KEY(post_id,media_id), UNIQUE(post_id,position)
);
CREATE TABLE content.post_mention (
 post_id uuid NOT NULL REFERENCES content.post(id) ON DELETE RESTRICT,
 mentioned_user_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 start_offset int NOT NULL CHECK(start_offset>=0), end_offset int NOT NULL CHECK(end_offset>start_offset),
 PRIMARY KEY(post_id,mentioned_user_id,start_offset)
);
CREATE INDEX mention_user ON content.post_mention(mentioned_user_id,post_id);
CREATE TABLE content.hashtag (id uuid PRIMARY KEY, normalized_tag varchar(50) NOT NULL UNIQUE, display_tag varchar(50) NOT NULL);
CREATE TABLE content.post_hashtag (
 post_id uuid NOT NULL REFERENCES content.post(id) ON DELETE RESTRICT,
 hashtag_id uuid NOT NULL REFERENCES content.hashtag(id) ON DELETE RESTRICT, PRIMARY KEY(post_id,hashtag_id)
);
CREATE INDEX hashtag_posts ON content.post_hashtag(hashtag_id,post_id);
