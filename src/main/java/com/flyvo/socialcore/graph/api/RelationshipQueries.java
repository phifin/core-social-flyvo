package com.flyvo.socialcore.graph.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.List;
import java.util.UUID;

public interface RelationshipQueries {
  record Facts(
      UUID userId,
      boolean following,
      boolean followedBy,
      boolean friends,
      boolean blocked,
      boolean muted) {}

  record Page<T>(List<T> items, String nextCursor) {}

  /** Internal current source facts; callers compose account/resource authorization. Maximum 100. */
  List<Facts> facts(UUID viewer, List<UUID> users);

  /**
   * Caller holds sorted account guards first. Requires a transaction; shared pair guards sorted by
   * key.
   */
  void guardInteractions(ActorContext actor, List<UUID> users);

  /** Internal profile consumer: caller must authorize the owner's profile first. */
  Page<UUID> visibleEdges(ActorContext viewer, UUID owner, String kind, int size, String after);

  Page<UUID> ownEdges(ActorContext actor, String kind, int size, String after);
}
