package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Content-owned interactions; every method checks the current containing post. */
public interface Interactions {
  enum ReactionType {
    LIKE,
    LOVE,
    CELEBRATE,
    SUPPORT,
    INSIGHTFUL
  }

  record Text(String body) {}

  record ReactionInput(ReactionType type) {}

  record CommentView(
      UUID id,
      UUID postId,
      UUID rootId,
      UUID authorId,
      String displayName,
      UUID replyToUserId,
      String body,
      boolean tombstone,
      Instant createdAt,
      Instant editedAt,
      Long version) {}

  record ReactionView(UUID id, ReactionType type, long version) {}

  record ActorReaction(UUID actorId, String displayName, ReactionType type) {}

  record ReactionPage(
      Map<ReactionType, Long> counts, List<ActorReaction> items, String nextCursor) {}

  record Engagement(long comments, Map<ReactionType, Long> reactions) {}

  CommentView create(ActorContext actor, UUID post, UUID replyTo, String body, String key);

  CommentView readComment(ActorContext actor, UUID comment);

  ContentQueries.Page<CommentView> comments(
      ActorContext actor, UUID post, UUID root, int size, String after);

  CommentView edit(ActorContext actor, UUID comment, long expected, String body);

  void delete(ActorContext actor, UUID comment, long expected);

  ReactionView react(ActorContext actor, UUID post, UUID comment, ReactionType type);

  void unreact(ActorContext actor, UUID post, UUID comment);

  ReactionPage reactions(ActorContext actor, UUID post, UUID comment, int size, String after);

  Map<UUID, Engagement> engagement(ActorContext actor, List<UUID> posts);
}
