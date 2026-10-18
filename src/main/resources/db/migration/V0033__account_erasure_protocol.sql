CREATE TABLE account.erasure_intent (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES account.user_account(id),
 lineage uuid NOT NULL,
 expected_version bigint NOT NULL CHECK(expected_version>=0),
 protocol varchar(32) NOT NULL CHECK(protocol='erasure-v1'),
 policy varchar(64) NOT NULL CHECK(policy='isolated-synthetic-v1'),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,expected_version), UNIQUE(id,user_id)
);
CREATE TABLE account.erasure_request (
 id uuid PRIMARY KEY REFERENCES account.erasure_intent(id),
 user_id uuid NOT NULL UNIQUE REFERENCES account.user_account(id),
 requested_at timestamptz NOT NULL, lifecycle_generation bigint NOT NULL CHECK(lifecycle_generation>=0),
 protocol varchar(32) NOT NULL CHECK(protocol='erasure-v1'),
 policy varchar(64) NOT NULL CHECK(policy='isolated-synthetic-v1'),
 roster_digest varchar(64) NOT NULL CHECK(roster_digest ~ '^[a-f0-9]{64}$'),
 manifest_digest varchar(64) NOT NULL DEFAULT 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' CHECK(manifest_digest ~ '^[a-f0-9]{64}$'),
 verification_round bigint NOT NULL DEFAULT 1 CHECK(verification_round>0),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,user_id),
 FOREIGN KEY(id,user_id) REFERENCES account.erasure_intent(id,user_id)
);
CREATE TABLE account.erasure_issue (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id), reason varchar(64) NOT NULL,
 version bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account.erasure_step (
 user_id uuid NOT NULL REFERENCES account.user_account(id),
 request_id uuid NOT NULL REFERENCES account.erasure_request(id),
 module varchar(32) NOT NULL, step varchar(64) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('PENDING','RUNNABLE','RUNNING','RETRY','FAILED','DONE','WAITING_DEPENDENCY','WAITING_EXTERNAL','WAITING_RETENTION','WAITING_CASE','WAITING_HOLD')),
 checkpoint varchar(4096) NOT NULL DEFAULT 'START', examined bigint NOT NULL DEFAULT 0 CHECK(examined>=0),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0), proof varchar(128), proof_version bigint,
 verification_round bigint NOT NULL DEFAULT 1 CHECK(verification_round>0),
 next_check_at timestamptz, reason varchar(64), execution_id uuid,
 failures bigint NOT NULL DEFAULT 0 CHECK(failures>=0),
 completed_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,module,step),
 FOREIGN KEY(request_id,user_id) REFERENCES account.erasure_request(id,user_id),
 CHECK((module,step) IN (('account','recovery-intent'),('identity','credentials'),('profile','social'),('community','participation'),('graph','relations'),('content','authored-and-references'),('moderation','private-payloads'),('account','technical'),('media','objects'),('notification','derived'),('search','derived'),('account','completion-manifest'))),
 CHECK((status='DONE')=(completed_at IS NOT NULL)),
 CHECK(status<>'DONE' OR (proof IS NOT NULL AND proof_version=version))
);
CREATE INDEX erasure_step_due ON account.erasure_step(status,next_check_at,user_id);
CREATE TABLE account.erasure_export (
 id uuid PRIMARY KEY, intent_id uuid NOT NULL REFERENCES account.erasure_intent(id),
 operation varchar(16) NOT NULL CHECK(operation IN ('PREPARED','COMMITTED','RESOURCE','COMPLETION')),
 owner varchar(32), resource_kind varchar(32), resource_ids uuid[],
 record text NOT NULL CHECK(octet_length(record)<=16384),
 digest varchar(64) NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),
 receipt_digest varchar(64), verified_at timestamptz,
 failures bigint NOT NULL DEFAULT 0 CHECK(failures>=0),
 execution_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((receipt_digest IS NULL)=(verified_at IS NULL)),
 CHECK(receipt_digest IS NULL OR receipt_digest=digest),
 CHECK((operation='RESOURCE')=(resource_ids IS NOT NULL)),
 CHECK(resource_ids IS NULL OR (cardinality(resource_ids) BETWEEN 1 AND 100 AND array_position(resource_ids,NULL) IS NULL))
);
CREATE UNIQUE INDEX erasure_export_single ON account.erasure_export(intent_id,operation) WHERE operation IN ('PREPARED','COMMITTED');
CREATE INDEX erasure_export_backlog ON account.erasure_export(intent_id,created_at,id) WHERE receipt_digest IS NULL;
CREATE INDEX erasure_resource_kind ON account.erasure_export(intent_id,resource_kind,id);
CREATE INDEX erasure_resource_owner ON account.erasure_export(intent_id,owner) INCLUDE(failures) WHERE operation='RESOURCE';
CREATE FUNCTION account.guard_erasure_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'erasure protocol records cannot be deleted'; END IF;
 IF TG_TABLE_NAME='erasure_intent' THEN RAISE EXCEPTION 'erasure intent is immutable'; END IF;
 IF TG_TABLE_NAME='erasure_export' AND
    (NEW.id,NEW.intent_id,NEW.operation,NEW.owner,NEW.resource_kind,NEW.resource_ids,NEW.record,NEW.digest,NEW.created_at)
    IS DISTINCT FROM
    (OLD.id,OLD.intent_id,OLD.operation,OLD.owner,OLD.resource_kind,OLD.resource_ids,OLD.record,OLD.digest,OLD.created_at)
 THEN RAISE EXCEPTION 'erasure export is immutable'; END IF;
 IF TG_TABLE_NAME='erasure_export' AND OLD.receipt_digest IS NOT NULL AND
    (NEW.receipt_digest,NEW.verified_at) IS DISTINCT FROM (OLD.receipt_digest,OLD.verified_at)
 THEN RAISE EXCEPTION 'erasure receipt is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erasure_intent_immutable BEFORE UPDATE OR DELETE ON account.erasure_intent FOR EACH ROW EXECUTE FUNCTION account.guard_erasure_immutable();
