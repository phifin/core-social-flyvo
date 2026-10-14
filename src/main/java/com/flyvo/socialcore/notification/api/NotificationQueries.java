package com.flyvo.socialcore.notification.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NotificationQueries {
  enum Kind {
    FRIEND_REQUEST,
    FRIENDSHIP_ACCEPTED,
    COMMUNITY_INVITATION,
    COMMENT_ON_POST,
    REPLY_TO_COMMENT,
    MENTION,
    REACTION_ON_CONTENT
  }

  record Actor(UUID userId, String displayName) {}

  record Item(
      UUID id,
      Kind kind,
      String targetType,
      UUID targetId,
      long activityVersion,
      long readVersion,
      Instant lastActivityAt,
      long actorCount,
      List<Actor> actors) {}

  record Page(List<Item> items, String nextCursor) {}

  Page inbox(ActorContext recipient, int size, String after);

  long unread(ActorContext recipient);

  Map<Kind, Boolean> preferences(ActorContext recipient);
}
