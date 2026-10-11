package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.media.domain.MediaState.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ImageWork implements ExternalJobHandler {
  private final MediaRepository store;
  private final ObjectProvider<ObjectStorage> objects;
  private final ImageProcessor processor;
  private final Clock clock;
  private final DurableWork work;
  private final tools.jackson.databind.ObjectMapper json;
  private final MediaLimits limits;

  public ImageWork(
      MediaRepository store,
      ObjectProvider<ObjectStorage> objects,
      ImageProcessor processor,
      Clock clock,
      DurableWork work,
      tools.jackson.databind.ObjectMapper json,
      MediaLimits limits) {
    this.store = store;
    this.objects = objects;
    this.processor = processor;
    this.clock = clock;
    this.work = work;
    this.json = json;
    this.limits = limits;
  }

  public String kind() {
    return "MEDIA_PROCESS";
  }

  public String prepare(String payload, Lease lease) {
    UUID id = UUID.fromString(payload);
    var first = store.asset(id, false);
    if (first == null) return "";
    store.quota(first.owner(), clock.instant());
    var a = store.asset(id, true);
    if (!List.of("UPLOADED", "PROCESSING").contains(a.state())) return "";
    store.processing(id, lease.generation(), clock.instant());
    // Every generation has a durable orphan check, including crash before READY.
    work.job(
        "MEDIA_GENERATION_PURGE",
        id + ":" + lease.generation(),
        id + ":" + lease.generation(),
        lease.until().plusSeconds(60));
    return json.writeValueAsString(store.asset(id, false));
  }

  public record Result(List<Variant> variants, String rejection) {}

  public String execute(String prepared) {
    if (prepared.isEmpty()) return "";
    var a = json.readValue(prepared, Asset.class);
    var s = objects.getIfAvailable();
    if (s == null) throw new Failure(503, "STORAGE_DISABLED");
    byte[] bytes = s.readUpload(a.key(), a.uploadedVersion(), a.declaredBytes());
    try {
      String actual =
          java.util.Base64.getEncoder()
              .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      if (bytes.length != a.declaredBytes() || !actual.equals(a.sha256()))
        return json.writeValueAsString(new Result(List.of(), "IMAGE_CHECKSUM_MISMATCH"));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    List<ImageProcessor.Image> images;
    try {
      images = processor.process(bytes, a.mime());
    } catch (Failure invalid) {
      if (invalid.status() != 415 && invalid.status() != 413) throw invalid;
      return json.writeValueAsString(new Result(List.of(), invalid.code()));
    }
    var variants = new java.util.ArrayList<Variant>();
    for (var image : images) {
      String key =
          "variants/"
              + a.id()
              + "/"
              + a.generation()
              + "/"
              + image.name().toLowerCase(java.util.Locale.ROOT)
              + ".png";
      var written = s.writeVariant(key, image.mime(), image.bytes());
      variants.add(
          new Variant(
              image.name(),
              key,
              written.versionId(),
              image.mime(),
              written.bytes(),
              image.width(),
              image.height(),
              a.generation()));
    }
    return json.writeValueAsString(new Result(List.copyOf(variants), null));
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if (prepared.isEmpty()) return;
    var snapshot = json.readValue(prepared, Asset.class);
    var q = store.quota(snapshot.owner(), clock.instant());
    var a = store.asset(snapshot.id(), true);
    if (!a.state().equals("PROCESSING") || a.generation() != lease.generation()) return;
    var r = json.readValue(result, Result.class);
    long retained = r.variants().stream().mapToLong(Variant::bytes).sum();
    String rejection = r.rejection();
    if (rejection == null
        && (r.variants().size() != 2
            || q.reserved() - a.declaredBytes() + q.retained() + retained > limits.retainedBytes()))
      rejection = "MEDIA_QUOTA_EXCEEDED";
    if (rejection != null) {
      store.state(a.id(), "REJECTED", rejection, clock.instant());
      work.job("MEDIA_CLEANUP", a.id() + ":rejected", a.id().toString(), clock.instant());
      work.event(
          "MediaRejected",
          "media",
          a.id(),
          a.version() + 1,
          0,
          json.writeValueAsString(java.util.Map.of("assetId", a.id(), "reasonCode", rejection)));
    } else {
      store.charge(a.owner(), -a.declaredBytes(), retained);
      store.ready(a.id(), a.generation(), r.variants(), clock.instant());
      work.job(
          "MEDIA_QUARANTINE_PURGE",
          a.id().toString(),
          a.id().toString(),
          a.uploadSettles().isAfter(clock.instant()) ? a.uploadSettles() : clock.instant());
      work.job(
          "MEDIA_CLEANUP",
          a.id() + ":ready",
          a.id().toString(),
          clock.instant().plusSeconds(86400));
      work.event(
          "MediaReady",
          "media",
          a.id(),
          a.version() + 1,
          0,
          json.writeValueAsString(java.util.Map.of("assetId", a.id())));
    }
  }

  public void failed(String payload, Lease lease) {
    if (lease.attempt() < 10) return;
    UUID id = UUID.fromString(payload);
    var first = store.asset(id, false);
    if (first == null) return;
    store.quota(first.owner(), clock.instant());
    var a = store.asset(id, true);
    if (a.state().equals("PROCESSING") && a.generation() == lease.generation()) {
      store.state(id, "FAILED", "PROCESSING_EXHAUSTED", clock.instant());
      work.job("MEDIA_CLEANUP", id + ":failed", id.toString(), clock.instant().plusSeconds(86400));
    }
  }
}
