package com.flyvo.socialcore.content.application.port;

import com.flyvo.socialcore.content.api.ContentNotificationSources;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface NotificationSourceRepository {
  List<ContentNotificationSources.Source> inspect(
      ContentNotificationSources.Type type, List<UUID> ids);

  List<UUID> ownedTargets(ContentNotificationSources.Type type, UUID owner, UUID after, int limit);

  Set<UUID> eligible(
      UUID recipient,
      ContentNotificationSources.Type type,
      String kind,
      List<UUID> ids,
      Instant now);
}
