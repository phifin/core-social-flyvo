package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.community.api.CommunityNotificationSources;
import com.flyvo.socialcore.community.application.port.CommunityRepository;
import com.flyvo.socialcore.platform.api.Failure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityNotificationProofs implements CommunityNotificationSources {
  private final CommunityRepository store;
  private final Clock clock;

  public CommunityNotificationProofs(CommunityRepository store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Source> inspect(List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull))
      throw Failure.invalid();
    return store.notificationSources(ids, clock.instant());
  }
}
