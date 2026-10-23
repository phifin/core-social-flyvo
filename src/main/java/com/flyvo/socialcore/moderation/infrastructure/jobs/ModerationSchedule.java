package com.flyvo.socialcore.moderation.infrastructure.jobs;

import com.flyvo.socialcore.platform.api.DurableWork;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ModerationSchedule {
  private final DurableWork work;
  private final Clock clock;
  private final org.springframework.core.env.Environment environment;

  public ModerationSchedule(
      DurableWork work, Clock clock, org.springframework.core.env.Environment environment) {
    this.work = work;
    this.clock = clock;
    this.environment = environment;
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void enqueue() {
    var now = clock.instant();
    String key = Long.toString(now.getEpochSecond() / 60);
    work.job("MODERATION_ENCRYPT", key, "{}", now);
    work.job("MODERATION_RETENTION", key, "{}", now);
    if (environment.getProperty("app.erasure.enabled", Boolean.class, false)
        && now.getEpochSecond() / 60 % 60 == 0) work.job("MODERATION_PRESERVATION", key, "{}", now);
  }
}
