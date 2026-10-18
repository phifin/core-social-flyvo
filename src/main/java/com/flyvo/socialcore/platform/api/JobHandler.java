package com.flyvo.socialcore.platform.api;

/**
 * Transactional database-only job handler. External work needs a separate prepare/effect/ack
 * contract.
 */
public interface JobHandler {
  String kind();

  void apply(String payload);

  /** Called under the current job-first fence; failure metadata joins its terminal ack. */
  default void failed(String payload, int attempts) {}
}
