package com.flyvo.socialcore.graph.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.UUID;

public interface GraphCommands {
  record Request(
      UUID id,
      UUID requesterId,
      UUID addresseeId,
      String status,
      Instant expiresAt,
      long version) {}

  void follow(ActorContext actor, UUID target, boolean enabled);

  void block(ActorContext actor, UUID target, boolean enabled);

  void mute(ActorContext actor, UUID target, boolean enabled);

  void unfriend(ActorContext actor, UUID target);

  Request request(ActorContext actor, UUID target);

  Request respond(ActorContext actor, UUID requestId, String action);

  RelationshipQueries.Page<Request> requests(
      ActorContext actor, String direction, int size, String after);
}
