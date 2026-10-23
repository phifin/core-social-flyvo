CREATE OR REPLACE FUNCTION account.guard_erasure_step() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'required erasure step cannot be deleted'; END IF;
 IF (NEW.user_id,NEW.request_id,NEW.module,NEW.step) IS DISTINCT FROM (OLD.user_id,OLD.request_id,OLD.module,OLD.step)
 THEN RAISE EXCEPTION 'erasure step identity is immutable'; END IF;
 IF OLD.status='DONE' AND NEW IS DISTINCT FROM OLD AND NOT
  ((OLD.module,OLD.step) IN (('moderation','private-payloads'),('account','completion-manifest'))
   AND NEW.status='WAITING_DEPENDENCY' AND NEW.reason='PRIVATE_PAYLOAD_CHANGED'
   AND NEW.checkpoint='START' AND NEW.version=OLD.version+1 AND NEW.proof IS NULL
   AND NEW.proof_version IS NULL AND NEW.completed_at IS NULL
   AND NEW.verification_round=OLD.verification_round
   AND EXISTS(SELECT 1 FROM account.user_account WHERE id=OLD.user_id AND status='DELETION_PENDING'))
 THEN RAISE EXCEPTION 'completed step needs reviewed verification incident protocol'; END IF;
 RETURN NEW;
END $$;
