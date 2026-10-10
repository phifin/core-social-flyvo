package com.flyvo.socialcore.community.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.UUID;

public interface AdmissionCommands {
  record Invite(
      UUID id,
      UUID communityId,
      UUID inviteeId,
      UUID inviterId,
      String status,
      Instant expiresAt,
      long version) {}

  record JoinRequest(
      UUID id, UUID communityId, UUID userId, String status, String message, long version) {}

  Invite invite(ActorContext actor, UUID community, UUID invitee, String key);

  Invite respondInvite(ActorContext actor, UUID id, String action);

  JoinRequest request(ActorContext actor, UUID community, String message, String key);

  JoinRequest respondRequest(ActorContext actor, UUID id, String action);
}
