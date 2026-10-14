package com.flyvo.socialcore.notification.infrastructure.jobs;

import com.flyvo.socialcore.notification.application.port.InboxRepository;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetention implements JobHandler {
  private final InboxRepository store;
  private final DurableWork work;
  private final Clock clock;

  public NotificationRetention(InboxRepository store, DurableWork work, Clock clock) {
    this.store = store;
    this.work = work;
    this.clock = clock;
  }

  public String kind() {
    return "NOTIFICATION_RETENTION";
  }

  public void apply(String payload) {
    store.prune(clock.instant());
  }

  @Scheduled(fixedDelay = 60_000)
  @Transactional
  public void enqueue() {
    work.job(kind(), Long.toString(clock.instant().getEpochSecond() / 60), "{}", clock.instant());
  }
}
