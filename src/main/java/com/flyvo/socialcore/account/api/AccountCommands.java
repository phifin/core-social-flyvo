package com.flyvo.socialcore.account.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;

public interface AccountCommands {
  UUID createVerified();

  void requestDeletion(UUID userId);

  void suspend(ActorContext actor, UUID target, String reason);

  record Change(UUID userId, String before, String after, long version) {}

  Change changeStatus(
      ActorContext actor, UUID target, long expectedVersion, boolean suspend, String reasonCode);
}
