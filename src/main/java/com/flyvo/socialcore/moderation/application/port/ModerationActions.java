package com.flyvo.socialcore.moderation.application.port;

import java.time.Instant;
import java.util.UUID;

public interface ModerationActions {
  void append(
      UUID id,
      UUID actor,
      UUID community,
      UUID target,
      String action,
      String reason,
      String before,
      String after,
      String requestId,
      Instant now);
}
