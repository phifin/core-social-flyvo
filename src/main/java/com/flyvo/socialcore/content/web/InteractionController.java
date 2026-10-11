package com.flyvo.socialcore.content.web;

import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.platform.api.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class InteractionController {
  private final Interactions interactions;

  InteractionController(Interactions interactions) {
    this.interactions = interactions;
  }

  private ActorContext actor(ActorContext a) {
    return a == null ? ActorContext.anonymous() : a;
  }

  private ResponseEntity<Interactions.CommentView> created(Interactions.CommentView c) {
    return ResponseEntity.created(URI.create("/api/v1/comments/" + c.id()))
        .eTag("\"" + c.version() + "\"")
        .body(c);
  }

  @PostMapping("/api/v1/posts/{id}/comments")
  ResponseEntity<Interactions.CommentView> create(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestBody Interactions.Text request,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    return created(interactions.create(actor, id, null, request.body(), key));
  }

  @PostMapping("/api/v1/comments/{id}/replies")
  ResponseEntity<Interactions.CommentView> reply(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestBody Interactions.Text request,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    var target = interactions.readComment(actor, id);
    return created(interactions.create(actor, target.postId(), id, request.body(), key));
  }

  @GetMapping("/api/v1/comments/{id}")
  ResponseEntity<Interactions.CommentView> read(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    var c = interactions.readComment(actor(actor), id);
    var response = ResponseEntity.ok().header("Cache-Control", "private, no-store");
    if (c.version() != null) response.eTag("\"" + c.version() + "\"");
    return response.body(c);
  }

  @GetMapping("/api/v1/posts/{id}/comments")
  ContentQueries.Page<Interactions.CommentView> roots(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return interactions.comments(actor(actor), id, null, size, after);
  }

  @GetMapping("/api/v1/comments/{id}/replies")
  ContentQueries.Page<Interactions.CommentView> replies(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    var root = interactions.readComment(actor(actor), id);
    return interactions.comments(actor(actor), root.postId(), id, size, after);
  }

  @PatchMapping("/api/v1/comments/{id}")
  ResponseEntity<Interactions.CommentView> edit(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Interactions.Text request) {
    var c = interactions.edit(actor, id, ResourceVersions.require(match), request.body());
    return ResponseEntity.ok().eTag("\"" + c.version() + "\"").body(c);
  }

  @DeleteMapping("/api/v1/comments/{id}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match) {
    interactions.delete(actor, id, ResourceVersions.require(match));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/api/v1/posts/{id}/reactions/me")
  Interactions.ReactionView reactPost(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestBody Interactions.ReactionInput input) {
    return interactions.react(actor, id, null, input.type());
  }

  @PutMapping("/api/v1/comments/{id}/reactions/me")
  Interactions.ReactionView reactComment(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestBody Interactions.ReactionInput input) {
    return interactions.react(actor, null, id, input.type());
  }

  @DeleteMapping("/api/v1/posts/{id}/reactions/me")
  ResponseEntity<Void> unreactPost(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    interactions.unreact(actor, id, null);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/v1/comments/{id}/reactions/me")
  ResponseEntity<Void> unreactComment(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    interactions.unreact(actor, null, id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/v1/posts/{id}/reactions")
  Interactions.ReactionPage postReactions(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return interactions.reactions(actor(actor), id, null, size, after);
  }

  @GetMapping("/api/v1/comments/{id}/reactions")
  Interactions.ReactionPage commentReactions(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return interactions.reactions(actor(actor), null, id, size, after);
  }
}
