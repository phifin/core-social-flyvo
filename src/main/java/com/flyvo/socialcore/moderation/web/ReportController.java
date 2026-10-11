package com.flyvo.socialcore.moderation.web;

import com.flyvo.socialcore.moderation.api.Reports;
import com.flyvo.socialcore.platform.api.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ReportController {
  private final Reports reports;

  ReportController(Reports reports) {
    this.reports = reports;
  }

  record ActionRequest(
      Reports.Action action, String reason, Long sourceVersion, Instant suspendedUntil) {}

  @PostMapping("/api/v1/reports")
  ResponseEntity<Reports.View> submit(
      @AuthenticationPrincipal ActorContext actor,
      @RequestBody Reports.Submit input,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    var r = reports.submit(actor, input, key);
    return ResponseEntity.created(URI.create("/api/v1/me/reports"))
        .eTag("\"" + r.version() + "\"")
        .body(r);
  }

  @GetMapping("/api/v1/me/reports")
  Reports.Page own(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return reports.own(actor, size, offset);
  }

  @GetMapping({
    "/api/v1/communities/{community}/moderation/reports",
    "/api/v1/admin/moderation/reports"
  })
  Reports.Page queue(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return reports.queue(actor, community, size, offset);
  }

  @GetMapping({
    "/api/v1/communities/{community}/moderation/reports/{report}",
    "/api/v1/admin/moderation/reports/{report}"
  })
  ResponseEntity<Reports.Evidence> evidence(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID report) {
    var r = reports.evidence(actor, community, report);
    return ResponseEntity.ok().eTag("\"" + r.report().version() + "\"").body(r);
  }

  @PostMapping({
    "/api/v1/communities/{community}/moderation/reports/{report}/actions",
    "/api/v1/admin/moderation/reports/{report}/actions"
  })
  ResponseEntity<Reports.Result> resolve(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID report,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody ActionRequest input) {
    var r =
        reports.resolve(
            actor,
            community,
            ResourceVersions.require(match),
            new Reports.Resolution(
                report,
                input.action(),
                input.reason(),
                input.sourceVersion(),
                input.suspendedUntil()));
    return ResponseEntity.ok().eTag("\"" + r.report().version() + "\"").body(r);
  }
}
