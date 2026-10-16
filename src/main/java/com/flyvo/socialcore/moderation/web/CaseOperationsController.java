package com.flyvo.socialcore.moderation.web;

import com.flyvo.socialcore.moderation.api.CaseOperations;
import com.flyvo.socialcore.platform.api.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class CaseOperationsController {
  private final CaseOperations operations;

  CaseOperationsController(CaseOperations operations) {
    this.operations = operations;
  }

  record Assignment(UUID assignedTo) {}

  @PutMapping({
    "/api/v1/communities/{community}/moderation/reports/{report}/assignment",
    "/api/v1/admin/moderation/reports/{report}/assignment"
  })
  ResponseEntity<CaseOperations.CaseView> assign(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID report,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Assignment input) {
    var r =
        operations.assign(
            actor, community, report, ResourceVersions.require(match), input.assignedTo());
    return ResponseEntity.ok().eTag("\"" + r.version() + "\"").body(r);
  }

  @GetMapping({
    "/api/v1/communities/{community}/moderation/reports/{report}/history",
    "/api/v1/admin/moderation/reports/{report}/history"
  })
  CaseOperations.History history(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID report,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return operations.history(actor, community, report, size, offset);
  }

  @PutMapping({
    "/api/v1/communities/{community}/moderation/reports/{id}/hold",
    "/api/v1/admin/moderation/reports/{id}/hold"
  })
  ResponseEntity<CaseOperations.Control> caseHold(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody CaseOperations.Hold input) {
    return hold(actor, community, CaseOperations.Root.REPORT, id, match, input);
  }

  @PutMapping({
    "/api/v1/communities/{community}/moderation/actions/{id}/hold",
    "/api/v1/admin/moderation/actions/{id}/hold"
  })
  ResponseEntity<CaseOperations.Control> actionHold(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody CaseOperations.Hold input) {
    return hold(actor, community, CaseOperations.Root.ACTION, id, match, input);
  }

  private ResponseEntity<CaseOperations.Control> hold(
      ActorContext actor,
      UUID community,
      CaseOperations.Root type,
      UUID id,
      String match,
      CaseOperations.Hold input) {
    var r = operations.hold(actor, community, type, id, ResourceVersions.require(match), input);
    return ResponseEntity.ok().eTag("\"" + r.version() + "\"").body(r);
  }

  @GetMapping({
    "/api/v1/communities/{community}/moderation/actions/{id}",
    "/api/v1/admin/moderation/actions/{id}"
  })
  CaseOperations.ActionView action(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable(required = false) UUID community,
      @PathVariable UUID id) {
    return operations.action(actor, community, id);
  }

  @PostMapping("/api/v1/admin/moderation/actions")
  ResponseEntity<CaseOperations.AccountResult> account(
      @AuthenticationPrincipal ActorContext actor,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody CaseOperations.AccountRequest input) {
    var r = operations.account(actor, ResourceVersions.require(match), input);
    return ResponseEntity.ok().eTag("\"" + r.account().version() + "\"").body(r);
  }

  @GetMapping("/api/v1/admin/moderation/accounts/{id}")
  ResponseEntity<CaseOperations.AccountState> accountState(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    var r = operations.accountState(actor, id);
    return ResponseEntity.ok().eTag("\"" + r.version() + "\"").body(r);
  }

  @GetMapping("/api/v1/admin/moderation/maintenance/{operation}")
  CaseOperations.Progress progress(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable CaseOperations.Maintenance operation) {
    return operations.maintenance(actor, operation, false);
  }

  @PostMapping("/api/v1/admin/moderation/maintenance/{operation}")
  CaseOperations.Progress resume(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable CaseOperations.Maintenance operation) {
    return operations.maintenance(actor, operation, true);
  }
}
