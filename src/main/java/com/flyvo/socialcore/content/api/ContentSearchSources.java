package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Restricted projection exports, and a separate canonical current-policy hydration path. */
public interface ContentSearchSources {
  record Source(
      UUID id,
      UUID authorId,
      UUID communityId,
      long version,
      Long accountVersion,
      boolean active,
      String body) {}

  record Card(
      UUID id,
      UUID authorId,
      String displayName,
      UUID communityId,
      String body,
      Instant publishedAt,
      long version) {}

  List<UUID> scan(UUID after, int limit);

  List<UUID> scanByAuthor(UUID author, UUID after, int limit);

  List<Source> inspect(List<UUID> ids);

  void authorize(ActorContext actor, UUID communityId);

  List<Card> hydrate(ActorContext actor, List<UUID> ids, UUID communityId);
}
