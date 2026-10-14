package com.flyvo.socialcore.notification.application.port;

import com.flyvo.socialcore.notification.api.NotificationQueries.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InboxRepository {
  record Group(
      UUID id,
      UUID recipient,
      Kind kind,
      String targetType,
      UUID targetId,
      String key,
      long activityVersion,
      long readVersion,
      Instant lastActivity) {}

  record Contribution(
      UUID id,
      UUID notification,
      UUID actor,
      String sourceType,
      UUID sourceId,
      long sourceVersion) {}

  record Change(
      UUID eventId,
      UUID recipient,
      Kind kind,
      String sourceType,
      UUID sourceId,
      UUID actor,
      long sourceVersion,
      String targetType,
      UUID targetId,
      boolean active,
      String reactionType,
      boolean newActivity,
      Instant occurredAt) {}

  record Key(Instant time, UUID id) {}

  void guard(UUID recipient, Instant now);

  boolean apply(Change change, Instant now);

  List<Group> scan(UUID recipient, int limit, Instant cutoff, Key after, boolean unread);

  Group find(UUID recipient, UUID id);

  List<Contribution> contributions(List<UUID> notifications, int limit, UUID after);

  void read(UUID recipient, UUID id, long observed);

  Map<Kind, Boolean> preferences(UUID recipient);

  void preferences(UUID recipient, Map<Kind, Boolean> changes, Instant now);

  long watermark(UUID recipient);

  int prune(Instant now);
}
