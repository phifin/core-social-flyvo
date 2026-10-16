package com.flyvo.socialcore.moderation.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Reports {
  enum Target {
    POST,
    COMMENT,
    COMMUNITY,
    USER
  }

  enum Category {
    SPAM,
    HARASSMENT,
    HATE,
    VIOLENCE,
    SEXUAL_CONTENT,
    OTHER
  }

  enum Action {
    REVIEW_REPORT,
    RESOLVE_REPORT,
    DISMISS_REPORT,
    REMOVE_CONTENT,
    RESTORE_CONTENT,
    SUSPEND_MEMBER,
    BAN_MEMBER,
    UNBAN_MEMBER
  }

  record Submit(Target targetType, UUID targetId, Category category, String detail) {}

  record View(
      UUID id,
      Target targetType,
      UUID targetId,
      UUID communityId,
      Category category,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      long version) {}

  record Page(List<View> items, String nextCursor) {}

  record Evidence(
      View report, String detail, Instant capturedAt, String summary, boolean redacted) {}

  record Resolution(
      UUID reportId, Action action, String reason, Long sourceVersion, Instant suspendedUntil) {}

  record Result(UUID actionId, View report) {}

  View submit(ActorContext actor, Submit request, String key);

  Page own(ActorContext actor, int size, int offset);

  Page queue(ActorContext actor, UUID communityId, int size, int offset);

  Evidence evidence(ActorContext actor, UUID communityId, UUID reportId);

  Result resolve(ActorContext actor, UUID communityId, long expected, Resolution request);
}
