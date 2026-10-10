CREATE TABLE platform.idempotency_record (
 actor_id uuid NOT NULL REFERENCES account.user_account(id), route_key varchar(200) NOT NULL,
 key_hash bytea NOT NULL CHECK(octet_length(key_hash)=32), request_hash bytea NOT NULL CHECK(octet_length(request_hash)=32),
 response_reference uuid, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
 PRIMARY KEY(actor_id,route_key,key_hash)
);
CREATE INDEX idempotency_expiry_idx ON platform.idempotency_record(expires_at);
