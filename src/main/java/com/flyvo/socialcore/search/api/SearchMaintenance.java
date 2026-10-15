package com.flyvo.socialcore.search.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.UUID;

public interface SearchMaintenance {
  record Rebuild(
      UUID id,
      SearchQueries.Kind kind,
      String stage,
      long version,
      long examined,
      long changed,
      Instant createdAt,
      Instant completedAt) {}

  Rebuild start(ActorContext actor, SearchQueries.Kind kind, UUID resumeId, Long expectedVersion);

  Rebuild read(ActorContext actor, UUID id);
}
