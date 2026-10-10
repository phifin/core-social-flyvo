CREATE FUNCTION content.check_post_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pid uuid; p content.post%ROWTYPE; recipients bigint;
BEGIN
 IF TG_TABLE_NAME='post' THEN pid:=COALESCE(NEW.id,OLD.id);
 ELSE pid:=COALESCE(NEW.post_id,OLD.post_id); END IF;
 SELECT * INTO p FROM content.post WHERE id=pid FOR UPDATE;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT count(*) INTO recipients FROM content.post_recipient WHERE post_id=pid;
 IF (p.audience='CUSTOM' AND (recipients<1 OR recipients>100)) OR (p.audience<>'CUSTOM' AND recipients<>0)
    OR EXISTS(SELECT 1 FROM content.post_recipient WHERE post_id=pid AND user_id=p.author_id) THEN
   RAISE EXCEPTION 'invalid post audience recipients' USING ERRCODE='23514';
 END IF;
 IF p.status='PUBLISHED' AND length(trim(p.body))=0 AND p.link_url IS NULL
    AND NOT EXISTS(SELECT 1 FROM content.post_media WHERE post_id=pid) THEN
   RAISE EXCEPTION 'empty published post' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER post_invariants AFTER INSERT OR UPDATE ON content.post
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION content.check_post_invariants();
CREATE CONSTRAINT TRIGGER recipient_invariants AFTER INSERT OR UPDATE OR DELETE ON content.post_recipient
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION content.check_post_invariants();
CREATE CONSTRAINT TRIGGER attachment_invariants AFTER INSERT OR UPDATE OR DELETE ON content.post_media
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION content.check_post_invariants();
CREATE FUNCTION content.reject_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'post revisions are immutable'; END $$;
CREATE TRIGGER revision_immutable BEFORE UPDATE ON content.post_revision
 FOR EACH ROW EXECUTE FUNCTION content.reject_revision_mutation();
