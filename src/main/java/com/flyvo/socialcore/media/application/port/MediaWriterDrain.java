package com.flyvo.socialcore.media.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * External evidence boundary. A real provider must prove all accepted ingress and variant writers
 * for this asset have finished, including writes accepted before capability expiry. No runtime
 * implementation is installed by default.
 */
public interface MediaWriterDrain {
  record Proof(UUID asset, long fencedGeneration, Instant observedAt, String digest) {}

  Proof attest(UUID asset, long fencedGeneration, Instant uploadSettlesAt);
}
