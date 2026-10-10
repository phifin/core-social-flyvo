ALTER TABLE profile.profile ADD COLUMN avatar_media_id uuid REFERENCES media.asset(id) ON DELETE RESTRICT;
CREATE INDEX profile_avatar ON profile.profile(avatar_media_id) WHERE avatar_media_id IS NOT NULL;
