package com.flyvo.socialcore.moderation.web;

import com.flyvo.socialcore.moderation.application.CommunityPostModeration;
import com.flyvo.socialcore.platform.api.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class PostModerationController {
  private final CommunityPostModeration moderation;

  PostModerationController(CommunityPostModeration moderation) {
    this.moderation = moderation;
  }

  public record Action(String action, String reason) {}

  @PostMapping("/api/v1/communities/{community}/moderation/posts/{post}/actions")
  ResponseEntity<CommunityPostModeration.Result> apply(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID community,
      @PathVariable UUID post,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Action request) {
    var r =
        moderation.apply(
            actor,
            community,
            post,
            ResourceVersions.require(match),
            request.action(),
            request.reason());
    return ResponseEntity.ok().eTag("\"" + r.version() + "\"").body(r);
  }
}
