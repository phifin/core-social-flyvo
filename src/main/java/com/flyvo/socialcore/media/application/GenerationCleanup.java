package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class GenerationCleanup implements ExternalJobHandler {
  private final MediaRepository store;
  private final ObjectProvider<ObjectStorage> objects;
  private final Clock clock;

  public GenerationCleanup(
      MediaRepository store, ObjectProvider<ObjectStorage> objects, Clock clock) {
    this.store = store;
    this.objects = objects;
    this.clock = clock;
  }

  public String kind() {
    return "MEDIA_GENERATION_PURGE";
  }

  public String prepare(String payload, Lease lease) {
    String[] parts = payload.split(":");
    var a = store.asset(UUID.fromString(parts[0]), false);
    long generation = Long.parseLong(parts[1]);
    if (a != null && a.generation() == generation) {
      if (a.state().equals("READY")) return "";
      if (a.state().equals("PROCESSING")) throw new Failure(503, "PROCESSING_GENERATION_UNSETTLED");
    }
    return "variants/" + parts[0] + "/" + generation + "/";
  }

  public String execute(String prepared) {
    if (prepared.isEmpty()) return "";
    var s = objects.getIfAvailable();
    if (s == null) throw new Failure(503, "STORAGE_DISABLED");
    if (!s.purgeVariants(prepared)) throw new Failure(503, "PURGE_INCOMPLETE");
    return "";
  }

  public void complete(String payload, Lease lease, String prepared, String result) {}

  public void failed(String payload, Lease lease) {}
}
