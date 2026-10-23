package com.flyvo.socialcore.notification.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationErasureStore {
  record Item(UUID recipient, UUID id, String kind, String type, UUID source, UUID actor) {}

  record Batch(String table, List<Item> items) {}

  void terminal(UUID subject, Instant now);

  Batch pending(UUID subject, String targetType, UUID target);

  void erase(Batch batch, Instant now);

  int preferences(UUID subject);
}
