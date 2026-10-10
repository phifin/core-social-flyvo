package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.List;
import java.util.UUID;

public interface ContentCommands {
  record Audience(String type, List<UUID> recipientIds) {}

  record Mention(UUID userId, int start, int end) {}

  record Attachment(UUID mediaId, String altText) {}

  record Publish(
      String body,
      String linkUrl,
      UUID communityId,
      Audience audience,
      List<Mention> mentions,
      List<Attachment> media) {}

  record Edit(String body, String linkUrl, List<Mention> mentions, List<Attachment> media) {}

  ContentQueries.PostView publish(ActorContext actor, Publish request, String key);

  ContentQueries.PostView edit(ActorContext actor, UUID post, long version, Edit request);

  ContentQueries.PostView audience(ActorContext actor, UUID post, long version, Audience audience);

  void delete(ActorContext actor, UUID post, long version);
}
