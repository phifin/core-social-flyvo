package com.flyvo.socialcore.profile.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.media.api.MediaCommands;
import com.flyvo.socialcore.platform.api.JobHandler;
import com.flyvo.socialcore.profile.application.port.*;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ProfileErasure implements JobHandler {
  private final AccountErasure erasure;
  private final ProfileRepository profiles;
  private final ProfileErasureStore store;
  private final MediaCommands media;
  private final ObjectMapper json;
  private final Clock clock;
  private final com.flyvo.socialcore.platform.api.DurableWork work;

  public ProfileErasure(
      AccountErasure erasure,
      ProfileRepository profiles,
      ProfileErasureStore store,
      MediaCommands media,
      ObjectMapper json,
      Clock clock,
      com.flyvo.socialcore.platform.api.DurableWork work) {
    this.erasure = erasure;
    this.profiles = profiles;
    this.store = store;
    this.media = media;
    this.json = json;
    this.clock = clock;
    this.work = work;
  }

  public String kind() {
    return "PROFILE_ERASURE";
  }

  public void apply(String payload) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.PROFILE) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    var c = erasure.begin(pass.userId(), Step.PROFILE, pass.version());
    if (c == null) return;
    // Receipt verification precedes the profile/media binding's last enumeration mapping.
    if (c.checkpoint().startsWith("AVATAR_EXPORT:")) {
      var chunk = UUID.fromString(c.checkpoint().substring(14));
      if (!erasure.resourcesExported(c, chunk)) {
        erasure.advance(
            c,
            c.checkpoint(),
            0,
            "WAITING_EXTERNAL",
            "RESOURCE_EXPORT_PENDING",
            erasure.databaseTime().plusSeconds(60),
            null);
        return;
      }
    }
    var profile = profiles.find(c.userId(), true);
    if (profile != null && profile.avatarMediaId() != null) {
      if ("START".equals(c.checkpoint())) {
        var chunk = erasure.recordResources(c, "MEDIA_ASSET", List.of(profile.avatarMediaId()));
        erasure.advance(
            c,
            "AVATAR_EXPORT:" + chunk,
            0,
            "WAITING_EXTERNAL",
            "RESOURCE_EXPORT_PENDING",
            erasure.databaseTime().plusSeconds(5),
            null);
        return;
      }
      var ids = erasure.resourceIds(c, UUID.fromString(c.checkpoint().substring(14)));
      if (!ids.equals(List.of(profile.avatarMediaId())))
        throw new IllegalStateException("AVATAR_MANIFEST_MISMATCH");
      media.release(c.userId(), "AVATAR", c.userId(), ids, erasure.databaseTime());
    }
    var version = store.erase(c.userId(), c.requestedAt().plusSeconds(30 * 86400L));
    if (version != null)
      work.event(
          "ProfileUpdated",
          "profile",
          c.userId(),
          version,
          0,
          json.writeValueAsString(
              new com.flyvo.socialcore.profile.api.event.ProfileUpdated(c.userId())));
    erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "PROFILE_ABSENCE_V1");
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
