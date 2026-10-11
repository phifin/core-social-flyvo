package com.flyvo.socialcore.feed.web;

import com.flyvo.socialcore.feed.api.FeedQueries;
import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class FeedController {
  private final FeedQueries feeds;

  FeedController(FeedQueries feeds) {
    this.feeds = feeds;
  }

  private ActorContext actor(ActorContext actor) {
    return actor == null ? ActorContext.anonymous() : actor;
  }

  @GetMapping("/api/v1/feeds/home")
  FeedQueries.Page home(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return feeds.home(actor(actor), size, after);
  }

  @GetMapping("/api/v1/communities/{community}/feed")
  FeedQueries.Page community(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID community,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return feeds.community(actor(actor), community, size, after);
  }

  @GetMapping("/api/v1/users/{user}/timeline")
  FeedQueries.Page timeline(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID user,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return feeds.timeline(actor(actor), user, size, after);
  }
}
