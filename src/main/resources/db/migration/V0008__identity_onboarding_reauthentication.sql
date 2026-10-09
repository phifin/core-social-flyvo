ALTER TABLE identity.auth_session
    ADD COLUMN reauthentication_required boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN identity.auth_session.reauthentication_required IS
    'Mailbox completion of external onboarding is not fresh password/provider authentication; only explicit reauthentication clears this flag.';
