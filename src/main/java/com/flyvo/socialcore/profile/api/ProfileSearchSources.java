package com.flyvo.socialcore.profile.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.List;
import java.util.UUID;

/**
 * Internal projection export is NOT a public directory or authorization proof. All batches ≤100.
 */
public interface ProfileSearchSources {
  record Source(
      UUID id, long version, Long accountVersion, boolean active, String handle, String name) {}

  record Card(UUID id, String handle, String name, long version) {}

  List<UUID> scan(UUID after, int limit);

  List<Source> inspect(List<UUID> ids);

  List<Card> hydrate(ActorContext actor, List<UUID> ids);
}
