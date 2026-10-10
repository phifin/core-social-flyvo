package com.flyvo.socialcore.content.web;

import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.platform.api.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/posts")
class PostController {
  private final ContentCommands commands;
  private final ContentQueries queries;

  PostController(ContentCommands commands, ContentQueries queries) {
    this.commands = commands;
    this.queries = queries;
  }

  @PostMapping
  ResponseEntity<ContentQueries.PostView> publish(
      @AuthenticationPrincipal ActorContext actor,
      @RequestBody ContentCommands.Publish request,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    var p = commands.publish(actor, request, key);
    return ResponseEntity.created(URI.create("/api/v1/posts/" + p.id()))
        .eTag("\"" + p.version() + "\"")
        .body(p);
  }

  @GetMapping("/{id}")
  ResponseEntity<ContentQueries.PostView> read(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    var p = queries.read(actor == null ? ActorContext.anonymous() : actor, id);
    return ResponseEntity.ok()
        .eTag("\"" + p.version() + "\"")
        .header("Cache-Control", "private, no-store")
        .body(p);
  }

  @GetMapping
  ContentQueries.Page<ContentQueries.PostView> posts(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(required = false) UUID authorId,
      @RequestParam(required = false) UUID communityId,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return queries.posts(
        actor == null ? ActorContext.anonymous() : actor, authorId, communityId, size, after);
  }

  @PatchMapping("/{id}")
  ResponseEntity<ContentQueries.PostView> edit(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody ContentCommands.Edit request) {
    var p = commands.edit(actor, id, ResourceVersions.require(match), request);
    return ResponseEntity.ok().eTag("\"" + p.version() + "\"").body(p);
  }

  @PutMapping("/{id}/audience")
  ResponseEntity<ContentQueries.PostView> audience(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody ContentCommands.Audience request) {
    var p = commands.audience(actor, id, ResourceVersions.require(match), request);
    return ResponseEntity.ok().eTag("\"" + p.version() + "\"").body(p);
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match) {
    commands.delete(actor, id, ResourceVersions.require(match));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/revisions")
  ContentQueries.Page<ContentQueries.Revision> revisions(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "2147483647") int before) {
    return queries.revisions(actor, id, size, before);
  }

  @PostMapping("/{id}/media/{media}/access")
  ResponseEntity<?> access(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @PathVariable UUID media,
      @RequestParam(required = false) Integer revision) {
    return ResponseEntity.ok()
        .header("Cache-Control", "private, no-store")
        .body(
            queries.mediaAccess(
                actor == null ? ActorContext.anonymous() : actor, id, media, revision));
  }
}
