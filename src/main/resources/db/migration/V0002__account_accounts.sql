CREATE SCHEMA account;
CREATE TABLE account.user_account (
 id uuid PRIMARY KEY, status varchar(24) NOT NULL CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DELETION_PENDING','DELETED')),
 platform_role varchar(16) NOT NULL DEFAULT 'USER' CHECK (platform_role IN ('USER','ADMIN')),
 deletion_requested_at timestamptz, suspended_until timestamptz,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
 CHECK ((status IN ('DELETION_PENDING','DELETED')) = (deletion_requested_at IS NOT NULL))
);
CREATE INDEX account_lifecycle_idx ON account.user_account(status,deletion_requested_at);
