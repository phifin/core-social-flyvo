-- New profiles have no prior handle. Unique canonical claims arbitrate creation
-- without retaining one advisory lock per bulk-inserted row. Bounded renames keep
-- sorted old/new keys; reserve/expiry retain that order before their claim lock.
CREATE OR REPLACE FUNCTION profile.claim_profile_handle() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key text; existing profile.handle_claim%ROWTYPE;
BEGIN
 IF EXISTS(SELECT 1 FROM profile.erasure_tombstone WHERE user_id=NEW.user_id) THEN
   RAISE EXCEPTION 'erased profile cannot be recreated';
 END IF;
 IF TG_OP='UPDATE' AND NEW.handle_normalized=OLD.handle_normalized THEN RETURN NEW; END IF;
 IF TG_OP='UPDATE' THEN
   FOR key IN SELECT DISTINCT x FROM unnest(ARRAY[NEW.handle_normalized,OLD.handle_normalized]) x ORDER BY x LOOP
     PERFORM pg_advisory_xact_lock(hashtextextended('profile.handle:'||key,0));
   END LOOP;
 END IF;
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
