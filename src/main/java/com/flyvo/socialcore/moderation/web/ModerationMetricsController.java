package com.flyvo.socialcore.moderation.web;

import com.flyvo.socialcore.moderation.api.CaseOperations;
import com.flyvo.socialcore.platform.api.*;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ModerationMetricsController {
  private final CaseOperations operations;
  private final ObjectProvider<PrometheusMeterRegistry> metrics;

  ModerationMetricsController(
      CaseOperations operations, ObjectProvider<PrometheusMeterRegistry> metrics) {
    this.operations = operations;
    this.metrics = metrics;
  }

  @GetMapping(
      value = "/api/v1/admin/moderation/metrics",
      produces = "text/plain;version=0.0.4;charset=utf-8")
  String scrape(@AuthenticationPrincipal ActorContext actor) {
    operations.authorizeMetrics(actor);
    var exporter = metrics.getIfAvailable();
    if (exporter == null) throw new Failure(503, "METRICS_EXPORTER_UNAVAILABLE");
    return exporter.scrape();
  }
}
