package com.flyvo.socialcore.identity.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;

/** ADR-015: existing verified JWT output, never a session authorization cache. */
public interface StreamAuthentication {
  record Proof(ActorContext actor, Instant expiresAt) {}

  Proof verify(String accessToken);
}
