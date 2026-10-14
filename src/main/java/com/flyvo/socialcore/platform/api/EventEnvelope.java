package com.flyvo.socialcore.platform.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable producer metadata; consumers never inspect platform storage directly. */
public record EventEnvelope(
    UUID eventId,
    String type,
    int schemaVersion,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    int eventSequence,
    Instant occurredAt,
    String correlationId,
    UUID causationId,
    String payload) {
  public EventEnvelope {
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(type);
    Objects.requireNonNull(aggregateType);
    Objects.requireNonNull(aggregateId);
    Objects.requireNonNull(occurredAt);
    Objects.requireNonNull(payload);
    if (schemaVersion < 1 || aggregateVersion < 0 || eventSequence < 0)
      throw new IllegalArgumentException("INVALID_EVENT_ENVELOPE");
  }
}
