package com.flyvo.socialcore.graph.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal participant routing; notification composes current account/block/mute proof. */
public interface GraphNotificationSources {
  record Source(
      UUID requestId,
      UUID requesterId,
      UUID addresseeId,
      String status,
      Instant expiresAt,
      long pairVersion,
      boolean friends) {}

  List<Source> inspect(List<UUID> requests);
}
