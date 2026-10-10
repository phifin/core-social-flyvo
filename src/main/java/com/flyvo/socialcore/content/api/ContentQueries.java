package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.media.api.MediaQueries;
import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ContentQueries {
  record PostView(
      UUID id,
      UUID authorId,
      String displayName,
      UUID communityId,
      String body,
      String linkUrl,
      String audience,
      List<UUID> recipientIds,
      List<ContentCommands.Mention> mentions,
      List<String> hashtags,
      List<ContentCommands.Attachment> media,
      Instant publishedAt,
      Instant editedAt,
      int revision,
      long version) {}

  record Revision(
      int number,
      UUID editorId,
      String body,
      String linkUrl,
      ContentCommands.Audience audience,
      List<ContentCommands.Attachment> media,
      Instant editedAt) {}

  record Page<T>(List<T> items, String nextCursor) {}

  PostView read(ActorContext actor, UUID post);

  Page<PostView> posts(ActorContext actor, UUID author, UUID community, int size, String after);

  Page<Revision> revisions(ActorContext actor, UUID post, int size, int before);

  List<MediaQueries.Variant> mediaAccess(
      ActorContext actor, UUID post, UUID media, Integer revision);

  List<PostView> hydrate(ActorContext actor, List<UUID> posts);
}
