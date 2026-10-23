package com.flyvo.socialcore.content.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Internal bounded routing/current source proof. Not a public source lookup. */
public interface ContentNotificationSources {
  enum Type {
    POST,
    COMMENT,
    REACTION
  }

  record Source(
      UUID id,
      long version,
      boolean active,
      UUID actorId,
      String targetType,
      UUID targetId,
      UUID postId,
      UUID targetAuthorId,
      UUID postAuthorId,
      UUID rootAuthorId,
      UUID replyToUserId,
      List<UUID> mentionedUsers,
      String reactionType) {}

  List<Source> inspect(Type type, List<UUID> sourceIds);

  /** Restricted owner-root IDs for bounded notification cleanup and moderation inventory. */
  List<UUID> ownedTargets(Type type, UUID owner, UUID after, int limit);

  /** Current parent/actor policy plus the recipient's specific route, no text fallback. */
  Set<UUID> eligible(UUID recipient, Type type, String kind, List<UUID> sourceIds);
}
