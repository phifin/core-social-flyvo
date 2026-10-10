package com.flyvo.socialcore.community.web;

import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.community.application.*;
import com.flyvo.socialcore.platform.api.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class CommunityController {
  private final CommunityLifecycle communities;
  private final MembershipLifecycle members;
  private final CommunityAdmissions admissions;

  CommunityController(
      CommunityLifecycle communities, MembershipLifecycle members, CommunityAdmissions admissions) {
    this.communities = communities;
    this.members = members;
    this.admissions = admissions;
  }

  public record Create(
      String slug, String name, String description, String visibility, String joinMode) {}

  public record Settings(String name, String description, String visibility, String joinMode) {}

  public record User(UUID userId) {}

  public record Role(String role) {}

  public record Message(String message) {}

  public record Items<T>(List<T> items, String nextCursor) {}

  @PostMapping("/communities")
  ResponseEntity<CommunityCommands.Info> create(
      @AuthenticationPrincipal ActorContext actor,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody Create r) {
    var c =
        communities.create(
            actor,
            r.slug(),
            r.name(),
            r.description() == null ? "" : r.description(),
            r.visibility(),
            r.joinMode(),
            key);
    return ResponseEntity.created(URI.create("/api/v1/communities/" + c.id()))
        .eTag(tag(c.version()))
        .body(c);
  }

  @GetMapping("/communities")
  Items<CommunityCommands.Info> discover(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return new Items<>(communities.discover(actor, size, offset), null);
  }

  @GetMapping("/communities/{id}")
  ResponseEntity<CommunityCommands.Info> read(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    var c = communities.read(actor, id);
    return ResponseEntity.ok().eTag(tag(c.version())).body(c);
  }

  @PatchMapping("/communities/{id}")
  ResponseEntity<CommunityCommands.Info> edit(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Settings r) {
    var c =
        communities.settings(
            actor,
            id,
            ResourceVersions.require(match),
            r.name(),
            r.description(),
            r.visibility(),
            r.joinMode());
    return ResponseEntity.ok().eTag(tag(c.version())).body(c);
  }

  @PostMapping("/communities/{id}/archive")
  ResponseEntity<CommunityCommands.Info> archive(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match) {
    var c = communities.archive(actor, id, ResourceVersions.require(match));
    return ResponseEntity.ok().eTag(tag(c.version())).body(c);
  }

  @PostMapping("/communities/{id}/ownership-transfers")
  ResponseEntity<CommunityCommands.Info> transfer(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody User r) {
    var c = communities.transfer(actor, id, ResourceVersions.require(match), r.userId());
    return ResponseEntity.ok().eTag(tag(c.version())).body(c);
  }

  @GetMapping("/communities/{id}/members")
  Items<MembershipQueries.Facts> members(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return new Items<>(members.members(actor, id, size, offset), null);
  }

  @PutMapping("/communities/{id}/members/me")
  ResponseEntity<MembershipQueries.Facts> join(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    var m = members.join(actor, id);
    return ResponseEntity.ok().eTag(tag(m.version())).body(m);
  }

  @DeleteMapping("/communities/{id}/members/me")
  ResponseEntity<Void> leave(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "If-Match", required = false) String match) {
    members.leave(actor, id, ResourceVersions.require(match));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/communities/{id}/members/{user}")
  ResponseEntity<Void> remove(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @PathVariable UUID user,
      @RequestHeader(name = "If-Match", required = false) String match) {
    members.remove(actor, id, user, ResourceVersions.require(match));
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/communities/{id}/members/{user}/role")
  ResponseEntity<MembershipQueries.Facts> role(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @PathVariable UUID user,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Role r) {
    var m = members.role(actor, id, user, ResourceVersions.require(match), r.role());
    return ResponseEntity.ok().eTag(tag(m.version())).body(m);
  }

  @PostMapping("/communities/{id}/invitations")
  ResponseEntity<AdmissionCommands.Invite> invite(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody User r) {
    var i = admissions.invite(actor, id, r.userId(), key);
    return ResponseEntity.created(URI.create("/api/v1/community-invitations/" + i.id()))
        .eTag(tag(i.version()))
        .body(i);
  }

  @GetMapping("/me/community-invitations")
  Items<AdmissionCommands.Invite> invitations(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return new Items<>(admissions.invitations(actor, size, offset), null);
  }

  @PostMapping({
    "/community-invitations/{id}/accept",
    "/community-invitations/{id}/decline",
    "/community-invitations/{id}/revoke"
  })
  ResponseEntity<AdmissionCommands.Invite> respondInvite(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      jakarta.servlet.http.HttpServletRequest request) {
    String path = request.getRequestURI();
    var i = admissions.respondInvite(actor, id, path.substring(path.lastIndexOf('/') + 1));
    return ResponseEntity.ok().eTag(tag(i.version())).body(i);
  }

  @PostMapping("/communities/{id}/join-requests")
  ResponseEntity<AdmissionCommands.JoinRequest> request(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody Message r) {
    var q = admissions.request(actor, id, r.message(), key);
    return ResponseEntity.created(URI.create("/api/v1/join-requests/" + q.id()))
        .eTag(tag(q.version()))
        .body(q);
  }

  @GetMapping("/communities/{id}/join-requests")
  Items<AdmissionCommands.JoinRequest> requests(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return new Items<>(admissions.requests(actor, id, size, offset), null);
  }

  @PostMapping({
    "/join-requests/{id}/approve",
    "/join-requests/{id}/reject",
    "/join-requests/{id}/cancel"
  })
  ResponseEntity<AdmissionCommands.JoinRequest> respondRequest(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID id,
      jakarta.servlet.http.HttpServletRequest request) {
    String path = request.getRequestURI();
    var q = admissions.respondRequest(actor, id, path.substring(path.lastIndexOf('/') + 1));
    return ResponseEntity.ok().eTag(tag(q.version())).body(q);
  }

  private String tag(long v) {
    return "\"" + v + "\"";
  }
}
