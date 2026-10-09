package com.flyvo.socialcore.platform.api;

import java.util.UUID;

public record ActorContext(UUID userId, UUID sessionId) {
  public static ActorContext anonymous() {
    return new ActorContext(null, null);
  }

  public boolean authenticated() {
    return userId != null && sessionId != null;
  }
}
