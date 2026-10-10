CREATE SCHEMA media;
CREATE TABLE media.quota (
 owner_id uuid PRIMARY KEY REFERENCES account.user_account(id) ON DELETE RESTRICT,
 reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(reserved_bytes>=0),
 retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes>=0),
 window_start timestamptz NOT NULL, issued_count int NOT NULL DEFAULT 0 CHECK(issued_count>=0),
 version bigint NOT NULL DEFAULT 0
);
CREATE TABLE media.asset (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES account.user_account(id) ON DELETE RESTRICT,
 purpose varchar(16) NOT NULL CHECK(purpose IN ('POST','AVATAR')),
 state varchar(24) NOT NULL CHECK(state IN ('UPLOAD_PENDING','UPLOADED','PROCESSING','READY','EXPIRED','REJECTED','FAILED','DELETING','DELETED')),
 quarantine_key varchar(512) NOT NULL UNIQUE, uploaded_version_id varchar(256),
 declared_mime varchar(32) NOT NULL CHECK(declared_mime IN ('image/jpeg','image/png','image/webp')),
 declared_bytes bigint NOT NULL CHECK(declared_bytes BETWEEN 1 AND 10485760),
 sha256 varchar(44) NOT NULL, retained_bytes bigint NOT NULL DEFAULT 0 CHECK(retained_bytes>=0),
 width int CHECK(width>0), height int CHECK(height>0), processing_generation bigint NOT NULL DEFAULT 0,
 failure_code varchar(64), upload_expires_at timestamptz NOT NULL, orphan_expires_at timestamptz NOT NULL,
 finalized_at timestamptz, ready_at timestamptz, purge_after timestamptz,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CHECK(state NOT IN ('UPLOADED','PROCESSING','READY') OR uploaded_version_id IS NOT NULL),
 CHECK(state<>'READY' OR (width IS NOT NULL AND height IS NOT NULL AND ready_at IS NOT NULL))
);
CREATE INDEX asset_owner ON media.asset(owner_id,created_at,id);
CREATE INDEX asset_cleanup ON media.asset(state,purge_after,orphan_expires_at);
CREATE TABLE media.variant (
 id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES media.asset(id) ON DELETE RESTRICT,
 name varchar(16) NOT NULL CHECK(name IN ('DISPLAY','THUMBNAIL')), object_key varchar(512) NOT NULL UNIQUE,
 object_version_id varchar(256) NOT NULL CHECK(object_version_id<>'null'), mime varchar(32) NOT NULL CHECK(mime IN ('image/png','image/jpeg')),
 bytes bigint NOT NULL CHECK(bytes>0), width int NOT NULL CHECK(width>0), height int NOT NULL CHECK(height>0),
 generation bigint NOT NULL, UNIQUE(asset_id,name,generation)
);
CREATE TABLE media.binding (
 asset_id uuid PRIMARY KEY REFERENCES media.asset(id) ON DELETE RESTRICT,
 context_type varchar(16) NOT NULL CHECK(context_type IN ('POST','AVATAR')), context_id uuid NOT NULL,
 bound_at timestamptz NOT NULL, released_at timestamptz
);
CREATE INDEX binding_context ON media.binding(context_type,context_id);
