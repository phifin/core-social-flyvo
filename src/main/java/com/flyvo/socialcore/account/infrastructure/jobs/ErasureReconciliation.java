package com.flyvo.socialcore.account.infrastructure.jobs;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.application.Erasures;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ErasureReconciliation implements JobHandler {
  private final Erasures erasures;
  private final DurableWork work;
  private final Clock clock;

  public ErasureReconciliation(Erasures erasures, DurableWork work, Clock clock) {
    this.erasures = erasures;
    this.work = work;
    this.clock = clock;
  }

  public String kind() {
    return "ERASURE_RECONCILE";
  }

  public void apply(String payload) {
    erasures.reconcile(UUID.fromString(payload));
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void enqueue() {
    work.job("ERASURE_SCAN", clock.instant().getEpochSecond() / 60 + "", "START", clock.instant());
  }

  @Component
  @ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
  public static class Scan implements JobHandler {
    private final AccountErasure erasure;
    private final DurableWork work;
    private final Clock clock;

    public Scan(AccountErasure erasure, DurableWork work, Clock clock) {
      this.erasure = erasure;
      this.work = work;
      this.clock = clock;
    }

    public String kind() {
      return "ERASURE_SCAN";
    }

    public void apply(String payload) {
      var ids =
          erasure.scanSubjects("START".equals(payload) ? null : UUID.fromString(payload), 100);
      for (var id : ids)
        work.job(
            "ERASURE_RECONCILE",
            id + ":" + clock.instant().getEpochSecond() / 60,
            id.toString(),
            clock.instant());
      if (ids.size() == 100)
        work.job(
            "ERASURE_SCAN",
            UUID.randomUUID().toString(),
            ids.getLast().toString(),
            clock.instant());
    }
  }
}
