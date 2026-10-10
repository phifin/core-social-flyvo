package com.flyvo.socialcore.media.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunable accounting defaults; immutable decoder/input ceilings are separate security limits. */
@ConfigurationProperties("app.media.limits")
public record MediaLimits(
    @DefaultValue("104857600") long pendingBytes,
    @DefaultValue("1073741824") long retainedBytes,
    @DefaultValue("10") int uploadsPerMinute) {
  public MediaLimits {
    if (pendingBytes < 1
        || retainedBytes < pendingBytes
        || uploadsPerMinute < 1
        || uploadsPerMinute > 1000) throw new IllegalArgumentException("INVALID_MEDIA_LIMITS");
  }
}
