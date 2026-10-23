package com.flyvo.socialcore.media.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Media-owned inventory, generation fence and once-only accounting. */
public interface MediaErasureStore {
  List<UUID> scan(UUID owner, UUID after, int limit);

  UUID nextUnpurged(UUID owner);

  void claim(UUID owner, UUID request, UUID asset, Instant now);

  int pruneVariants(UUID asset, int limit);

  boolean complete(
      UUID owner, UUID request, UUID asset, long expectedVersion, String drainDigest, Instant now);

  boolean residual(UUID owner);
}
