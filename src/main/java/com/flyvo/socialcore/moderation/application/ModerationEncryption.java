package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.moderation.application.port.CaseStore;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class ModerationEncryption implements JobHandler {
  private final CaseStore store;
  private final ActionPayloads payloads;
  private final DurableWork work;
  private final ModerationTelemetry metrics;
  private final Clock clock;

  public ModerationEncryption(
      CaseStore store,
      ActionPayloads payloads,
      DurableWork work,
      ModerationTelemetry metrics,
      Clock clock) {
    this.store = store;
    this.payloads = payloads;
    this.work = work;
    this.metrics = metrics;
    this.clock = clock;
  }

  public String kind() {
    return "MODERATION_ENCRYPT";
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(String payload) {
    try {
      var root = store.legacyRoot();
      if (root == null) {
        metrics.committed("encrypted", 0, store.progress());
        return;
      }
      var c = store.control(root, true);
      if (c == null) throw new IllegalStateException("MODERATION_ROOT_MISSING");
      var rows = store.legacy(root, 100);
      for (var row : rows) {
        var encrypted = payloads.encrypt(row.id(), row.legacy());
        store.encrypted(row.id(), encrypted.ciphertext(), encrypted.keyId());
      }
      if (!rows.isEmpty())
        work.auditScoped(
            null,
            "MODERATION_LEGACY_ENCRYPTED",
            "moderation",
            root.id(),
            c.community(),
            "SUCCESS",
            null);
      metrics.committed("encrypted", rows.size(), store.progress());
      if (store.legacyRoot() != null)
        work.job(kind(), "continuation:" + UUID.randomUUID(), "{}", clock.instant());
    } catch (RuntimeException failure) {
      metrics.failure("encrypt");
      throw failure;
    }
  }
}
