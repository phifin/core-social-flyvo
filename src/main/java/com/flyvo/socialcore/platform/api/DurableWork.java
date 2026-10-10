package com.flyvo.socialcore.platform.api;

import java.time.Instant;
import java.util.UUID;

public interface DurableWork {
  void job(String kind, String dedupKey, String payload, Instant availableAt);

  void mail(String dedupKey, String address, String template, String secret, Instant expiresAt);

  void audit(
      UUID actor, String action, String targetType, UUID target, String outcome, String reason);

  void auditScoped(
      UUID actor,
      String action,
      String targetType,
      UUID target,
      UUID scope,
      String outcome,
      String reason);

  void event(
      String type, String aggregateType, UUID aggregateId, long version, int sequence, String json);
}
