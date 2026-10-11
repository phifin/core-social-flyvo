package com.flyvo.socialcore.media.domain;

import java.time.Instant;
import java.util.UUID;

public final class MediaState {
  private MediaState() {}

  public record Asset(
      UUID id,
      UUID owner,
      String purpose,
      String state,
      String key,
      String uploadedVersion,
      String mime,
      long declaredBytes,
      String sha256,
      long retainedBytes,
      long generation,
      Instant uploadExpires,
      Instant orphanExpires,
      Instant purgeAfter,
      long version) {
    public boolean finalizable(UUID actor, Instant now) {
      return owner.equals(actor) && state.equals("UPLOAD_PENDING") && now.isBefore(uploadExpires);
    }

    /** Covers all issued/reissued PUTs plus a conservative in-flight settling interval. */
    public Instant uploadSettles() {
      return uploadExpires.plusSeconds(900);
    }
  }

  public record Variant(
      String name,
      String key,
      String objectVersion,
      String mime,
      long bytes,
      int width,
      int height,
      long generation) {}

  public record Quota(long reserved, long retained, Instant window, int issued) {}

  public record Binding(String purpose, UUID context, Instant released) {}
}
