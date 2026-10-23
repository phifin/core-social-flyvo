ALTER TABLE content.comment ADD COLUMN erased_by_account boolean NOT NULL DEFAULT false;
ALTER TABLE content.comment ADD CONSTRAINT comment_erasure_shape CHECK
 (NOT erased_by_account OR (status='DELETED' AND body='' AND deleted_at IS NOT NULL));
CREATE INDEX comment_erasure_unredacted ON content.comment(author_id,id) WHERE NOT erased_by_account;
CREATE FUNCTION content.guard_comment_erasure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.erased_by_account AND (NOT NEW.erased_by_account OR NEW.body<>'' OR NEW.status<>'DELETED')
 THEN RAISE EXCEPTION 'erased comment cannot be restored' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER comment_erasure_guard BEFORE UPDATE ON content.comment
 FOR EACH ROW EXECUTE FUNCTION content.guard_comment_erasure();

CREATE OR REPLACE FUNCTION content.guard_post_erasure_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.erasure_origin AND
   (NOT NEW.erasure_origin OR NEW.erasure_requested_at IS DISTINCT FROM OLD.erasure_requested_at
    OR (OLD.erasure_redacted_at IS NOT NULL AND
       (NEW.erasure_redacted_at IS DISTINCT FROM OLD.erasure_redacted_at OR NEW.body<>''
        OR NEW.link_url IS NOT NULL OR NEW.status<>'DELETED')))
 THEN RAISE EXCEPTION 'post erasure is one-way' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION content.guard_redacted_post_relation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE redacted timestamptz;
BEGIN
 SELECT erasure_redacted_at INTO redacted FROM content.post WHERE id=NEW.post_id FOR SHARE;
 IF redacted IS NOT NULL THEN RAISE EXCEPTION 'redacted post cannot regain private relations' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER revision_erasure_guard BEFORE INSERT ON content.post_revision
 FOR EACH ROW EXECUTE FUNCTION content.guard_redacted_post_relation();
CREATE TRIGGER media_erasure_guard BEFORE INSERT ON content.post_media
 FOR EACH ROW EXECUTE FUNCTION content.guard_redacted_post_relation();
CREATE TRIGGER mention_erasure_guard BEFORE INSERT ON content.post_mention
 FOR EACH ROW EXECUTE FUNCTION content.guard_redacted_post_relation();
CREATE TRIGGER hashtag_erasure_guard BEFORE INSERT ON content.post_hashtag
 FOR EACH ROW EXECUTE FUNCTION content.guard_redacted_post_relation();
CREATE TRIGGER recipient_erasure_guard BEFORE INSERT ON content.post_recipient
 FOR EACH ROW EXECUTE FUNCTION content.guard_redacted_post_relation();
