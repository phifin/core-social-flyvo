ALTER TABLE identity.oauth_transaction ADD CONSTRAINT oauth_initiator_check
 CHECK ((intent='LOGIN' AND initiating_user_id IS NULL AND initiating_session_id IS NULL)
 OR (intent IN ('LINK','REAUTH') AND initiating_user_id IS NOT NULL AND initiating_session_id IS NOT NULL));
ALTER TABLE identity.login_email ADD CONSTRAINT email_normalized_check CHECK(length(email_normalized)>3 AND email_normalized=lower(email_normalized));
ALTER TABLE identity.auth_identity ADD CONSTRAINT provider_subject_check CHECK(length(issuer)>0 AND length(subject)>0);
ALTER TABLE identity.oauth_transaction ADD CONSTRAINT oauth_hash_check CHECK(octet_length(state_hash)=32 AND octet_length(browser_binding_hash)=32 AND (nonce_hash IS NULL OR octet_length(nonce_hash)=32));
ALTER TABLE identity.refresh_token ADD CONSTRAINT refresh_successor_check CHECK(replaced_by_id IS NULL OR (consumed_at IS NOT NULL AND replaced_by_id<>id));
ALTER TABLE identity.auth_session ADD CONSTRAINT session_expiry_order CHECK(authenticated_at<=absolute_expires_at AND created_at<absolute_expires_at);
ALTER TABLE platform.job ADD CONSTRAINT job_lease_check CHECK((status='RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL));
ALTER TABLE platform.outbox_delivery ADD CONSTRAINT delivery_lease_check CHECK((status='RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL));
