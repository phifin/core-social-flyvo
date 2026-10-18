package com.flyvo.socialcore.profile.application.port;

import com.flyvo.socialcore.profile.domain.Preferences;
import com.flyvo.socialcore.profile.domain.Profile;
import java.util.UUID;

public interface ProfileRepository {
  Profile find(UUID user, boolean lock);

  Preferences preferences(UUID user, boolean lock);

  void add(Profile profile, Preferences preferences);

  void flush();

  java.util.List<Profile> summaries(java.util.List<UUID> users);

  java.util.List<UUID> scan(UUID after, int limit);

  java.util.Map<UUID, Long> tombstones(java.util.List<UUID> ids);
}
