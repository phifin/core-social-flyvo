package com.flyvo.socialcore.content.application.port;

import com.flyvo.socialcore.content.api.Interactions;
import com.flyvo.socialcore.content.domain.comment.Comment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InteractionRepository {
  record Row(Comment comment, boolean visible, boolean children, boolean replyVisible) {}

  record Key(UUID id, Instant time) {}

  record Reaction(
      UUID id, UUID actor, Interactions.ReactionType type, boolean active, long version) {}

  void lockPost(UUID post, boolean exclusive);

  Comment find(UUID id, boolean exclusive);

  void lockComment(UUID id, boolean exclusive);

  void insert(Comment comment);

  void save(Comment comment, Instant now);

  void redactDeleted(UUID comment, Instant cutoff, Instant now);

  List<Comment> redactForDeletedPost(UUID post, Instant cutoff, Instant now);

  List<Row> comments(
      UUID viewer,
      UUID post,
      UUID root,
      UUID single,
      int limit,
      Instant asOf,
      Key after,
      Instant now);

  Reaction reaction(UUID actor, UUID post, UUID comment);

  Reaction setReaction(
      UUID actor,
      UUID post,
      UUID comment,
      Interactions.ReactionType type,
      boolean active,
      Instant now);

  List<Reaction> actors(UUID viewer, UUID post, UUID comment, int limit, UUID after, Instant now);

  Map<Interactions.ReactionType, Long> counts(UUID viewer, UUID post, UUID comment, Instant now);

  Map<UUID, Interactions.Engagement> engagement(UUID viewer, List<UUID> posts, Instant now);
}
