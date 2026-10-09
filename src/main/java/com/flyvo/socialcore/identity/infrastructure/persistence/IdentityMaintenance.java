package com.flyvo.socialcore.identity.infrastructure.persistence;

import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
class IdentityMaintenance {
  private final IdentityRepository store;
  private final Clock clock;

  IdentityMaintenance(IdentityRepository store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 3600000)
  @Transactional
  public void prune() {
    store.prune(clock.instant());
  }
}
