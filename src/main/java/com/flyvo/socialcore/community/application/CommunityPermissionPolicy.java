package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.community.domain.CommunityState.*;
import com.flyvo.socialcore.platform.api.Failure;
import java.time.Instant;

public final class CommunityPermissionPolicy {
  private CommunityPermissionPolicy() {}

  public static int rank(String role) {
    return switch (role) {
      case "OWNER" -> 4;
      case "ADMIN" -> 3;
      case "MODERATOR" -> 2;
      case "MEMBER" -> 1;
      default -> 0;
    };
  }

  public static void require(Community c, Member member, Instant now, int minimum) {
    if (member == null || !member.effective(now) || rank(member.effectiveRole(c)) < minimum)
      throw new Failure(403, "ACCESS_DENIED");
  }

  public static void active(Community c) {
    if (!c.active()) throw new Failure(409, "COMMUNITY_ARCHIVED");
  }

  public static void sanction(Community c, Member actor, Member target, Instant now) {
    require(c, actor, now, 2);
    if (target == null) throw new Failure(404, "NOT_FOUND");
    if (actor.userId().equals(target.userId())
        || rank(actor.effectiveRole(c)) <= rank(target.effectiveRole(c)))
      throw new Failure(403, "ACCESS_DENIED");
  }

  public static boolean readable(Community c, Member viewer, boolean signedIn, Instant now) {
    if (viewer != null && viewer.excluded(now)) return false;
    return "PUBLIC".equals(c.visibility()) || (signedIn && viewer != null && viewer.effective(now));
  }
}
