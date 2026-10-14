package com.flyvo.socialcore.community.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal invitation route/current admission proof, never a public invitation directory. */
public interface CommunityNotificationSources {
  record Source(
      UUID invitationId,
      UUID communityId,
      UUID inviteeId,
      UUID inviterId,
      String status,
      Instant expiresAt,
      long communityVersion,
      boolean eligible) {}

  List<Source> inspect(List<UUID> invitations);
}
