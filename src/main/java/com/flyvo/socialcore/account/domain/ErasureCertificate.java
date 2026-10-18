package com.flyvo.socialcore.account.domain;

import java.util.List;
import java.util.UUID;

/** Nonrecursive immutable certificate input: no own receipt/version or final event. */
public record ErasureCertificate(
    UUID lineage,
    UUID requestId,
    UUID subject,
    long lifecycleGeneration,
    String protocol,
    String policy,
    String rosterDigest,
    long verificationRound,
    String resourceManifestDigest,
    List<Proof> prerequisites) {
  public record Proof(String owner, String step, long version, long round, String digest) {}

  public ErasureCertificate {
    if (lineage == null
        || requestId == null
        || subject == null
        || lifecycleGeneration < 1
        || verificationRound < 1
        || protocol == null
        || policy == null
        || rosterDigest == null
        || resourceManifestDigest == null
        || prerequisites == null
        || prerequisites.size() != 11
        || prerequisites.stream()
            .anyMatch(
                p ->
                    p == null
                        || p.owner() == null
                        || p.step() == null
                        || "completion-manifest".equals(p.step())
                        || p.version() < 0
                        || p.round() != verificationRound
                        || p.digest() == null)
        || prerequisites.stream().map(p -> p.owner() + "/" + p.step()).distinct().count() != 11)
      throw new IllegalArgumentException("INVALID_ERASURE_CERTIFICATE");
    prerequisites = List.copyOf(prerequisites);
    if (!prerequisites.stream()
        .map(p -> p.owner() + "/" + p.step())
        .collect(java.util.stream.Collectors.toSet())
        .equals(
            java.util.Set.of(
                "account/recovery-intent",
                "identity/credentials",
                "profile/social",
                "community/participation",
                "graph/relations",
                "content/authored-and-references",
                "moderation/private-payloads",
                "account/technical",
                "media/objects",
                "notification/derived",
                "search/derived"))) throw new IllegalArgumentException("INVALID_ERASURE_ROSTER");
  }
}
