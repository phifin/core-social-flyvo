package com.flyvo.socialcore.community.domain;

import java.time.Instant;
import java.util.UUID;

public final class CommunityState {
  private CommunityState() {}

  public record Community(
      UUID id,
      String slug,
      String name,
      String description,
      String visibility,
      String joinMode,
      String status,
      UUID ownerId,
      Instant createdAt,
      long version) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }

    public boolean mayLeave(UUID user) {
      return !active() || !ownerId.equals(user);
    }
  }

  public record Member(
      UUID communityId,
      UUID userId,
      String role,
      String status,
      Instant joinedAt,
      Instant suspendedUntil,
      long version) {
    public boolean effective(Instant now) {
      return "ACTIVE".equals(status) && (suspendedUntil == null || !now.isBefore(suspendedUntil));
    }

    public boolean excluded(Instant now) {
      return "BANNED".equals(status) || ("ACTIVE".equals(status) && !effective(now));
    }

    public String effectiveRole(Community community) {
      return community.ownerId().equals(userId) ? "OWNER" : role;
    }

    public Member transition(String nextRole, String nextStatus, Instant suspension, Instant now) {
      if (!java.util.List.of("MEMBER", "MODERATOR", "ADMIN").contains(nextRole)
          || !java.util.List.of("ACTIVE", "LEFT", "REMOVED", "BANNED").contains(nextStatus)
          || (!"ACTIVE".equals(nextStatus) && suspension != null)
          || ("BANNED".equals(status) && !"REMOVED".equals(nextStatus))
          || (suspension != null && !suspension.equals(suspendedUntil) && !suspension.isAfter(now)))
        throw new IllegalArgumentException("Invalid membership transition");
      return new Member(
          communityId, userId, nextRole, nextStatus, joinedAt, suspension, version + 1);
    }
  }

  public record Invitation(
      UUID id,
      UUID communityId,
      UUID inviteeId,
      UUID inviterId,
      String status,
      Instant expiresAt,
      Instant createdAt,
      long version) {
    public boolean pending(Instant now) {
      return "PENDING".equals(status) && now.isBefore(expiresAt);
    }
  }

  public record Request(
      UUID id,
      UUID communityId,
      UUID userId,
      String status,
      String message,
      Instant createdAt,
      long version) {}
}
