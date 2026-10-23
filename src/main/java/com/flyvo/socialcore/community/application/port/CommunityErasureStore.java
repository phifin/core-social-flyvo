package com.flyvo.socialcore.community.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded owner-only traversal; rows are rechecked after account and community guards. */
public interface CommunityErasureStore {
  record Item(String kind, UUID id, UUID communityId, UUID otherUser) {}

  boolean ownsActive(UUID subject);

  List<Item> pending(UUID subject, int limit);

  void guardCommunities(List<UUID> ids);

  boolean erase(UUID subject, Item item, Instant now);
}
