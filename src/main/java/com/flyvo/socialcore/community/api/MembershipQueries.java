package com.flyvo.socialcore.community.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MembershipQueries {
  record Facts(
      UUID communityId,
      UUID userId,
      String role,
      String status,
      Instant suspendedUntil,
      long version) {}

  List<Facts> facts(UUID userId, List<UUID> communityIds);

  record Scope(UUID communityId, String visibility, String status, UUID ownerId) {}

  List<Scope> scopes(List<UUID> communityIds);

  /** Current scope guard; staff requires effective MODERATOR+, write denies archive. */
  Scope guardScope(ActorContext actor, UUID communityId, boolean staff, boolean write);

  /**
   * Requires a transaction and all relevant account guards acquired before this community guard.
   */
  void guardWrite(ActorContext actor, UUID communityId);

  boolean ownsActiveCommunity(UUID userId);
}
