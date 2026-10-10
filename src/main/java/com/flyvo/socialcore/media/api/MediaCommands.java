package com.flyvo.socialcore.media.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MediaCommands {
  /** Caller holds preceding account/scope/post guards; all binding is atomic. */
  void bind(UUID owner, String purpose, UUID context, List<UUID> assets);

  void release(UUID owner, String purpose, UUID context, List<UUID> assets, Instant purgeAfter);
}
