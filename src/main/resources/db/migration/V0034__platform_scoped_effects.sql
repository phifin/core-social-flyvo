CREATE TABLE platform.work_scope (
 job_id uuid PRIMARY KEY REFERENCES platform.job(id) ON DELETE CASCADE,
 scope_id uuid NOT NULL, effect_kind varchar(16) NOT NULL CHECK(effect_kind='MAIL')
);
CREATE INDEX work_scope_lookup ON platform.work_scope(scope_id,job_id);
CREATE TABLE platform.work_effect (
 job_id uuid NOT NULL REFERENCES platform.job(id) ON DELETE CASCADE, generation bigint NOT NULL CHECK(generation>0),
 started_at timestamptz NOT NULL, ended_at timestamptz,
 PRIMARY KEY(job_id,generation), CHECK(ended_at IS NULL OR ended_at>=started_at)
);
CREATE INDEX work_effect_unfinished ON platform.work_effect(started_at,job_id) WHERE ended_at IS NULL;
CREATE INDEX mail_expiry_lookup ON platform.secret_mail_payload(expires_at,job_id);
ALTER TABLE platform.job ADD COLUMN cancelled_at timestamptz,
 ADD COLUMN cancellation_code varchar(32),
 ADD CONSTRAINT job_cancellation_shape CHECK((cancelled_at IS NULL)=(cancellation_code IS NULL)),
 ADD CONSTRAINT job_cancellation_code CHECK(cancellation_code IS NULL OR cancellation_code='SCOPED_MAIL_CANCELLED');
