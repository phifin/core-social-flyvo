package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.moderation.application.port.CaseStore;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class ModerationRetention implements JobHandler {
  private final CaseStore store;
  private final DurableWork work;
  private final ModerationTelemetry metrics;
  private final Clock clock;

  public ModerationRetention(
      CaseStore store, DurableWork work, ModerationTelemetry metrics, Clock clock) {
    this.store = store;
    this.work = work;
    this.metrics = metrics;
    this.clock = clock;
  }

  public String kind() {
    return "MODERATION_RETENTION";
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(String payload) {
    try {
      var root = store.retentionRoot();
      if (root == null) {
        metrics.committed("redacted", 0, store.progress());
        return;
      }
      var c = store.control(root, true);
      int count = store.redact(root, 100);
      if (count > 0)
        work.auditScoped(
            null,
            "MODERATION_PAYLOAD_REDACTED",
            "moderation",
            root.id(),
            c.community(),
            "SUCCESS",
            null);
      metrics.committed("redacted", count, store.progress());
      if (store.retentionRoot() != null)
        work.job(kind(), "continuation:" + UUID.randomUUID(), "{}", clock.instant());
    } catch (RuntimeException failure) {
      metrics.failure("retention");
      throw failure;
    }
  }
}
