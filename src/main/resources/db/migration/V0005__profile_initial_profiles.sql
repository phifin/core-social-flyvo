CREATE SCHEMA profile;
CREATE TABLE profile.profile (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id), handle varchar(30) NOT NULL, handle_normalized varchar(30) NOT NULL UNIQUE,
 display_name varchar(80) NOT NULL CHECK(length(display_name)>0), bio varchar(1000) NOT NULL DEFAULT '',
 visibility varchar(16) NOT NULL CHECK(visibility IN ('PUBLIC','AUTHENTICATED','PRIVATE')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
 CHECK(handle_normalized ~ '^[a-z0-9_]{3,30}$')
);
CREATE TABLE profile.preference (
 user_id uuid PRIMARY KEY REFERENCES account.user_account(id), locale varchar(35) NOT NULL, time_zone varchar(64) NOT NULL,
 default_post_audience varchar(16) NOT NULL CHECK(default_post_audience IN ('PUBLIC','FOLLOWERS','FRIENDS','ONLY_ME')),
 updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0
);
