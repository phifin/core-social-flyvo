package com.flyvo.socialcore.moderation.domain;

import java.time.Instant;
import java.util.UUID;

public record Report(
    UUID id,
    UUID reporter,
    String targetType,
    UUID targetId,
    UUID community,
    UUID author,
    UUID parentAuthor,
    String category,
    byte[] evidence,
    String keyId,
    String status,
    Instant created,
    Instant resolved,
    long version) {
  public Report {
    if (id == null
        || reporter == null
        || targetId == null
        || created == null
        || status == null
        || targetType == null
        || (evidence == null) != (keyId == null)
        || evidence == null && !java.util.Set.of("RESOLVED", "DISMISSED").contains(status)
        || keyId != null && keyId.isBlank()
        || version < 0
        || !java.util.Set.of("POST", "COMMENT", "COMMUNITY", "USER").contains(targetType)
        || !java.util.Set.of("OPEN", "IN_REVIEW", "RESOLVED", "DISMISSED").contains(status)
        || (java.util.Set.of("RESOLVED", "DISMISSED").contains(status)) != (resolved != null)
        || targetType.equals("USER") && community != null
        || evidence != null && (evidence.length == 0 || evidence.length > 65536))
      throw new IllegalArgumentException();
    evidence = evidence == null ? null : evidence.clone();
  }

  @Override
  public byte[] evidence() {
    return evidence == null ? null : evidence.clone();
  }

  public Report transition(boolean review, boolean dismiss, Instant now) {
    if (!java.util.Set.of("OPEN", "IN_REVIEW").contains(status) || review && !status.equals("OPEN"))
      throw new IllegalStateException();
    return new Report(
        id,
        reporter,
        targetType,
        targetId,
        community,
        author,
        parentAuthor,
        category,
        evidence,
        keyId,
        review ? "IN_REVIEW" : dismiss ? "DISMISSED" : "RESOLVED",
        created,
        review ? null : now,
        version + 1);
  }
}
