package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class QuarantineCleanup implements ExternalJobHandler {
  private final MediaRepository store;
  private final ObjectProvider<ObjectStorage> objects;
  private final Clock clock;

  public QuarantineCleanup(
      MediaRepository store, ObjectProvider<ObjectStorage> objects, Clock clock) {
    this.store = store;
    this.objects = objects;
    this.clock = clock;
  }

  public String kind() {
    return "MEDIA_QUARANTINE_PURGE";
  }

  public String prepare(String payload, Lease lease) {
    var a = store.asset(UUID.fromString(payload), false);
    if (a == null || a.state().equals("DELETED") || a.state().equals("DELETING")) return "";
    if (clock.instant().isBefore(a.uploadExpires()) || !a.state().equals("READY"))
      throw new Failure(503, "QUARANTINE_NOT_SETTLED");
    return a.key();
  }

  public String execute(String prepared) {
    if (prepared.isEmpty()) return "";
    var s = objects.getIfAvailable();
    if (s == null) throw new Failure(503, "STORAGE_DISABLED");
    if (!s.purgeUpload(prepared)) throw new Failure(503, "PURGE_INCOMPLETE");
    return "";
  }

  public void complete(String payload, Lease lease, String prepared, String result) {}

  public void failed(String payload, Lease lease) {}
}
