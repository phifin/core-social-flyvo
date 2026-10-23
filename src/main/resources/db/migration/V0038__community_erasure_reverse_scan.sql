CREATE INDEX invitation_inviter_pending_erasure
 ON community.invitation(inviter_id,id) WHERE status='PENDING';
