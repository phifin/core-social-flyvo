package com.flyvo.socialcore.graph.application;

import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reuses the platform authenticated-encryption boundary to avoid exposing filtered user IDs. */
@Component
public class GraphCursors {
  private final Secrets secrets;
  private final Clock clock;

  public GraphCursors(Secrets secrets, Clock clock) {
    this.secrets = secrets;
    this.clock = clock;
  }

  private String purpose(UUID viewer, UUID owner, String kind) {
    return "graph-page:v1:" + viewer + ":" + owner + ":" + kind;
  }

  public String encode(UUID viewer, UUID owner, String kind, UUID last) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            secrets.encrypt(
                purpose(viewer, owner, kind),
                last + "|" + clock.instant().plusSeconds(900).getEpochSecond()));
  }

  public UUID decode(UUID viewer, UUID owner, String kind, String cursor) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 256) throw Failure.invalid();
      var parts =
          secrets
              .decrypt(purpose(viewer, owner, kind), Base64.getUrlDecoder().decode(cursor))
              .split("\\|", -1);
      if (parts.length != 2 || clock.instant().getEpochSecond() >= Long.parseLong(parts[1]))
        throw Failure.invalid();
      return UUID.fromString(parts[0]);
    } catch (RuntimeException invalid) {
      throw Failure.invalid();
    }
  }
}
