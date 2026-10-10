package com.flyvo.socialcore.content.application.policy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical ordinary read policy. Facts must be freshly supplied by authoritative source
 * APIs/views.
 */
public final class ContentAccessPolicy {
  private ContentAccessPolicy() {}

  public record PostFacts(UUID authorId, UUID communityId, String audience, String status) {}

  public record Facts(
      boolean authorActive,
      boolean viewerActive,
      boolean blocked,
      boolean following,
      boolean friends,
      boolean recipient,
      boolean muted,
      String communityVisibility,
      String communityStatus,
      String membershipStatus,
      String membershipRole,
      Instant suspendedUntil) {}

  public static boolean canRead(UUID viewer, PostFacts post, Facts facts, Instant now) {
    if (post == null
        || facts == null
        || now == null
        || post.authorId() == null
        || !"PUBLISHED".equals(post.status())
        || !facts.authorActive()
        || viewer != null && (!facts.viewerActive() || facts.blocked())) return false;
    if (post.communityId() != null) {
      if (!"COMMUNITY".equals(post.audience())
          || !Set.of("ACTIVE", "ARCHIVED").contains(nullSafe(facts.communityStatus())))
        return false;
      if (viewer != null && ("BANNED".equals(facts.membershipStatus()) || suspended(facts, now)))
        return false;
      return "PUBLIC".equals(facts.communityVisibility())
          || "PRIVATE".equals(facts.communityVisibility())
              && viewer != null
              && effectiveMember(facts, now);
    }
    if (!Set.of("PUBLIC", "FOLLOWERS", "FRIENDS", "CUSTOM", "ONLY_ME")
        .contains(nullSafe(post.audience()))) return false;
    if (post.authorId().equals(viewer)) return true;
    return switch (post.audience()) {
      case "PUBLIC" -> true;
      case "FOLLOWERS" -> viewer != null && facts.following();
      case "FRIENDS" -> viewer != null && facts.friends();
      case "CUSTOM" -> viewer != null && facts.recipient();
      default -> false;
    };
  }

  public static boolean canReadHistory(UUID viewer, PostFacts post, Facts facts, Instant now) {
    return viewer != null
        && canRead(viewer, post, facts, now)
        && (post.authorId().equals(viewer)
            || post.communityId() != null
                && effectiveMember(facts, now)
                && Set.of("OWNER", "ADMIN", "MODERATOR")
                    .contains(nullSafe(facts.membershipRole())));
  }

  private static boolean effectiveMember(Facts facts, Instant now) {
    return "ACTIVE".equals(facts.membershipStatus()) && !suspended(facts, now);
  }

  private static boolean suspended(Facts facts, Instant now) {
    return "ACTIVE".equals(facts.membershipStatus())
        && facts.suspendedUntil() != null
        && facts.suspendedUntil().isAfter(now);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
