CREATE SCHEMA graph;
CREATE TABLE graph.follow (
 follower_id uuid NOT NULL REFERENCES account.user_account(id), followed_id uuid NOT NULL REFERENCES account.user_account(id),
 created_at timestamptz NOT NULL, PRIMARY KEY(follower_id,followed_id), CHECK(follower_id<>followed_id)
);
CREATE INDEX follow_reverse_idx ON graph.follow(followed_id,follower_id);
CREATE TABLE graph.block (
 blocker_id uuid NOT NULL REFERENCES account.user_account(id), blocked_id uuid NOT NULL REFERENCES account.user_account(id),
 created_at timestamptz NOT NULL, PRIMARY KEY(blocker_id,blocked_id), CHECK(blocker_id<>blocked_id)
);
CREATE INDEX block_reverse_idx ON graph.block(blocked_id,blocker_id);
CREATE TABLE graph.mute (
 muter_id uuid NOT NULL REFERENCES account.user_account(id), muted_id uuid NOT NULL REFERENCES account.user_account(id),
 created_at timestamptz NOT NULL, PRIMARY KEY(muter_id,muted_id), CHECK(muter_id<>muted_id)
);
CREATE INDEX mute_target_idx ON graph.mute(muted_id);
CREATE TABLE graph.pair_revision (
 id uuid PRIMARY KEY, low_user_id uuid NOT NULL REFERENCES account.user_account(id), high_user_id uuid NOT NULL REFERENCES account.user_account(id),
 version bigint NOT NULL CHECK(version>=1), UNIQUE(low_user_id,high_user_id), CHECK(low_user_id<high_user_id)
);
CREATE INDEX pair_high_idx ON graph.pair_revision(high_user_id);
CREATE TABLE graph.friend_request (
 id uuid PRIMARY KEY, requester_id uuid NOT NULL REFERENCES account.user_account(id), addressee_id uuid NOT NULL REFERENCES account.user_account(id),
 low_user_id uuid GENERATED ALWAYS AS (LEAST(requester_id,addressee_id)) STORED,
 high_user_id uuid GENERATED ALWAYS AS (GREATEST(requester_id,addressee_id)) STORED,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED','EXPIRED')),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, resolved_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0), CHECK(requester_id<>addressee_id),
 CHECK(expires_at>created_at), CHECK((status='PENDING')=(resolved_at IS NULL))
);
CREATE UNIQUE INDEX friend_request_pending_uq ON graph.friend_request(low_user_id,high_user_id) WHERE status='PENDING';
CREATE INDEX friend_request_inbox_idx ON graph.friend_request(addressee_id,id);
CREATE INDEX friend_request_outbox_idx ON graph.friend_request(requester_id,id);
CREATE INDEX friend_request_expiry_idx ON graph.friend_request(status,expires_at);
CREATE TABLE graph.friendship (
 low_user_id uuid NOT NULL REFERENCES account.user_account(id), high_user_id uuid NOT NULL REFERENCES account.user_account(id),
 established_at timestamptz NOT NULL, request_id uuid NOT NULL UNIQUE REFERENCES graph.friend_request(id),
 PRIMARY KEY(low_user_id,high_user_id), CHECK(low_user_id<high_user_id)
);
CREATE INDEX friendship_reverse_idx ON graph.friendship(high_user_id,low_user_id);
CREATE SCHEMA graph_api;
CREATE VIEW graph_api.follows_v1 AS SELECT follower_id,followed_id FROM graph.follow;
CREATE VIEW graph_api.friendships_v1 AS SELECT low_user_id,high_user_id FROM graph.friendship;
CREATE VIEW graph_api.blocks_v1 AS SELECT blocker_id,blocked_id FROM graph.block;
CREATE VIEW graph_api.mutes_v1 AS SELECT muter_id,muted_id FROM graph.mute;
