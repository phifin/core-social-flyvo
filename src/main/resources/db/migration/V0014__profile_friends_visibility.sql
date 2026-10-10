ALTER TABLE profile.profile DROP CONSTRAINT profile_visibility_check;
ALTER TABLE profile.profile ADD CONSTRAINT profile_visibility_check CHECK(visibility IN ('PUBLIC','AUTHENTICATED','FRIENDS','PRIVATE'));
