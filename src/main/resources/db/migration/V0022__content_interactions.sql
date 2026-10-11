CREATE TABLE content.comment (
 id uuid PRIMARY KEY, post_id uuid NOT NULL REFERENCES content.post(id) ON DELETE RESTRICT,
 author_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 root_comment_id uuid, reply_to_user_id uuid REFERENCES account.user_account(id) ON DELETE RESTRICT,
 body varchar(5000) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('VISIBLE','DELETED','REMOVED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, edited_at timestamptz,
 deleted_at timestamptz, removed_at timestamptz, removal_action_id uuid,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0), UNIQUE(id,post_id),
 FOREIGN KEY(root_comment_id,post_id) REFERENCES content.comment(id,post_id) ON DELETE RESTRICT,
 CHECK(root_comment_id IS NULL OR root_comment_id<>id),
 CHECK(length(trim(body))>0 OR status='DELETED'),
 CHECK((removed_at IS NULL)=(removal_action_id IS NULL)),
 CHECK((status='DELETED' AND deleted_at IS NOT NULL) OR
   (status='REMOVED' AND deleted_at IS NULL AND removed_at IS NOT NULL) OR
   (status='VISIBLE' AND deleted_at IS NULL AND removed_at IS NULL))
);
CREATE INDEX comment_roots ON content.comment(post_id,created_at,id) WHERE root_comment_id IS NULL;
CREATE INDEX comment_replies ON content.comment(root_comment_id,created_at,id);
CREATE INDEX comment_post ON content.comment(post_id,status,author_id);
CREATE INDEX comment_author ON content.comment(author_id,id);
CREATE INDEX comment_reply_user ON content.comment(reply_to_user_id) WHERE reply_to_user_id IS NOT NULL;
CREATE FUNCTION content.check_comment_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.id,NEW.post_id,NEW.author_id,NEW.root_comment_id,NEW.reply_to_user_id,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.post_id,OLD.author_id,OLD.root_comment_id,OLD.reply_to_user_id,OLD.created_at)
 THEN RAISE EXCEPTION 'comment identity is immutable' USING ERRCODE='23514'; END IF;
 IF NEW.root_comment_id IS NOT NULL AND NOT EXISTS
   (SELECT 1 FROM content.comment WHERE id=NEW.root_comment_id AND post_id=NEW.post_id AND root_comment_id IS NULL)
 THEN RAISE EXCEPTION 'comment parent must be a root in the same post' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER comment_parent BEFORE INSERT OR UPDATE ON content.comment
 FOR EACH ROW EXECUTE FUNCTION content.check_comment_parent();
CREATE TABLE content.reaction (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 post_id uuid REFERENCES content.post(id) ON DELETE RESTRICT,
 comment_id uuid REFERENCES content.comment(id) ON DELETE RESTRICT,
 type varchar(16) NOT NULL CHECK(type IN ('LIKE','LOVE','CELEBRATE','SUPPORT','INSIGHTFUL')),
 active boolean NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0), CHECK(num_nonnulls(post_id,comment_id)=1)
);
CREATE UNIQUE INDEX reaction_post_actor ON content.reaction(actor_id,post_id) WHERE post_id IS NOT NULL;
CREATE UNIQUE INDEX reaction_comment_actor ON content.reaction(actor_id,comment_id) WHERE comment_id IS NOT NULL;
CREATE INDEX reaction_post_counts ON content.reaction(post_id,type,actor_id) WHERE active;
CREATE INDEX reaction_comment_counts ON content.reaction(comment_id,type,actor_id) WHERE active;
CREATE INDEX reaction_post_fk ON content.reaction(post_id) WHERE post_id IS NOT NULL;
CREATE INDEX reaction_comment_fk ON content.reaction(comment_id) WHERE comment_id IS NOT NULL;
CREATE FUNCTION content.check_reaction_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.actor_id,NEW.post_id,NEW.comment_id,NEW.created_at)
   IS DISTINCT FROM (OLD.id,OLD.actor_id,OLD.post_id,OLD.comment_id,OLD.created_at)
 THEN RAISE EXCEPTION 'reaction identity is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reaction_identity BEFORE UPDATE ON content.reaction
 FOR EACH ROW EXECUTE FUNCTION content.check_reaction_identity();
