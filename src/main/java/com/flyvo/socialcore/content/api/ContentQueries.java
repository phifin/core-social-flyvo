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

  enum FeedMode {
    HOME,
    COMMUNITY,
    TIMELINE
  }

  record FeedScope(FeedMode mode, UUID id) {}

  record Candidate(UUID id, Instant publishedAt) {}

  /** Current scope policy, including an empty community/timeline. */
  void authorizeFeed(ActorContext actor, FeedScope scope);

  Instant feedCutoff();

  List<Candidate> scanCandidates(
      ActorContext actor, FeedScope scope, int limit, Instant asOf, Candidate after);

  /** Rechecks source eligibility and current content in one coherent statement. */
  List<PostView> hydrateAuthorized(ActorContext actor, FeedScope scope, List<UUID> ids);

  PostView read(ActorContext actor, UUID post);

  Page<PostView> posts(ActorContext actor, UUID author, UUID community, int size, String after);

  Page<Revision> revisions(ActorContext actor, UUID post, int size, int before);

  List<MediaQueries.Variant> mediaAccess(
      ActorContext actor, UUID post, UUID media, Integer revision);

  List<PostView> hydrate(ActorContext actor, List<UUID> posts);
}
