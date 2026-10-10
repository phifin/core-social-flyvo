package com.flyvo.socialcore.community.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.UUID;

public interface MembershipCommands {
  MembershipQueries.Facts join(ActorContext actor, UUID community);

  void leave(ActorContext actor, UUID community, long version);

  void remove(ActorContext actor, UUID community, UUID user, long version);

  MembershipQueries.Facts role(
      ActorContext actor, UUID community, UUID user, long version, String role);

  record Sanction(MembershipQueries.Facts before, MembershipQueries.Facts after) {}

  /**
   * Moderation orchestration only: caller must atomically persist attributable action and audit.
   */
  Sanction sanction(
      ActorContext actor, UUID community, UUID user, long version, String action, Instant until);
}
