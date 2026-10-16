package com.flyvo.socialcore.account.api;

import java.util.UUID;

public interface AccountQueries {
  record Account(
      UUID id, String status, String platformRole, long version, long authenticationEpoch) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  Account find(UUID userId);

  java.util.Set<UUID> activeIds(java.util.List<UUID> userIds);

  /** Bounded current lifecycle versions, never identity credentials. */
  java.util.Map<UUID, Account> snapshots(java.util.List<UUID> userIds);

  Account guard(UUID userId, boolean exclusive);
}
