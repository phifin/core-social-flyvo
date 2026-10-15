package com.flyvo.socialcore.account.application.port;

import com.flyvo.socialcore.account.domain.UserAccount;
import java.util.UUID;

public interface AccountRepository {
  UserAccount find(UUID id);

  java.util.Set<UUID> activeIds(java.util.List<UUID> ids);

  java.util.List<UserAccount> snapshots(java.util.List<UUID> ids);

  UserAccount guard(UUID id, boolean exclusive);

  void add(UserAccount account);

  void flush();
}
