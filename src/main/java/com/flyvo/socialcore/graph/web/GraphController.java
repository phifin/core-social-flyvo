package com.flyvo.socialcore.graph.web;

import com.flyvo.socialcore.graph.api.*;
import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class GraphController {
  private final GraphCommands commands;
  private final RelationshipQueries queries;

  GraphController(GraphCommands commands, RelationshipQueries queries) {
    this.commands = commands;
    this.queries = queries;
  }

  public record Target(UUID userId) {}

  @PutMapping("/me/following/{user}")
  ResponseEntity<Void> follow(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.follow(actor, user, true);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/me/following/{user}")
  ResponseEntity<Void> unfollow(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.follow(actor, user, false);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/me/blocks/{user}")
  ResponseEntity<Void> block(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.block(actor, user, true);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/me/blocks/{user}")
  ResponseEntity<Void> unblock(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.block(actor, user, false);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/me/mutes/{user}")
  ResponseEntity<Void> mute(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.mute(actor, user, true);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/me/mutes/{user}")
  ResponseEntity<Void> unmute(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.mute(actor, user, false);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/me/friends/{user}")
  ResponseEntity<Void> unfriend(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID user) {
    commands.unfriend(actor, user);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me/graph/{kind}")
  RelationshipQueries.Page<UUID> edges(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable String kind,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return queries.ownEdges(actor, kind, size, after);
  }

  @PostMapping("/friend-requests")
  ResponseEntity<GraphCommands.Request> request(
      @AuthenticationPrincipal ActorContext actor, @RequestBody Target target) {
    var r = commands.request(actor, target.userId());
    return ResponseEntity.ok().body(r);
  }

  @PostMapping("/friend-requests/{id}/accept")
  GraphCommands.Request accept(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    return commands.respond(actor, id, "accept");
  }

  @PostMapping("/friend-requests/{id}/decline")
  GraphCommands.Request decline(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    return commands.respond(actor, id, "decline");
  }

  @PostMapping("/friend-requests/{id}/cancel")
  GraphCommands.Request cancel(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    return commands.respond(actor, id, "cancel");
  }

  @GetMapping("/me/friend-requests")
  RelationshipQueries.Page<GraphCommands.Request> requests(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(defaultValue = "incoming") String direction,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return commands.requests(actor, direction, size, after);
  }
}
