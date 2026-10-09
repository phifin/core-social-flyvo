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
      long version) {}

  Summary visible(ActorContext actor, UUID userId);
}
