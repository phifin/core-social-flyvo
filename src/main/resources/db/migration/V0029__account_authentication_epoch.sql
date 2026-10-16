ALTER TABLE account.user_account ADD COLUMN authentication_epoch bigint NOT NULL DEFAULT 0
 CHECK (authentication_epoch >= 0);
ALTER TABLE identity.auth_session ADD COLUMN authentication_epoch bigint NOT NULL DEFAULT 0
 CHECK (authentication_epoch >= 0);

-- A suspension predating this migration must not revive its old sessions on
-- the first versioned reinstatement. Existing ACTIVE accounts keep their sessions.
UPDATE account.user_account SET authentication_epoch=1 WHERE status='SUSPENDED';
