package com.flyvo.socialcore.notification.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.Map;
import java.util.UUID;

public interface NotificationCommands {
  void read(ActorContext recipient, UUID notification, long observedActivityVersion);

  void readAll(ActorContext recipient);

  Map<NotificationQueries.Kind, Boolean> preferences(
      ActorContext recipient, Map<NotificationQueries.Kind, Boolean> changes);
}
