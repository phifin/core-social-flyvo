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

  /** Guarded USER report proof: originating transaction required; no staff bypass. */
  Summary visible(ActorContext actor, UUID userId, boolean reportProof);

  record Attribution(UUID userId, String displayName) {}

  /** Internal minimal attribution only AFTER containing-content authorization. */
  java.util.List<Attribution> attributionSummaries(java.util.List<UUID> userIds);
}
