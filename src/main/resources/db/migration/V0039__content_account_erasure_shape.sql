ALTER TABLE content.post
 ADD COLUMN erasure_origin boolean NOT NULL DEFAULT false,
 ADD COLUMN erasure_requested_at timestamptz,
 ADD COLUMN erasure_redacted_at timestamptz,
 ADD CONSTRAINT post_erasure_shape CHECK
  (erasure_origin=(erasure_requested_at IS NOT NULL)
   AND (erasure_redacted_at IS NULL OR erasure_origin));
CREATE INDEX post_erasure_start ON content.post(author_id,id) WHERE NOT erasure_origin;
CREATE INDEX post_erasure_redact ON content.post(author_id,id)
 WHERE erasure_origin AND erasure_redacted_at IS NULL;

CREATE FUNCTION content.guard_post_erasure_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.erasure_origin AND
   (NOT NEW.erasure_origin OR NEW.erasure_requested_at IS DISTINCT FROM OLD.erasure_requested_at
    OR (OLD.erasure_redacted_at IS NOT NULL AND NEW.erasure_redacted_at IS DISTINCT FROM OLD.erasure_redacted_at))
 THEN RAISE EXCEPTION 'post erasure origin is one-way' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER post_erasure_origin_guard BEFORE UPDATE ON content.post
 FOR EACH ROW EXECUTE FUNCTION content.guard_post_erasure_origin();

ALTER TABLE content.reaction ADD COLUMN erased_by_account boolean NOT NULL DEFAULT false;
ALTER TABLE content.reaction ALTER COLUMN type DROP NOT NULL;
ALTER TABLE content.reaction ADD CONSTRAINT reaction_erasure_shape CHECK
 ((erased_by_account AND NOT active AND type IS NULL)
  OR (NOT erased_by_account AND type IS NOT NULL));
CREATE INDEX reaction_actor_erasure ON content.reaction(actor_id,id)
 WHERE NOT erased_by_account;
CREATE FUNCTION content.guard_reaction_erasure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.erased_by_account AND
   (NOT NEW.erased_by_account OR NEW.active OR NEW.type IS NOT NULL)
 THEN RAISE EXCEPTION 'erased reaction cannot be restored' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reaction_erasure_guard BEFORE UPDATE ON content.reaction
 FOR EACH ROW EXECUTE FUNCTION content.guard_reaction_erasure();
