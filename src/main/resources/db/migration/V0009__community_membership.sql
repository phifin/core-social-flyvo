CREATE SCHEMA community;
CREATE TABLE community.community (
 id uuid PRIMARY KEY, slug_normalized varchar(80) NOT NULL UNIQUE CHECK(slug_normalized ~ '^[a-z0-9][a-z0-9-]{2,79}$'),
 name varchar(120) NOT NULL CHECK(length(trim(name))>0), description varchar(4000) NOT NULL,
 visibility varchar(16) NOT NULL CHECK(visibility IN ('PUBLIC','PRIVATE')),
 join_mode varchar(16) NOT NULL CHECK(join_mode IN ('OPEN','REQUEST','INVITE_ONLY')),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
 owner_user_id uuid NOT NULL REFERENCES account.user_account(id),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version>=0)
);
CREATE INDEX community_discovery_idx ON community.community(visibility,status,created_at,id);
CREATE INDEX community_owner_idx ON community.community(owner_user_id,status,id);
CREATE TABLE community.membership (
 community_id uuid NOT NULL REFERENCES community.community(id), user_id uuid NOT NULL REFERENCES account.user_account(id),
 role varchar(16) NOT NULL CHECK(role IN ('ADMIN','MODERATOR','MEMBER')),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','LEFT','REMOVED','BANNED')),
 joined_at timestamptz NOT NULL, left_at timestamptz, suspended_until timestamptz,
 updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 PRIMARY KEY(community_id,user_id),
 CHECK((status='ACTIVE')=(left_at IS NULL)), CHECK(status='ACTIVE' OR suspended_until IS NULL)
);
CREATE INDEX membership_user_idx ON community.membership(user_id,status,community_id);
CREATE INDEX membership_staff_idx ON community.membership(community_id,status,role,user_id);
ALTER TABLE community.community ADD CONSTRAINT community_owner_membership_fk
 FOREIGN KEY(id,owner_user_id) REFERENCES community.membership(community_id,user_id) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION community.check_effective_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cid uuid;
BEGIN
 IF TG_TABLE_NAME='community' THEN cid:=COALESCE(NEW.id,OLD.id);
 ELSE cid:=COALESCE(NEW.community_id,OLD.community_id); END IF;
 PERFORM 1 FROM community.community WHERE id=cid FOR UPDATE;
 IF EXISTS(SELECT 1 FROM community.community c WHERE c.id=cid AND c.status='ACTIVE'
   AND NOT EXISTS(SELECT 1 FROM community.membership m WHERE m.community_id=c.id AND m.user_id=c.owner_user_id
     AND m.status='ACTIVE' AND (m.suspended_until IS NULL OR m.suspended_until<=clock_timestamp()))) THEN
   RAISE EXCEPTION 'active community requires effective owner' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER community_effective_owner AFTER INSERT OR UPDATE ON community.community
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION community.check_effective_owner();
CREATE CONSTRAINT TRIGGER membership_effective_owner AFTER INSERT OR UPDATE OR DELETE ON community.membership
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION community.check_effective_owner();
CREATE TABLE community.invitation (
 id uuid PRIMARY KEY, community_id uuid NOT NULL REFERENCES community.community(id),
 invitee_id uuid NOT NULL REFERENCES account.user_account(id), inviter_id uuid NOT NULL REFERENCES account.user_account(id),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
 expires_at timestamptz NOT NULL, responded_at timestamptz, created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK(expires_at>created_at), CHECK((status='PENDING')=(responded_at IS NULL))
);
CREATE UNIQUE INDEX invitation_pending_uq ON community.invitation(community_id,invitee_id) WHERE status='PENDING';
CREATE INDEX invitation_inbox_idx ON community.invitation(invitee_id,status,created_at,id);
CREATE INDEX invitation_expiry_idx ON community.invitation(status,expires_at);
CREATE INDEX invitation_community_idx ON community.invitation(community_id,id);
CREATE INDEX invitation_inviter_idx ON community.invitation(inviter_id);
CREATE TABLE community.join_request (
 id uuid PRIMARY KEY, community_id uuid NOT NULL REFERENCES community.community(id), user_id uuid NOT NULL REFERENCES account.user_account(id),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 message varchar(1000), decided_by uuid REFERENCES account.user_account(id), decided_at timestamptz,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK((status='PENDING')=(decided_at IS NULL)), CHECK((status='PENDING')=(decided_by IS NULL))
);
CREATE UNIQUE INDEX request_pending_uq ON community.join_request(community_id,user_id) WHERE status='PENDING';
CREATE INDEX request_review_idx ON community.join_request(community_id,status,created_at,id);
CREATE INDEX request_user_idx ON community.join_request(user_id,status,created_at,id);
CREATE INDEX request_decider_idx ON community.join_request(decided_by);
CREATE SCHEMA community_api;
CREATE VIEW community_api.communities_v1 AS SELECT id AS community_id,visibility,status,owner_user_id FROM community.community;
CREATE VIEW community_api.memberships_v1 AS SELECT community_id,user_id,role,status,suspended_until FROM community.membership;
