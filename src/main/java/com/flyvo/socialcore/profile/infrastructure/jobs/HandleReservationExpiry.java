package com.flyvo.socialcore.profile.infrastructure.jobs;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.profile.application.port.ProfileErasureStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class HandleReservationExpiry {
  private final ProfileErasureStore store;
  private final AccountErasure erasure;
  private final DurableWork work;

  public HandleReservationExpiry(
      ProfileErasureStore store, AccountErasure erasure, DurableWork work) {
    this.store = store;
    this.erasure = erasure;
    this.work = work;
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void expire() {
    erasure.databaseTime();
    int n = store.expireReservations();
    if (n > 0) work.audit(null, "HANDLE_RESERVATIONS_EXPIRED", "profile", null, "SUCCESS", null);
  }
}
