package com.flyvo.socialcore.platform.api;

public interface EventConsumer {
  String name();

  boolean accepts(String type);

  void apply(EventEnvelope event);
}
