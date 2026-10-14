package com.flyvo.socialcore.notification.application;

import com.flyvo.socialcore.notification.api.NotificationQueries.Kind;
import java.util.*;

/** Per-event recipient precedence, preserving deterministic recipient lock order. */
public final class NotificationRoutes {
  private NotificationRoutes() {}

  public record Route(UUID recipient, Kind kind) {}

  private static int priority(Kind kind) {
    return switch (kind) {
      case MENTION -> 3;
      case REPLY_TO_COMMENT -> 2;
      case COMMENT_ON_POST -> 1;
      default -> 0;
    };
  }

  public static List<Route> deduplicate(UUID actor, List<Route> routes) {
    var result = new TreeMap<UUID, Route>(Comparator.comparing(UUID::toString));
    for (var r : routes)
      if (r.recipient() != null && !r.recipient().equals(actor))
        result.merge(r.recipient(), r, (a, b) -> priority(a.kind()) >= priority(b.kind()) ? a : b);
    return List.copyOf(result.values());
  }
}
