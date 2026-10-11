package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.media.application.port.MediaRepository;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Bounded durable sweep, including terminal tombstones; storage is never permission. */
@Component
public class StorageReconciliation implements JobHandler {
  private final MediaRepository store;
  private final DurableWork work;
  private final Clock clock;

  public StorageReconciliation(MediaRepository store, DurableWork work, Clock clock) {
    this.store = store;
    this.work = work;
    this.clock = clock;
  }

  public String kind() {
    return "MEDIA_STORAGE_RECONCILE";
  }

  public void apply(String payload) {
    // Carry the sweep hour through every page, including a replay spanning hours.
    String[] parts = payload.split(":", -1);
    String hour = parts[0];
    UUID after = parts.length == 2 && !parts[1].isEmpty() ? UUID.fromString(parts[1]) : null;
    var ids = store.settledAssets(after, 20, clock.instant().minusSeconds(900));
    for (var id : ids) {
      work.job("MEDIA_CLEANUP", id + ":reconcile:" + hour, id.toString(), clock.instant());
      work.job("MEDIA_QUARANTINE_PURGE", id + ":reconcile:" + hour, id.toString(), clock.instant());
    }
    if (ids.size() == 20)
      work.job(kind(), hour + ":" + ids.getLast(), hour + ":" + ids.getLast(), clock.instant());
  }
}
