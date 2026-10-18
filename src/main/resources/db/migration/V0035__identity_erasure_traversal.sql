CREATE TABLE identity.erasure_marker (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE identity.erasure_mailbox (
 user_id uuid NOT NULL REFERENCES identity.erasure_marker(user_id),
 email_normalized varchar(254) NOT NULL, scanned boolean NOT NULL DEFAULT false,
 PRIMARY KEY(user_id,email_normalized)
);
CREATE INDEX oauth_owner_traversal ON identity.oauth_transaction(initiating_user_id,id) WHERE initiating_user_id IS NOT NULL;
CREATE INDEX session_owner_traversal ON identity.auth_session(user_id,id);
CREATE INDEX token_owner_traversal ON identity.one_time_token(user_id,id);
CREATE INDEX token_target_drain ON identity.one_time_token(target_email,user_id) WHERE target_email IS NOT NULL;
