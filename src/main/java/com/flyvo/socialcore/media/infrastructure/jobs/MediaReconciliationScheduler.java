package com.flyvo.socialcore.media.infrastructure.jobs;

import com.flyvo.socialcore.platform.api.DurableWork;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
class MediaReconciliationScheduler {
  private final DurableWork work;
  private final Clock clock;

  MediaReconciliationScheduler(DurableWork work, Clock clock) {
    this.work = work;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 3600000)
  @Transactional
  public void enqueue() {
    work.job(
        "MEDIA_QUOTA_RECONCILE",
        "hour:" + clock.instant().getEpochSecond() / 3600,
        "",
        clock.instant());
  }
}
