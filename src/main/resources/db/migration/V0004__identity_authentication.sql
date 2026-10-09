CREATE SCHEMA identity;
CREATE TABLE identity.login_email (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id), email_original varchar(254) NOT NULL,
 email_normalized varchar(254) NOT NULL UNIQUE, verified_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE identity.local_credential (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id), password_hash text NOT NULL,
 password_changed_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE identity.auth_identity (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES account.user_account(id), provider varchar(16) NOT NULL CHECK(provider IN ('GOOGLE','FACEBOOK')),
 issuer varchar(255) NOT NULL, subject varchar(255) NOT NULL, observed_email varchar(254), created_at timestamptz NOT NULL, last_login_at timestamptz NOT NULL,
 UNIQUE(provider,issuer,subject), UNIQUE(user_id,provider)
);
CREATE TABLE identity.registration_intent (
 id uuid PRIMARY KEY, email_normalized varchar(254) NOT NULL, token_hash bytea NOT NULL UNIQUE CHECK(octet_length(token_hash)=32),
 flow varchar(16) NOT NULL CHECK(flow IN ('LOCAL','EXTERNAL')), provider_proof_encrypted bytea,
 browser_binding_hash bytea NOT NULL CHECK(octet_length(browser_binding_hash)=32), expires_at timestamptz NOT NULL,
 consumed_at timestamptz, created_at timestamptz NOT NULL, CHECK((flow='EXTERNAL')=(provider_proof_encrypted IS NOT NULL))
);
CREATE INDEX registration_email_idx ON identity.registration_intent(email_normalized,created_at);
CREATE INDEX registration_expiry_idx ON identity.registration_intent(expires_at);
CREATE TABLE identity.email_dispatch_window (
 email_key bytea NOT NULL, purpose varchar(32) NOT NULL, window_start timestamptz NOT NULL,
 issued_count int NOT NULL CHECK(issued_count BETWEEN 1 AND 3), PRIMARY KEY(email_key,purpose,window_start)
);
CREATE TABLE identity.auth_session (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES account.user_account(id), created_at timestamptz NOT NULL,
 last_seen_at timestamptz NOT NULL, authenticated_at timestamptz NOT NULL, reauthenticated_at timestamptz,
 idle_expires_at timestamptz NOT NULL, absolute_expires_at timestamptz NOT NULL, revoked_at timestamptz, revoke_reason varchar(64),
 updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CHECK(idle_expires_at <= absolute_expires_at), CHECK((revoked_at IS NULL)=(revoke_reason IS NULL))
);
CREATE INDEX session_user_idx ON identity.auth_session(user_id,revoked_at);
CREATE INDEX session_expiry_idx ON identity.auth_session(absolute_expires_at);
CREATE TABLE identity.refresh_token (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES identity.auth_session(id), generation int NOT NULL CHECK(generation >= 0),
 token_hash bytea NOT NULL UNIQUE CHECK(octet_length(token_hash)=32), issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 consumed_at timestamptz, replaced_by_id uuid REFERENCES identity.refresh_token(id) DEFERRABLE INITIALLY DEFERRED,
 UNIQUE(session_id,generation)
);
CREATE UNIQUE INDEX refresh_current_idx ON identity.refresh_token(session_id) WHERE consumed_at IS NULL;
CREATE TABLE identity.one_time_token (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES account.user_account(id), purpose varchar(32) NOT NULL CHECK(purpose IN ('RESET_PASSWORD','CHANGE_EMAIL')),
 token_hash bytea NOT NULL UNIQUE CHECK(octet_length(token_hash)=32), target_email varchar(254),
 expires_at timestamptz NOT NULL, consumed_at timestamptz, created_at timestamptz NOT NULL,
 CHECK((purpose='CHANGE_EMAIL')=(target_email IS NOT NULL))
);
CREATE INDEX one_time_user_idx ON identity.one_time_token(user_id,purpose,created_at);
CREATE INDEX one_time_expiry_idx ON identity.one_time_token(expires_at);
CREATE TABLE identity.oauth_transaction (
 id uuid PRIMARY KEY, state_hash bytea NOT NULL UNIQUE, browser_binding_hash bytea NOT NULL,
 provider varchar(16) NOT NULL CHECK(provider IN ('GOOGLE','FACEBOOK')), intent varchar(16) NOT NULL CHECK(intent IN ('LOGIN','LINK','REAUTH')),
 initiating_user_id uuid REFERENCES account.user_account(id), initiating_session_id uuid REFERENCES identity.auth_session(id),
 nonce_hash bytea, pkce_verifier_encrypted bytea, return_path varchar(255) NOT NULL,
 expires_at timestamptz NOT NULL, consumed_at timestamptz,
 CHECK((intent='LOGIN')=(initiating_user_id IS NULL AND initiating_session_id IS NULL))
);
CREATE INDEX oauth_expiry_idx ON identity.oauth_transaction(expires_at);
