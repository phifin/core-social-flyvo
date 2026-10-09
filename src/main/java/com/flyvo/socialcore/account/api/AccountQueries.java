package com.flyvo.socialcore.account.api;

import java.util.UUID;

public interface AccountQueries {
  record Account(UUID id, String status, String platformRole, long version) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  Account find(UUID userId);

  Account guard(UUID userId, boolean exclusive);
}
