package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.moderation.api.CaseOperations;
import io.micrometer.core.instrument.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Owned operational snapshots only; never a permission/count cache for resource reads. */
@Component
public class ModerationTelemetry {
  private final AtomicReference<Double> legacy = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> cases = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> actions = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> observed = new AtomicReference<>(Double.NaN);
  private final Map<String, Counter> completed;
  private final Map<String, Counter> failures;

  public ModerationTelemetry(MeterRegistry metrics) {
    Gauge.builder("moderation.legacy.payloads", legacy, AtomicReference::get).register(metrics);
    Gauge.builder("moderation.retention.partial", cases, AtomicReference::get)
        .tag("root", "report")
        .register(metrics);
    Gauge.builder("moderation.retention.partial", actions, AtomicReference::get)
        .tag("root", "action")
        .register(metrics);
    Gauge.builder("moderation.progress.observed.seconds", observed, AtomicReference::get)
        .register(metrics);
    completed =
        Map.of(
            "encrypted",
            metrics.counter("moderation.payloads", "operation", "encrypted"),
            "redacted",
            metrics.counter("moderation.payloads", "operation", "redacted"));
    failures =
        Map.of(
            "encrypt",
            metrics.counter("moderation.maintenance.failures", "operation", "encrypt"),
            "retention",
            metrics.counter("moderation.maintenance.failures", "operation", "retention"));
  }

  public void failure(String operation) {
    failures.get(operation).increment();
  }

  public void committed(String operation, int count, CaseOperations.Progress progress) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("MODERATION_METRICS_REQUIRE_TRANSACTION");
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            if (count > 0) completed.get(operation).increment(count);
            legacy.set((double) progress.legacyPayloads());
            cases.set((double) progress.pendingCaseRoots());
            actions.set((double) progress.pendingActionRoots());
            observed.set(System.currentTimeMillis() / 1000.0);
          }
        });
  }
}
