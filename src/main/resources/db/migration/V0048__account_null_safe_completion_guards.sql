-- Fail closed on missing JSON fields and SQL NULL in the completion/invalidation guards.
CREATE OR REPLACE FUNCTION account.completion_proven(p_user uuid,p_digest text) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE r account.erasure_request%ROWTYPE; i account.erasure_intent%ROWTYPE;
 e account.erasure_export%ROWTYPE; certificate jsonb; expected_roster text;
BEGIN
 SELECT * INTO r FROM account.erasure_request WHERE user_id=p_user;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT * INTO i FROM account.erasure_intent WHERE id=r.id AND user_id=p_user;
 IF NOT FOUND THEN RETURN false; END IF;
 expected_roster := encode(sha256(convert_to('erasure-v1:isolated-synthetic-v1:[account/recovery-intent, identity/credentials, profile/social, community/participation, graph/relations, content/authored-and-references, moderation/private-payloads, account/technical, media/objects, notification/derived, search/derived, account/completion-manifest]','UTF8')),'hex');
 IF r.protocol<>'erasure-v1' OR r.policy<>'isolated-synthetic-v1' OR r.roster_digest<>expected_roster
  OR i.protocol<>r.protocol OR i.policy<>r.policy OR i.expected_version+1<>r.lifecycle_generation
 THEN RETURN false; END IF;
 IF (SELECT count(*) FROM account.erasure_step WHERE user_id=p_user AND request_id=r.id)<>12
  OR (SELECT count(*) FROM account.erasure_step WHERE user_id=p_user AND request_id=r.id
       AND NOT(module='account' AND step='completion-manifest') AND status='DONE'
       AND proof IS NOT NULL AND proof_version=version AND verification_round=r.verification_round)<>11
 THEN RETURN false; END IF;
 SELECT * INTO e FROM account.erasure_export WHERE intent_id=r.id AND operation='COMPLETION'
  AND digest=p_digest AND receipt_digest=digest AND verified_at IS NOT NULL ORDER BY created_at,id LIMIT 1;
 IF NOT FOUND OR encode(sha256(convert_to(e.record,'UTF8')),'hex')<>e.digest THEN RETURN false; END IF;
 certificate := e.record::jsonb;
 IF NOT (certificate @> jsonb_build_object(
   'lineage',i.lineage::text,'requestId',r.id::text,'subject',p_user::text,
   'lifecycleGeneration',r.lifecycle_generation,'protocol',r.protocol,'policy',r.policy,
   'rosterDigest',r.roster_digest,'verificationRound',r.verification_round,
   'resourceManifestDigest',r.manifest_digest))
  OR jsonb_typeof(certificate->'prerequisites') IS DISTINCT FROM 'array'
  OR jsonb_array_length(certificate->'prerequisites')<>11
 THEN RETURN false; END IF;
 IF (SELECT count(DISTINCT (p->>'owner',p->>'step')) FROM jsonb_array_elements(certificate->'prerequisites') p)<>11
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(certificate->'prerequisites') p
    WHERE NOT EXISTS(SELECT 1 FROM account.erasure_step s WHERE s.user_id=p_user AND s.request_id=r.id
      AND s.module=p->>'owner' AND s.step=p->>'step' AND s.step<>'completion-manifest'
      AND s.status='DONE' AND s.version::text=p->>'version' AND s.proof=p->>'digest'
      AND s.verification_round::text=p->>'round' AND s.proof_version=s.version))
 THEN RETURN false; END IF;
 IF EXISTS(SELECT 1 FROM account.erasure_export x WHERE x.intent_id=r.id AND x.operation='RESOURCE'
   AND (x.receipt_digest IS NULL OR x.receipt_digest<>x.digest OR x.verified_at IS NULL
    OR encode(sha256(convert_to(x.record,'UTF8')),'hex')<>x.digest
    OR NOT (x.record::jsonb @> jsonb_build_object(
      'recordId',x.id::text,'requestId',r.id::text,'subject',p_user::text,
      'generation',r.lifecycle_generation,'round',r.verification_round,
      'protocol',r.protocol,'policy',r.policy,'lineage',i.lineage::text,
      'rosterDigest',r.roster_digest,'operation','RESOURCE','owner',x.owner,
      'resourceKind',x.resource_kind,'resourceIds',to_jsonb(x.resource_ids)))
    OR (x.record::jsonb->>'effectiveNotBefore')::timestamptz IS DISTINCT FROM r.requested_at))
 THEN RETURN false; END IF;
 RETURN true;
END $$;

CREATE OR REPLACE FUNCTION account.guard_erasure_step() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'required erasure step cannot be deleted'; END IF;
 IF (NEW.user_id,NEW.request_id,NEW.module,NEW.step) IS DISTINCT FROM (OLD.user_id,OLD.request_id,OLD.module,OLD.step)
 THEN RAISE EXCEPTION 'erasure step identity is immutable'; END IF;
 IF OLD.status='DONE' AND NEW IS DISTINCT FROM OLD AND NOT COALESCE(
  ((OLD.module,OLD.step) IN (('moderation','private-payloads'),('account','completion-manifest'))
   AND NEW.status='WAITING_DEPENDENCY' AND NEW.reason='PRIVATE_PAYLOAD_CHANGED'
   AND NEW.checkpoint='START' AND NEW.version=OLD.version+1 AND NEW.proof IS NULL
   AND NEW.proof_version IS NULL AND NEW.completed_at IS NULL
   AND NEW.verification_round=OLD.verification_round
   AND EXISTS(SELECT 1 FROM account.user_account WHERE id=OLD.user_id AND status='DELETION_PENDING')),false)
 THEN RAISE EXCEPTION 'completed step needs reviewed verification incident protocol'; END IF;
 IF NEW.module='account' AND NEW.step='completion-manifest' AND NEW.status='DONE'
  AND NOT account.completion_proven(NEW.user_id,NEW.proof)
 THEN RAISE EXCEPTION 'completion requires current independently receipted certificate'; END IF;
 RETURN NEW;
END $$;
