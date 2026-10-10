package com.flyvo.socialcore.profile.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;

public interface ProfileQueries {
  record Summary(
      UUID userId,
      String handle,
      String displayName,
      String bio,
      String visibility,
      long version,
      UUID avatarMediaId) {}

  Summary visible(ActorContext actor, UUID userId);

  record Attribution(UUID userId, String displayName) {}

  /** Internal minimal attribution only AFTER containing-content authorization. */
  java.util.List<Attribution> attributionSummaries(java.util.List<UUID> userIds);
}
