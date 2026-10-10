package com.flyvo.socialcore.platform.api;

import java.util.UUID;

public interface Idempotency {
  UUID claim(UUID actor, String route, String key, String request);

  void complete(UUID actor, String route, String key, UUID reference);
}
