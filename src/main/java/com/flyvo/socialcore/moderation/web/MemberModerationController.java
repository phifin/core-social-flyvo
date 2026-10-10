package com.flyvo.socialcore.moderation.web;

import com.flyvo.socialcore.moderation.application.MemberModeration;
import com.flyvo.socialcore.platform.api.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class MemberModerationController {
  private final MemberModeration moderation;

  MemberModerationController(MemberModeration moderation) {
    this.moderation = moderation;
  }

  public record Action(UUID userId, String action, String reason, Instant suspendedUntil) {}

  @PostMapping("/api/v1/communities/{id}/moderation/actions")
  ResponseEntity<MemberModeration.Result> action(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Action request) {
    var result =
        moderation.apply(
            actor,
            id,
            request.userId(),
            ResourceVersions.require(match),
            request.action(),
            request.reason(),
            request.suspendedUntil());
    return ResponseEntity.ok().eTag("\"" + result.membership().version() + "\"").body(result);
  }
}
