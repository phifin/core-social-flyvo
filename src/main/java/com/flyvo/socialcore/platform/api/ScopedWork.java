package com.flyvo.socialcore.platform.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Opaque producer correlation only; the adapter never decides account policy. */
public interface ScopedWork {
  void mail(
      UUID scope,
      String dedupKey,
      String address,
      String template,
      String secret,
      Instant expiresAt);

  record Drain(int examined, boolean remaining, boolean inFlight, Instant notBefore) {}

  /** No caller transaction: exact MAIL scopes/producer IDs, deterministic job-first locks. */
  Drain cancelMail(UUID scope, List<UUID> proofIds, Instant historicalCut);

  String executionStatus(String kind, UUID executionId);

  java.util.Map<UUID, String> executionStatuses(String kind, List<UUID> executionIds);

  /** Caller already holds the producing account fence. One bounded technical page. */
  int eraseIdempotency(UUID scope);
}
