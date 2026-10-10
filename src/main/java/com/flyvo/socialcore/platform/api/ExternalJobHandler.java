package com.flyvo.socialcore.platform.api;

import java.time.Instant;

/** Technical prepare/effect/ack protocol. Only execute runs outside a transaction. */
public interface ExternalJobHandler {
  record Lease(long generation, int attempt, Instant until) {}

  String kind();

  String prepare(String payload, Lease lease);

  String execute(String prepared);

  void complete(String payload, Lease lease, String prepared, String result);

  void failed(String payload, Lease lease);
}
