CREATE TABLE profile.erasure_tombstone (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id),
 source_version bigint NOT NULL CHECK(source_version>0), erased_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION profile.reject_erasure_tombstone_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'profile erasure tombstone is immutable'; END $$;
CREATE TRIGGER profile_erasure_immutable BEFORE UPDATE OR DELETE ON profile.erasure_tombstone FOR EACH ROW EXECUTE FUNCTION profile.reject_erasure_tombstone_mutation();
CREATE TABLE profile.handle_claim (
 handle_normalized varchar(30) PRIMARY KEY CHECK(handle_normalized ~ '^[a-z0-9_]{3,30}$'),
 state varchar(16) NOT NULL CHECK(state IN ('LIVE','RESERVED')),
 user_id uuid UNIQUE REFERENCES profile.profile(user_id) DEFERRABLE INITIALLY DEFERRED,
 expires_at timestamptz,
 CHECK((state='LIVE' AND user_id IS NOT NULL AND expires_at IS NULL)
    OR (state='RESERVED' AND user_id IS NULL AND expires_at IS NOT NULL))
);
CREATE INDEX handle_reservation_expiry ON profile.handle_claim(expires_at,handle_normalized) WHERE state='RESERVED';
INSERT INTO profile.handle_claim(handle_normalized,state,user_id)
 SELECT handle_normalized,'LIVE',user_id FROM profile.profile;

CREATE FUNCTION profile.claim_profile_handle() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key text; existing profile.handle_claim%ROWTYPE;
BEGIN
 IF EXISTS(SELECT 1 FROM profile.erasure_tombstone WHERE user_id=NEW.user_id) THEN
   RAISE EXCEPTION 'erased profile cannot be recreated';
 END IF;
 IF TG_OP='UPDATE' AND NEW.handle_normalized=OLD.handle_normalized THEN RETURN NEW; END IF;
 -- All competing create/rename/reserve/expiry paths share this key order.
 FOR key IN SELECT DISTINCT x FROM unnest(ARRAY[NEW.handle_normalized,CASE WHEN TG_OP='UPDATE' THEN OLD.handle_normalized END]) x WHERE x IS NOT NULL ORDER BY x LOOP
   PERFORM pg_advisory_xact_lock(hashtextextended('profile.handle:'||key,0));
 END LOOP;
 SELECT * INTO existing FROM profile.handle_claim WHERE handle_normalized=NEW.handle_normalized FOR UPDATE;
 IF FOUND THEN
   IF existing.state='RESERVED' AND existing.expires_at<=clock_timestamp() THEN
     DELETE FROM profile.handle_claim WHERE handle_normalized=NEW.handle_normalized;
   ELSE RAISE EXCEPTION 'handle is claimed' USING ERRCODE='23505'; END IF;
 END IF;
 IF TG_OP='UPDATE' THEN DELETE FROM profile.handle_claim WHERE handle_normalized=OLD.handle_normalized AND state='LIVE' AND user_id=OLD.user_id; END IF;
 INSERT INTO profile.handle_claim(handle_normalized,state,user_id) VALUES (NEW.handle_normalized,'LIVE',NEW.user_id);
 RETURN NEW;
END $$;
CREATE TRIGGER profile_handle_claim BEFORE INSERT OR UPDATE OF handle_normalized ON profile.profile FOR EACH ROW EXECUTE FUNCTION profile.claim_profile_handle();

CREATE FUNCTION profile.check_handle_completeness() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key text; p_user uuid; c_user uuid; c_state text;
BEGIN
 FOR key IN SELECT DISTINCT x FROM unnest(ARRAY[CASE WHEN TG_OP<>'DELETE' THEN NEW.handle_normalized END,CASE WHEN TG_OP<>'INSERT' THEN OLD.handle_normalized END]) x WHERE x IS NOT NULL LOOP
   SELECT user_id INTO p_user FROM profile.profile WHERE handle_normalized=key;
   SELECT user_id,state INTO c_user,c_state FROM profile.handle_claim WHERE handle_normalized=key;
   IF (p_user IS NOT NULL AND (c_user IS DISTINCT FROM p_user OR c_state IS DISTINCT FROM 'LIVE'))
      OR (c_state='LIVE' AND c_user IS DISTINCT FROM p_user)
   THEN RAISE EXCEPTION 'profile handle claim is incomplete' USING ERRCODE='23514'; END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER profile_handle_complete AFTER INSERT OR UPDATE OR DELETE ON profile.profile DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION profile.check_handle_completeness();
CREATE CONSTRAINT TRIGGER handle_profile_complete AFTER INSERT OR UPDATE OR DELETE ON profile.handle_claim DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION profile.check_handle_completeness();