CREATE TRIGGER erasure_export_immutable BEFORE UPDATE OR DELETE ON account.erasure_export FOR EACH ROW EXECUTE FUNCTION account.guard_erasure_immutable();
CREATE FUNCTION account.guard_erasure_step() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'required erasure step cannot be deleted'; END IF;
 IF (NEW.user_id,NEW.request_id,NEW.module,NEW.step) IS DISTINCT FROM (OLD.user_id,OLD.request_id,OLD.module,OLD.step)
 THEN RAISE EXCEPTION 'erasure step identity is immutable'; END IF;
 IF OLD.status='DONE' AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'completed step needs reviewed verification incident protocol'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erasure_step_guard BEFORE UPDATE OR DELETE ON account.erasure_step FOR EACH ROW EXECUTE FUNCTION account.guard_erasure_step();
-- Package I cannot finalize, including raw SQL mistakes. II must explicitly replace
-- this guard only after its owner/proof/final-certificate regression gate.
CREATE FUNCTION account.deny_package_i_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='DELETED' AND OLD.status<>'DELETED' THEN RAISE EXCEPTION 'package I has no complete owner proof'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erasure_completion_gate BEFORE UPDATE ON account.user_account FOR EACH ROW EXECUTE FUNCTION account.deny_package_i_completion();

CREATE FUNCTION account.check_erasure_roster() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT count(*) FROM account.erasure_step WHERE user_id=NEW.user_id)<>12 THEN
   RAISE EXCEPTION 'erasure requires all twelve owner steps' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER erasure_roster_complete AFTER INSERT ON account.erasure_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account.check_erasure_roster();
CREATE FUNCTION account.guard_erasure_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (NEW.id,NEW.user_id,NEW.requested_at,NEW.lifecycle_generation,NEW.protocol,NEW.policy,NEW.roster_digest,NEW.verification_round)
    IS DISTINCT FROM (OLD.id,OLD.user_id,OLD.requested_at,OLD.lifecycle_generation,OLD.protocol,OLD.policy,OLD.roster_digest,OLD.verification_round)
 THEN RAISE EXCEPTION 'erasure request is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erasure_request_immutable BEFORE UPDATE OR DELETE ON account.erasure_request FOR EACH ROW EXECUTE FUNCTION account.guard_erasure_request();
