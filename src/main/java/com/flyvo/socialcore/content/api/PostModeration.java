package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;

public interface PostModeration {
  record Transition(UUID authorId, String before, String after, long version) {}

  /**
   * Scoped staff guard, not ordinary read-policy bypass. Originating moderation transaction
   * required.
   */
  Transition apply(
      ActorContext actor, UUID community, UUID post, long expected, String action, UUID actionId);
}
