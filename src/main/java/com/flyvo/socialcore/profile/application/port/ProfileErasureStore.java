package com.flyvo.socialcore.profile.application.port;

import java.time.Instant;
import java.util.UUID;

public interface ProfileErasureStore {
  Long erase(UUID user, Instant reserveUntil);

  int expireReservations();
}
