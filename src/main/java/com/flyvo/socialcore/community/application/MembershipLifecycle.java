package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.community.domain.CommunityState.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipLifecycle implements MembershipCommands {
  private final CommunityAccess access;

  public MembershipLifecycle(CommunityAccess access) {
    this.access = access;
  }

  @Transactional
  public MembershipQueries.Facts join(ActorContext actor, UUID id) {
    access.guardAccounts(actor);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    var m = access.store().member(id, actor.userId());
    if (m != null && m.effective(access.clock().instant())) return CommunityLifecycle.facts(m, c);
    if (m != null && m.excluded(access.clock().instant())) throw new Failure(403, "ACCESS_DENIED");
    if (!"OPEN".equals(c.joinMode())) throw Failure.conflict();
    activate(actor, c, actor.userId());
    return CommunityLifecycle.facts(access.store().member(id, actor.userId()), c);
  }

  void activate(ActorContext actor, Community c, UUID user) {
    var existing = access.store().member(c.id(), user);
    if (existing != null && existing.excluded(access.clock().instant()))
      throw new Failure(403, "ACCESS_DENIED");
    if (existing != null && existing.effective(access.clock().instant())) return;
    access.store().join(c.id(), user, access.clock().instant());
    var invite = access.store().pendingInvitation(c.id(), user);
    if (invite != null)
      access
          .store()
          .resolveInvite(
              invite.id(),
              invite.pending(access.clock().instant()) ? "REVOKED" : "EXPIRED",
              access.clock().instant());
    var request = access.store().pendingRequest(c.id(), user);
    if (request != null)
      access
          .store()
          .resolveRequest(request.id(), "CANCELLED", actor.userId(), access.clock().instant());
    access.bump(c);
    access.changed(actor, c, "MemberJoinedCommunity", user);
  }

  @Transactional
  public void leave(ActorContext actor, UUID id, long version) {
    access.guardAccounts(actor);
    var c = access.guard(id, true);
    // The domain contract explicitly permits self-leave after archive.
    var m = access.store().member(id, actor.userId());
    if (m == null) throw new Failure(404, "NOT_FOUND");
    access.version(m.version(), version);
    if (!"ACTIVE".equals(m.status())) {
      if ("LEFT".equals(m.status())) return;
      throw Failure.conflict();
    }
    if (!c.mayLeave(actor.userId())) throw new Failure(409, "OWNER_TRANSFER_REQUIRED");
    if (!m.effective(access.clock().instant())) throw new Failure(403, "ACCESS_DENIED");
    change(actor, c, m, m.role(), "LEFT", null);
  }

  @Transactional
  public void remove(ActorContext actor, UUID id, UUID user, long version) {
    access.guardAccounts(actor, user);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    var m = access.store().member(id, user);
    CommunityPermissionPolicy.sanction(
        c, access.store().member(id, actor.userId()), m, access.clock().instant());
    access.version(m.version(), version);
    if ("REMOVED".equals(m.status())) return;
    if (!"ACTIVE".equals(m.status())) throw Failure.conflict();
    change(actor, c, m, m.role(), "REMOVED", null);
  }

  @Transactional
  public MembershipQueries.Facts role(
      ActorContext actor, UUID id, UUID user, long version, String role) {
    if (role == null || !List.of("ADMIN", "MODERATOR", "MEMBER").contains(role))
      throw Failure.invalid();
    access.guardAccounts(actor, user);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    var a = access.store().member(id, actor.userId());
    var m = access.store().member(id, user);
    CommunityPermissionPolicy.sanction(c, a, m, access.clock().instant());
    access.version(m.version(), version);
    if (!m.effective(access.clock().instant())
        || CommunityPermissionPolicy.rank(role)
            >= CommunityPermissionPolicy.rank(a.effectiveRole(c)))
      throw new Failure(403, "ACCESS_DENIED");
    if (CommunityPermissionPolicy.rank(a.effectiveRole(c)) < 3)
      throw new Failure(403, "ACCESS_DENIED");
    if (!role.equals(m.role())) change(actor, c, m, role, "ACTIVE", m.suspendedUntil());
    return CommunityLifecycle.facts(access.store().member(id, user), c);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Sanction sanction(
      ActorContext actor, UUID id, UUID user, long version, String action, Instant until) {
    if (!List.of("SUSPEND_MEMBER", "BAN_MEMBER", "UNBAN_MEMBER").contains(action))
      throw Failure.invalid();
    access.guardAccounts(actor, user);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    var m = access.store().member(id, user);
    CommunityPermissionPolicy.sanction(
        c, access.store().member(id, actor.userId()), m, access.clock().instant());
    access.version(m.version(), version);
    var before = CommunityLifecycle.facts(m, c);
    switch (action) {
      case "SUSPEND_MEMBER" -> {
        if (!"ACTIVE".equals(m.status())
            || until == null
            || !until.isAfter(access.clock().instant())
            || until.isAfter(access.clock().instant().plusSeconds(365 * 86400L)))
          throw Failure.invalid();
        change(actor, c, m, m.role(), "ACTIVE", until);
      }
      case "BAN_MEMBER" -> {
        if (until != null || "BANNED".equals(m.status())) throw Failure.conflict();
        change(actor, c, m, m.role(), "BANNED", null);
      }
      case "UNBAN_MEMBER" -> {
        if (until != null || !"BANNED".equals(m.status())) throw Failure.conflict();
        change(actor, c, m, "MEMBER", "REMOVED", null);
      }
      default -> throw Failure.invalid();
    }
    return new Sanction(before, CommunityLifecycle.facts(access.store().member(id, user), c));
  }

  private void change(
      ActorContext actor, Community c, Member m, String role, String status, Instant until) {
    var next = m.transition(role, status, until, access.clock().instant());
    access
        .store()
        .membership(
            c.id(),
            m.userId(),
            next.role(),
            next.status(),
            next.suspendedUntil(),
            access.clock().instant());
    access.bump(c);
    access.changed(actor, c, "MembershipChanged", m.userId());
  }

  @Transactional(readOnly = true)
  public List<MembershipQueries.Facts> members(ActorContext actor, UUID id, int size, int offset) {
    CommunityAccess.page(size, offset);
    access.actor(actor);
    var c = access.store().community(id, false, false);
    if (c == null) throw new Failure(404, "NOT_FOUND");
    var self = access.store().member(id, actor.userId());
    if (self == null || !self.effective(access.clock().instant()))
      throw new Failure(404, "NOT_FOUND");
    return access.store().members(id, size, offset).stream()
        .map(m -> CommunityLifecycle.facts(m, c))
        .toList();
  }
}
