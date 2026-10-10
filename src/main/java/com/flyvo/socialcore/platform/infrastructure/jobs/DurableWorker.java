package com.flyvo.socialcore.platform.infrastructure.jobs;

import com.flyvo.socialcore.platform.application.port.MailTransport;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DurableWorker {
  private final DurableWorkStore store;
  private final MailTransport mail;
  private final Clock clock;
  private final java.util.List<com.flyvo.socialcore.platform.api.JobHandler> handlers;
  private final UUID owner = UUID.randomUUID();
  private final io.micrometer.core.instrument.Counter failures;

  public DurableWorker(
      DurableWorkStore store,
      MailTransport mail,
      Clock clock,
      java.util.List<com.flyvo.socialcore.platform.api.JobHandler> handlers,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.store = store;
    this.mail = mail;
    this.clock = clock;
    this.handlers = handlers;
    failures = metrics.counter("social.work.failures");
    for (String status : java.util.List.of("PENDING", "RUNNING", "RETRY", "DEAD"))
      io.micrometer.core.instrument.Gauge.builder(
              "social.work.queue", store, s -> s.queueDepth(status))
          .tag("status", status)
          .register(metrics);
    io.micrometer.core.instrument.Gauge.builder(
            "social.work.oldest.seconds", store, DurableWorkStore::oldestPendingSeconds)
        .register(metrics);
  }

  @Scheduled(fixedDelayString = "${app.worker.poll-ms:1000}")
  public void runOnce() {
    try {
      dispatch();
    } catch (RuntimeException failure) {
      failures.increment();
    }
  }

  private void dispatch() {
    var claim = store.claim(owner);
    if (claim != null) {
      String error = null;
      if (claim.ciphertext() == null || !clock.instant().isBefore(claim.expiresAt()))
        error = "PAYLOAD_EXPIRED";
      else {
        try {
          var payload = store.decode(claim);
          mail.send(claim.id(), payload.address(), payload.template(), payload.secret());
        } catch (RuntimeException failure) {
          error = "SMTP_UNAVAILABLE";
          failures.increment();
        }
      }
      store.finish(claim, error);
    }
    store.consumeOne(owner);
    if (!store.consumeJob(owner, handlers)) failures.increment();
  }

  @Scheduled(fixedDelay = 60000)
  public void prune() {
    try {
      store.prune();
    } catch (RuntimeException failure) {
      failures.increment();
    }
  }
}
