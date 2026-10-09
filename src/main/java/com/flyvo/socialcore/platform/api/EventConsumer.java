package com.flyvo.socialcore.platform.api;

import java.util.UUID;

public interface EventConsumer {
  String name();

  boolean accepts(String type);

  void apply(UUID eventId, String type, String payload);
}
