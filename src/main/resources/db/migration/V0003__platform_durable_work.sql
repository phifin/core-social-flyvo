CREATE TABLE platform.audit_entry (
 id uuid PRIMARY KEY, actor_id uuid REFERENCES account.user_account(id), action varchar(64) NOT NULL,
 target_type varchar(32) NOT NULL, target_id uuid, scope_id uuid, outcome varchar(16) NOT NULL,
 reason_code varchar(64), request_id varchar(64) NOT NULL, metadata jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL
);
CREATE INDEX audit_target_idx ON platform.audit_entry(target_type,target_id,created_at);
CREATE INDEX audit_actor_idx ON platform.audit_entry(actor_id,created_at);
CREATE FUNCTION platform.reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'audit is append only'; END $$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON platform.audit_entry FOR EACH ROW EXECUTE FUNCTION platform.reject_audit_mutation();
CREATE TABLE platform.outbox_event (
 id uuid PRIMARY KEY, event_type varchar(80) NOT NULL, schema_version int NOT NULL CHECK(schema_version > 0),
 aggregate_type varchar(32) NOT NULL, aggregate_id uuid NOT NULL, aggregate_version bigint NOT NULL CHECK(aggregate_version >= 0),
 event_sequence int NOT NULL CHECK(event_sequence >= 0), occurred_at timestamptz NOT NULL, payload jsonb NOT NULL,
 correlation_id varchar(64) NOT NULL, causation_id uuid, created_at timestamptz NOT NULL,
 UNIQUE(aggregate_type,aggregate_id,aggregate_version,event_sequence)
);
CREATE TABLE platform.outbox_delivery (
 event_id uuid NOT NULL REFERENCES platform.outbox_event(id), consumer_name varchar(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','RETRY','COMPLETED','DEAD')),
 attempts int NOT NULL DEFAULT 0 CHECK(attempts >= 0), available_at timestamptz NOT NULL,
 lease_owner uuid, lease_until timestamptz, lease_generation bigint NOT NULL DEFAULT 0,
 last_error_code varchar(64), completed_at timestamptz, PRIMARY KEY(event_id,consumer_name)
);
CREATE INDEX outbox_due_idx ON platform.outbox_delivery(available_at,event_id) WHERE status IN ('PENDING','RETRY');
CREATE INDEX outbox_lease_idx ON platform.outbox_delivery(lease_until) WHERE status='RUNNING';
CREATE TABLE platform.consumer_receipt (
 consumer_name varchar(64) NOT NULL, event_id uuid NOT NULL, processed_at timestamptz NOT NULL, PRIMARY KEY(consumer_name,event_id)
);
CREATE TABLE platform.job (
 id uuid PRIMARY KEY, kind varchar(64) NOT NULL, dedup_key varchar(128) NOT NULL, payload jsonb NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','RETRY','COMPLETED','DEAD')),
 attempts int NOT NULL DEFAULT 0 CHECK(attempts >= 0), available_at timestamptz NOT NULL,
 lease_owner uuid, lease_until timestamptz, lease_generation bigint NOT NULL DEFAULT 0,
 last_error_code varchar(64), created_at timestamptz NOT NULL, completed_at timestamptz, UNIQUE(kind,dedup_key)
);
CREATE INDEX job_due_idx ON platform.job(available_at,id) WHERE status IN ('PENDING','RETRY');
CREATE INDEX job_lease_idx ON platform.job(lease_until) WHERE status='RUNNING';
CREATE TABLE platform.secret_mail_payload (
 job_id uuid PRIMARY KEY REFERENCES platform.job(id), ciphertext bytea NOT NULL, key_id varchar(64) NOT NULL, expires_at timestamptz NOT NULL
);
