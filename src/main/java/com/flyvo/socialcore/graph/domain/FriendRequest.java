package com.flyvo.socialcore.graph.domain;

import java.time.Instant;
import java.util.UUID;

public record FriendRequest(
    UUID id,
    UUID requester,
    UUID addressee,
    String status,
    Instant createdAt,
    Instant expiresAt,
    long version) {
  public UserPair pair() {
    return UserPair.of(requester, addressee);
  }

  public boolean live(Instant now) {
    return status.equals("PENDING") && now.isBefore(expiresAt);
  }
}
