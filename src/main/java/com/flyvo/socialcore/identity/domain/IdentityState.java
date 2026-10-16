package com.flyvo.socialcore.identity.domain;

import java.time.Instant;
import java.util.UUID;

public final class IdentityState {
  private IdentityState() {}

  public record Credential(UUID userId, String hash, long version) {
    @Override
    public String toString() {
      return "Credential[redacted]";
    }
  }

  public record Session(
      UUID id,
      UUID userId,
      Instant authenticatedAt,
      Instant reauthenticatedAt,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      Instant revokedAt,
      boolean reauthenticationRequired,
      long authenticationEpoch,
      long version) {
    public boolean active(Instant now) {
      return revokedAt == null && now.isBefore(idleExpiresAt) && now.isBefore(absoluteExpiresAt);
    }

    public boolean recent(Instant now) {
      var at = reauthenticatedAt == null ? authenticatedAt : reauthenticatedAt;
      return !reauthenticationRequired && !at.isAfter(now) && at.isAfter(now.minusSeconds(300));
    }
  }

  public record Refresh(
      UUID id, UUID sessionId, int generation, Instant expiresAt, Instant consumedAt) {}

  public record Intent(
      UUID id,
      String email,
      String flow,
      byte[] proof,
      byte[] browserHash,
      Instant expiresAt,
      Instant consumedAt) {}

  public record OneTime(
      UUID id,
      UUID userId,
      String purpose,
      String targetEmail,
      Instant expiresAt,
      Instant consumedAt) {}

  public record External(UUID id, UUID userId, String provider, String issuer, String subject) {}

  public record ProviderProof(String provider, String issuer, String subject, String email) {}

  public record OAuth(
      UUID id,
      String provider,
      String intent,
      UUID userId,
      UUID sessionId,
      byte[] browserHash,
      byte[] nonceHash,
      byte[] verifier,
      Instant expiresAt,
      Instant consumedAt) {}
}
