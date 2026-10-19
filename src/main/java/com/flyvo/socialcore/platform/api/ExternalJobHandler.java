package com.flyvo.socialcore.platform.api;

import java.time.Instant;

/** Technical prepare/effect/ack protocol. Only execute runs outside a transaction. */
public interface ExternalJobHandler {
  /** Execution key is the immutable producer dedup key of the fenced job, not its payload. */
  record Lease(long generation, int attempt, Instant until, String executionKey) {
    public Lease(long generation, int attempt, Instant until) {
      this(generation, attempt, until, null);
    }
  }

  /** Durable scheduling wait, not a failed external attempt. Prepare must have no effects. */
  final class Deferred extends RuntimeException {
    private final Instant until;

    public Deferred(Instant until) {
      super("EXTERNAL_JOB_DEFERRED", null, false, false);
      this.until = java.util.Objects.requireNonNull(until);
    }

    public Instant until() {
      return until;
    }
  }

  String kind();

  String prepare(String payload, Lease lease);

  String execute(String prepared);

  void complete(String payload, Lease lease, String prepared, String result);

  void failed(String payload, Lease lease);
}
