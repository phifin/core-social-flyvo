package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.community.api.AdmissionCommands;
import com.flyvo.socialcore.community.domain.CommunityState.*;
import com.flyvo.socialcore.platform.api.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityAdmissions implements AdmissionCommands {
  private final CommunityAccess access;
  private final MembershipLifecycle memberships;
  private final Idempotency idempotency;

  public CommunityAdmissions(
      CommunityAccess access, MembershipLifecycle memberships, Idempotency idempotency) {
    this.access = access;
    this.memberships = memberships;
    this.idempotency = idempotency;
  }

  @Transactional
  public Invite invite(ActorContext actor, UUID id, UUID invitee, String key) {
    if (invitee == null || invitee.equals(actor.userId())) throw Failure.invalid();
    access.guardAccounts(actor, invitee);
    access.activeAccount(invitee);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    CommunityPermissionPolicy.require(
        c, access.store().member(id, actor.userId()), access.clock().instant(), 3);
    var route = "POST /communities/" + id + "/invitations";
    var replay = idempotency.claim(actor.userId(), route, key, invitee.toString());
    if (replay != null) return inviteInfo(access.store().invitation(replay));
    eligible(c, invitee);
    var existing = access.store().pendingInvitation(id, invitee);
    if (existing != null && existing.pending(access.clock().instant())) {
      idempotency.complete(actor.userId(), route, key, existing.id());
      return inviteInfo(existing);
    }
    if (existing != null)
      access.store().resolveInvite(existing.id(), "EXPIRED", access.clock().instant());
    var request = access.store().pendingRequest(id, invitee);
    if (request != null)
      access
          .store()
          .resolveRequest(request.id(), "CANCELLED", actor.userId(), access.clock().instant());
    var i =
        new Invitation(
            UUID.randomUUID(),
            id,
            invitee,
            actor.userId(),
            "PENDING",
            access.clock().instant().plusSeconds(7 * 86400L),
            access.clock().instant(),
            0);
    access.store().invite(i);
    access
        .work()
        .job("COMMUNITY_INVITE_EXPIRY", i.id().toString(), i.id() + ":" + id, i.expiresAt());
    access.bump(c);
    access.admissionChanged(actor, c, "CommunityInvitationCreated", invitee, i.id(), "PENDING");
    idempotency.complete(actor.userId(), route, key, i.id());
    return inviteInfo(i);
  }

  @Transactional
  public Invite respondInvite(ActorContext actor, UUID id, String action) {
    if (!List.of("accept", "decline", "revoke").contains(action)) throw Failure.invalid();
    var observed = access.store().invitation(id);
    if (observed == null) throw new Failure(404, "NOT_FOUND");
    access.guardAccounts(actor);
    var c = access.guard(observed.communityId(), true);
    var i = access.store().invitation(id);
    if ("revoke".equals(action))
      CommunityPermissionPolicy.require(
          c, access.store().member(c.id(), actor.userId()), access.clock().instant(), 3);
    else if (!actor.userId().equals(i.inviteeId())) throw new Failure(404, "NOT_FOUND");
    CommunityPermissionPolicy.active(c);
    String result =
        switch (action) {
          case "accept" -> "ACCEPTED";
          case "decline" -> "DECLINED";
          default -> "REVOKED";
        };
    if (result.equals(i.status())) return inviteInfo(i);
    if (!i.pending(access.clock().instant())) throw new Failure(409, "INVITATION_UNAVAILABLE");
    if ("accept".equals(action)) {
      var m = access.store().member(c.id(), actor.userId());
      if (m != null && m.excluded(access.clock().instant()))
        throw new Failure(403, "ACCESS_DENIED");
      access.store().resolveInvite(id, result, access.clock().instant());
      memberships.activate(actor, c, actor.userId());
      // Effective existing membership does not emit a duplicate join, but resolution still has its
      // own versioned fact.
      access.bump(access.store().community(c.id(), false, false));
      access.admissionChanged(actor, c, "CommunityInvitationResolved", actor.userId(), id, result);
    } else {
      access.store().resolveInvite(id, result, access.clock().instant());
      access.bump(c);
      access.admissionChanged(actor, c, "CommunityInvitationResolved", i.inviteeId(), id, result);
    }
    return inviteInfo(access.store().invitation(id));
  }

  @Transactional
  public JoinRequest request(ActorContext actor, UUID id, String message, String key) {
    if (message != null && message.length() > 1000) throw Failure.invalid();
    access.guardAccounts(actor);
    var c = access.guard(id, true);
    CommunityPermissionPolicy.active(c);
    var route = "POST /communities/" + id + "/join-requests";
    var replay = idempotency.claim(actor.userId(), route, key, access.fingerprint(message));
    if (replay != null) return requestInfo(access.store().request(replay));
    eligible(c, actor.userId());
    if (!"REQUEST".equals(c.joinMode())) throw Failure.conflict();
    var existing = access.store().pendingRequest(id, actor.userId());
    if (existing != null) {
      idempotency.complete(actor.userId(), route, key, existing.id());
      return requestInfo(existing);
    }
    var invitation = access.store().pendingInvitation(id, actor.userId());
    if (invitation != null)
      access
          .store()
          .resolveInvite(
              invitation.id(),
              invitation.pending(access.clock().instant()) ? "REVOKED" : "EXPIRED",
              access.clock().instant());
    var r =
        new Request(
            UUID.randomUUID(), id, actor.userId(), "PENDING", message, access.clock().instant(), 0);
    access.store().request(r);
    access.bump(c);
    access.admissionChanged(actor, c, "CommunityJoinRequested", actor.userId(), r.id(), "PENDING");
    idempotency.complete(actor.userId(), route, key, r.id());
    return requestInfo(r);
  }

  @Transactional
  public JoinRequest respondRequest(ActorContext actor, UUID id, String action) {
    if (!List.of("approve", "reject", "cancel").contains(action)) throw Failure.invalid();
    var observed = access.store().request(id);
    if (observed == null) throw new Failure(404, "NOT_FOUND");
    access.guardAccounts(actor, observed.userId());
    var c = access.guard(observed.communityId(), true);
    var r = access.store().request(id);
    if ("cancel".equals(action)) {
      if (!actor.userId().equals(r.userId())) throw new Failure(404, "NOT_FOUND");
    } else
      CommunityPermissionPolicy.require(
          c, access.store().member(c.id(), actor.userId()), access.clock().instant(), 3);
    CommunityPermissionPolicy.active(c);
    String result =
        switch (action) {
          case "approve" -> "APPROVED";
          case "reject" -> "REJECTED";
          default -> "CANCELLED";
        };
    if (result.equals(r.status())) return requestInfo(r);
    if (!"PENDING".equals(r.status())) throw Failure.conflict();
    access.store().resolveRequest(id, result, actor.userId(), access.clock().instant());
    if ("approve".equals(action)) {
      access.activeAccount(r.userId());
      memberships.activate(actor, c, r.userId());
    }
    access.bump(access.store().community(c.id(), false, false));
    access.admissionChanged(actor, c, "CommunityJoinRequestResolved", r.userId(), id, result);
    return requestInfo(access.store().request(id));
  }

  @Transactional(readOnly = true)
  public List<Invite> invitations(ActorContext actor, int size, int offset) {
    CommunityAccess.page(size, offset);
    access.actor(actor);
    return access
        .store()
        .invitations(actor.userId(), access.clock().instant(), size, offset)
        .stream()
        .map(CommunityAdmissions::inviteInfo)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<JoinRequest> requests(ActorContext actor, UUID id, int size, int offset) {
    CommunityAccess.page(size, offset);
    access.actor(actor);
    var c = access.store().community(id, false, false);
    if (c == null) throw new Failure(404, "NOT_FOUND");
    CommunityPermissionPolicy.require(
        c, access.store().member(id, actor.userId()), access.clock().instant(), 3);
    return access.store().requests(id, size, offset).stream()
        .map(CommunityAdmissions::requestInfo)
        .toList();
  }

  private void eligible(Community c, UUID user) {
    var m = access.store().member(c.id(), user);
    if (m != null && m.excluded(access.clock().instant())) throw new Failure(403, "ACCESS_DENIED");
    if (m != null && m.effective(access.clock().instant())) throw Failure.conflict();
  }

  private static Invite inviteInfo(Invitation i) {
    return new Invite(
        i.id(),
        i.communityId(),
        i.inviteeId(),
        i.inviterId(),
        i.status(),
        i.expiresAt(),
        i.version());
  }

  private static JoinRequest requestInfo(Request r) {
    return new JoinRequest(
        r.id(), r.communityId(), r.userId(), r.status(), r.message(), r.version());
  }
}
