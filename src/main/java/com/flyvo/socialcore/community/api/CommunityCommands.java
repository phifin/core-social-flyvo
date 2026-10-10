package com.flyvo.socialcore.community.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;

public interface CommunityCommands {
  record Info(
      UUID id,
      String slug,
      String name,
      String description,
      String visibility,
      String joinMode,
      String status,
      UUID ownerId,
      long version) {}

  Info create(
      ActorContext actor,
      String slug,
      String name,
      String description,
      String visibility,
      String joinMode,
      String key);

  Info settings(
      ActorContext actor,
      UUID id,
      long version,
      String name,
      String description,
      String visibility,
      String joinMode);

  Info archive(ActorContext actor, UUID id, long version);

  Info transfer(ActorContext actor, UUID id, long version, UUID successor);
}
