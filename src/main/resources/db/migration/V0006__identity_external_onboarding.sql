CREATE TABLE identity.external_onboarding (
 id uuid PRIMARY KEY, secret_hash bytea NOT NULL UNIQUE CHECK(octet_length(secret_hash)=32),
 browser_binding_hash bytea NOT NULL CHECK(octet_length(browser_binding_hash)=32),
 provider_proof_encrypted bytea NOT NULL, expires_at timestamptz NOT NULL, consumed_at timestamptz
);
CREATE INDEX onboarding_expiry_idx ON identity.external_onboarding(expires_at);
