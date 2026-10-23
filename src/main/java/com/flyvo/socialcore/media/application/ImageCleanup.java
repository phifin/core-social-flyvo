package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.media.domain.MediaState.Asset;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ImageCleanup implements ExternalJobHandler {
  private final MediaRepository store;
  private final ObjectProvider<ObjectStorage> objects;
  private final Clock clock;
  private final tools.jackson.databind.ObjectMapper json;

  public ImageCleanup(
      MediaRepository store,
      ObjectProvider<ObjectStorage> objects,
      Clock clock,
      tools.jackson.databind.ObjectMapper json) {
    this.store = store;
    this.objects = objects;
    this.clock = clock;
    this.json = json;
  }

  public String kind() {
    return "MEDIA_CLEANUP";
  }

  public String prepare(String payload, Lease lease) {
    var first = store.asset(UUID.fromString(payload), false);
    if (first == null) return "";
    store.quota(first.owner(), clock.instant());
    var a = store.asset(first.id(), true);
    var b = store.binding(a.id());
    if (a.erasureRequest() != null && a.erasurePurgedAt() == null) return "";
    // Tombstones remain purgeable: a PUT accepted before expiry may finish late.
    // Hourly storage reconciliation rechecks them without releasing quota twice.
    if (a.state().equals("DELETED")) return json.writeValueAsString(a);
    if (b != null
        && (b.released() == null
            || a.purgeAfter() == null
            || clock.instant().isBefore(a.purgeAfter()))) return "";
    if (a.state().equals("PROCESSING") || a.state().equals("UPLOADED")) return "";
    if (a.state().equals("FAILED") && clock.instant().isBefore(a.orphanExpires())) return "";
    if (a.state().equals("READY") && b == null && clock.instant().isBefore(a.orphanExpires()))
      return "";
    if (clock.instant().isBefore(a.uploadSettles())) throw new Deferred(a.uploadSettles());
    store.state(a.id(), "DELETING", null, clock.instant());
    return json.writeValueAsString(a);
  }

  public String execute(String prepared) {
    if (prepared.isEmpty()) return "";
    var a = json.readValue(prepared, Asset.class);
    var s = objects.getIfAvailable();
    if (s == null) throw new Failure(503, "STORAGE_DISABLED");
    if (!s.purge(a.key(), "variants/" + a.id() + "/")) throw new Failure(503, "PURGE_INCOMPLETE");
    return "confirmed";
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if (prepared.isEmpty()) return;
    var previous = json.readValue(prepared, Asset.class);
    store.quota(previous.owner(), clock.instant());
    var a = store.asset(previous.id(), true);
    if (a.erasureRequest() != null) return;
    if (!a.state().equals("DELETING") || !result.equals("confirmed")) return;
    store.charge(a.owner(), a.retainedBytes() == 0 ? -a.declaredBytes() : 0, -a.retainedBytes());
    store.state(a.id(), "DELETED", null, clock.instant());
  }

  public void failed(String payload, Lease lease) {}
}
