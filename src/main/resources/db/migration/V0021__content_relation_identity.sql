-- Aggregate relation identities cannot move to another post. DELETE/INSERT is the
-- explicit owner-command replacement path, with both deferred parent guards fired.
CREATE FUNCTION content.reject_relation_reparent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.post_id<>OLD.post_id THEN
   RAISE EXCEPTION 'post relation identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER recipient_parent_immutable BEFORE UPDATE OF post_id ON content.post_recipient
 FOR EACH ROW EXECUTE FUNCTION content.reject_relation_reparent();
CREATE TRIGGER attachment_parent_immutable BEFORE UPDATE OF post_id ON content.post_media
 FOR EACH ROW EXECUTE FUNCTION content.reject_relation_reparent();
CREATE TRIGGER mention_parent_immutable BEFORE UPDATE OF post_id ON content.post_mention
 FOR EACH ROW EXECUTE FUNCTION content.reject_relation_reparent();
CREATE TRIGGER hashtag_parent_immutable BEFORE UPDATE OF post_id ON content.post_hashtag
 FOR EACH ROW EXECUTE FUNCTION content.reject_relation_reparent();
