package com.flyvo.socialcore.content.domain.post;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** A fixed audience value, not a permission snapshot; current source policy still applies. */
public record PostAudience(UUID authorId, UUID communityId, Type type, List<UUID> recipients) {
  public enum Type {
    PUBLIC,
    FOLLOWERS,
    FRIENDS,
    CUSTOM,
    ONLY_ME,
    COMMUNITY
  }

  public PostAudience {
    if (authorId == null
        || type == null
        || recipients == null
        || (type == Type.COMMUNITY) != (communityId != null)
        || recipients.size() > 100
        || recipients.stream().anyMatch(java.util.Objects::isNull)
        || recipients.contains(authorId)
        || new HashSet<>(recipients).size() != recipients.size()
        || (type == Type.CUSTOM) != !recipients.isEmpty())
      throw new IllegalArgumentException("INVALID_POST_AUDIENCE");
    recipients =
        recipients.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
  }
}
