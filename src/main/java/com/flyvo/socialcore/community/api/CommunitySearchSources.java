package com.flyvo.socialcore.community.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.List;
import java.util.UUID;

/**
 * Raw current projection snapshots are internal; only hydrate returns authorized discovery cards.
 */
public interface CommunitySearchSources {
  record Source(
      UUID id, long version, boolean active, String slug, String name, String description) {}

  record Card(
      UUID id, String slug, String name, String description, String visibility, long version) {}

  List<UUID> scan(UUID after, int limit);

  List<Source> inspect(List<UUID> ids);

  List<Card> hydrate(ActorContext actor, List<UUID> ids);
}
