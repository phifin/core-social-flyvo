package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.community.domain.CommunityState.*;
import com.flyvo.socialcore.platform.api.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityLifecycle implements CommunityCommands, MembershipQueries {
  private final CommunityAccess access;
  private final Idempotency idempotency;

  public CommunityLifecycle(CommunityAccess access, Idempotency idempotency) {
    this.access = access;
    this.idempotency = idempotency;
  }

  @Transactional
  public Info create(
      ActorContext actor,
      String slug,
      String name,
      String description,
      String visibility,
      String mode,
      String key) {
    validate(
        name,
        description,
        visibility == null ? "PRIVATE" : visibility,
        mode == null ? "INVITE_ONLY" : mode);
    if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{2,79}")) throw Failure.invalid();
    access.guardAccounts(actor);
    var route = "POST /communities";
    var previous =
        idempotency.claim(
            actor.userId(),
            route,
            key,
            access.fingerprint(
                java.util.Arrays.asList(
                    slug,
                    name,
                    description,
                    visibility == null ? "PRIVATE" : visibility,
                    mode == null ? "INVITE_ONLY" : mode)));
    if (previous != null) return read(actor, previous);
    var c =
        new Community(
            UUID.randomUUID(),
            slug,
            name,
            description,
            visibility == null ? "PRIVATE" : visibility,
            mode == null ? "INVITE_ONLY" : mode,
            "ACTIVE",
            actor.userId(),
            access.clock().instant(),
            0);
    access.store().create(c, access.clock().instant());
    access.changed(actor, c, "CommunityCreated", actor.userId());
    idempotency.complete(actor.userId(), route, key, c.id());
    return info(c);
  }

  @Transactional(readOnly = true)
  public Info read(ActorContext actor, UUID id) {
    boolean signed = actor != null && actor.authenticated();
    if (signed) access.actor(actor);
    var c = access.store().community(id, false, false);
    if (c == null) throw new Failure(404, "NOT_FOUND");
    if (!signed && !"PUBLIC".equals(c.visibility())) throw new Failure(404, "NOT_FOUND");
    var member = signed ? access.store().member(id, actor.userId()) : null;
    if (CommunityPermissionPolicy.readable(c, member, signed, access.clock().instant()))
      return info(c);
    // Signed-in outsiders see only the documented discovery card.
    return new Info(
        c.id(),
        c.slug(),
        c.name(),
        c.description(),
        c.visibility(),
        null,
        c.status(),
        null,
        c.version());
  }

  @Transactional(readOnly = true)
  public List<Info> discover(ActorContext actor, int size, int offset) {
    CommunityAccess.page(size, offset);
    boolean signed = actor != null && actor.authenticated();
    if (signed) access.actor(actor);
    return access.store().discover(signed, size, offset).stream()
        .map(
            c ->
                new Info(
                    c.id(),
                    c.slug(),
                    c.name(),
                    c.description(),
                    c.visibility(),
                    null,
                    c.status(),
                    null,
                    c.version()))
        .toList();
  }

  @Transactional
  public Info settings(
      ActorContext actor,
      UUID id,
      long version,
      String name,
      String description,
      String visibility,
      String mode) {
    access.guardAccounts(actor);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    CommunityPermissionPolicy.require(
        c, access.store().member(id, actor.userId()), access.clock().instant(), 3);
    access.version(c.version(), version);
    String n = name == null ? c.name() : name,
        d = description == null ? c.description() : description,
        v = visibility == null ? c.visibility() : visibility,
        m = mode == null ? c.joinMode() : mode;
    validate(n, d, v, m);
    access.store().settings(id, n, d, v, m, access.clock().instant());
    access.changed(actor, c, "CommunityUpdated", actor.userId());
    return info(access.store().community(id, false, false));
  }

  @Transactional
  public Info archive(ActorContext actor, UUID id, long version) {
    access.guardAccounts(actor);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.require(
        c, access.store().member(id, actor.userId()), access.clock().instant(), 4);
    access.version(c.version(), version);
    if (c.active()) {
      access.store().archive(id, access.clock().instant());
      access.changed(actor, c, "CommunityUpdated", actor.userId());
    }
    return info(access.store().community(id, false, false));
  }

  @Transactional
  public Info transfer(ActorContext actor, UUID id, long version, UUID successor) {
    if (successor == null || successor.equals(actor.userId())) throw Failure.invalid();
    access.guardAccounts(actor, successor);
    access.activeAccount(successor);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    CommunityPermissionPolicy.require(
        c, access.store().member(id, actor.userId()), access.clock().instant(), 4);
    access.version(c.version(), version);
    var member = access.store().member(id, successor);
    if (member == null || !member.effective(access.clock().instant())) throw Failure.conflict();
    access
        .store()
        .membership(id, actor.userId(), "ADMIN", "ACTIVE", null, access.clock().instant());
    access.store().transfer(id, successor, access.clock().instant());
    access.changed(actor, c, "MembershipChanged", successor);
    access
        .work()
        .audit(actor.userId(), "COMMUNITY_OWNER_TRANSFERRED", "community", id, "SUCCESS", null);
    return info(access.store().community(id, false, false));
  }

  @Transactional(readOnly = true)
  public List<Facts> facts(UUID user, List<UUID> ids) {
    if (user == null
        || ids == null
        || ids.size() > 100
        || ids.stream().anyMatch(java.util.Objects::isNull)) throw Failure.invalid();
    var communities =
        access.store().communities(ids).stream()
            .collect(java.util.stream.Collectors.toMap(Community::id, c -> c));
    return access.store().facts(user, ids).stream()
        .map(m -> facts(m, communities.get(m.communityId())))
        .toList();
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void guardWrite(ActorContext actor, UUID id) {
    // Caller must already hold actor account guard before acquiring later guards.
    access.actor(actor);
    var c = access.guard(id, false);
    CommunityPermissionPolicy.active(c);
    CommunityPermissionPolicy.require(
        c, access.store().member(id, actor.userId()), access.clock().instant(), 1);
  }

  @Transactional(readOnly = true)
  public boolean ownsActiveCommunity(UUID user) {
    return access.store().ownsActive(user);
  }

  public static Info info(Community c) {
    return new Info(
        c.id(),
        c.slug(),
        c.name(),
        c.description(),
        c.visibility(),
        c.joinMode(),
        c.status(),
        c.ownerId(),
        c.version());
  }

  public static Facts facts(Member m, Community c) {
    return new Facts(
        m.communityId(),
        m.userId(),
        m.effectiveRole(c),
        m.status(),
        m.suspendedUntil(),
        m.version());
  }

  private void validate(String n, String d, String v, String m) {
    if (n == null
        || n.isBlank()
        || n.length() > 120
        || d == null
        || d.length() > 4000
        || !List.of("PUBLIC", "PRIVATE").contains(v)
        || !List.of("OPEN", "REQUEST", "INVITE_ONLY").contains(m)) throw Failure.invalid();
  }
}
