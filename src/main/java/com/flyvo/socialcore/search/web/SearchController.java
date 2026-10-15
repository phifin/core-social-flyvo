package com.flyvo.socialcore.search.web;

import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.search.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SearchController {
  private final SearchQueries queries;
  private final SearchMaintenance maintenance;

  public SearchController(SearchQueries queries, SearchMaintenance maintenance) {
    this.queries = queries;
    this.maintenance = maintenance;
  }

  private ActorContext actor(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof ActorContext a))
      throw Failure.unauthorized();
    return a;
  }

  @GetMapping("/search/users")
  public SearchQueries.Page<SearchQueries.User> users(
      Authentication auth,
      @RequestParam String q,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return queries.users(actor(auth), q, size, after);
  }

  @GetMapping("/search/communities")
  public SearchQueries.Page<SearchQueries.Community> communities(
      Authentication auth,
      @RequestParam String q,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return queries.communities(actor(auth), q, size, after);
  }

  @GetMapping("/search/posts")
  public SearchQueries.Page<SearchQueries.Post> posts(
      Authentication auth,
      @RequestParam String q,
      @RequestParam(required = false) UUID communityId,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return queries.posts(actor(auth), q, communityId, size, after);
  }

  record Start(
      @NotNull SearchQueries.Kind kind, UUID resumeId, @PositiveOrZero Long expectedVersion) {}

  @PostMapping("/admin/search/rebuilds")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public SearchMaintenance.Rebuild start(Authentication auth, @Valid @RequestBody Start body) {
    return maintenance.start(actor(auth), body.kind(), body.resumeId(), body.expectedVersion());
  }

  @GetMapping("/admin/search/rebuilds/{id}")
  public SearchMaintenance.Rebuild status(Authentication auth, @PathVariable UUID id) {
    return maintenance.read(actor(auth), id);
  }
}
