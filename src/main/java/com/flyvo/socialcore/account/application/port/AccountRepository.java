package com.flyvo.socialcore.account.application.port;

import com.flyvo.socialcore.account.domain.UserAccount;
import java.util.UUID;

public interface AccountRepository {
  UserAccount find(UUID id);

  UserAccount guard(UUID id, boolean exclusive);

  void add(UserAccount account);

  void flush();
}
