package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.platform.api.ExternalJobHandler;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class MediaErasure implements ExternalJobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final MediaRepository assets;
  private final MediaErasureStore store;
  private final ObjectProvider<ObjectStorage> objects;
  private final ObjectProvider<MediaWriterDrain> drain;
  private final ObjectMapper json;

  public MediaErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      MediaRepository assets,
      MediaErasureStore store,
      ObjectProvider<ObjectStorage> objects,
      ObjectProvider<MediaWriterDrain> drain,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.assets = assets;
    this.store = store;
    this.objects = objects;
    this.drain = drain;
    this.json = json;
  }

  public String kind() {
    return "MEDIA_ERASURE";
  }

  public record Prepared(
      UUID subject,
      UUID request,
      long round,
      long stepVersion,
      UUID asset,
      long assetVersion,
      long generation,
      String quarantineKey,
      Instant uploadSettlesAt) {}

  public record Result(String status, String drainDigest) {}

  private void next(Context c, String stage, int examined) {
    erasure.advance(c, stage, examined, "RUNNABLE", null, null, null);
  }

  private void waitFor(Context c, String stage, String reason, Instant due) {
    erasure.advance(c, stage, 0, "WAITING_EXTERNAL", reason, due, null);
  }

  public String prepare(String payload, Lease lease) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.MEDIA) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    var stage = erasure.checkpoint(pass);
    if (stage == null) return "";
    accounts.guard(pass.userId(), false);
    var c = erasure.begin(pass.userId(), Step.MEDIA, pass.version());
    if (c == null) return "";
    if (stage.equals("START") || stage.startsWith("SCAN:")) {
      UUID after =
          stage.equals("START") || stage.equals("SCAN:START")
              ? null
              : UUID.fromString(stage.substring(5));
      var ids = store.scan(c.userId(), after, 100);
      if (ids.isEmpty()) next(c, "PURGE", 0);
      else {
        var chunk = erasure.recordResources(c, "MEDIA_ASSET", ids);
        waitFor(
            c,
            "SCAN_EXPORT:" + chunk + ":" + ids.getLast(),
            "RESOURCE_EXPORT_PENDING",
            erasure.databaseTime().plusSeconds(60));
      }
      return "";
    }
    if (stage.startsWith("SCAN_EXPORT:")) {
      var parts = stage.split(":");
      if (parts.length != 3) throw new IllegalStateException("MEDIA_SCAN_CHECKPOINT_INVALID");
      UUID chunk = UUID.fromString(parts[1]);
      if (!erasure.resourcesExported(c, chunk))
        waitFor(c, stage, "RESOURCE_EXPORT_PENDING", erasure.databaseTime().plusSeconds(60));
      else next(c, "SCAN:" + parts[2], erasure.resourceIds(c, chunk).size());
      return "";
    }
    if (!stage.equals("PURGE")) throw new IllegalStateException("UNKNOWN_MEDIA_ERASURE_STAGE");
    var id = store.nextUnpurged(c.userId());
    if (id == null) {
      assets.quota(c.userId(), erasure.databaseTime());
      if (store.residual(c.userId()))
        waitFor(c, stage, "MEDIA_RESIDUAL", erasure.databaseTime().plusSeconds(60));
      else erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "MEDIA_ABSENCE_V1");
      return "";
    }
    assets.quota(c.userId(), erasure.databaseTime());
    var a = assets.asset(id, true);
    if (a == null || !a.owner().equals(c.userId()))
      throw new IllegalStateException("MEDIA_ERASURE_ASSET_MISSING");
    var binding = assets.binding(id);
    if (binding != null && binding.released() == null) {
      erasure.advance(
          c,
          stage,
          0,
          "WAITING_DEPENDENCY",
          "MEDIA_BINDING_ACTIVE",
          erasure.databaseTime().plusSeconds(60),
          null);
      return "";
    }
    if (erasure.databaseTime().isBefore(a.uploadSettles())) {
      waitFor(c, stage, "UPLOAD_SETTLING", a.uploadSettles());
      return "";
    }
    store.claim(c.userId(), c.requestId(), id, erasure.databaseTime());
    int variants = store.pruneVariants(id, 100);
    if (variants > 0) {
      next(c, "PURGE", variants);
      return "";
    }
    var fenced = assets.asset(id, false);
    return json.writeValueAsString(
        new Prepared(
            c.userId(),
            c.requestId(),
            c.round(),
            c.version(),
            id,
            fenced.version(),
            fenced.generation(),
            fenced.key(),
            fenced.uploadSettles()));
  }

  public String execute(String prepared) {
    if (prepared.isEmpty()) return "";
    var p = json.readValue(prepared, Prepared.class);
    var provider = drain.getIfAvailable();
    if (provider == null) return json.writeValueAsString(new Result("WRITER_DRAIN_UNPROVEN", null));
    var proof = provider.attest(p.asset(), p.generation(), p.uploadSettlesAt());
    if (proof == null
        || !p.asset().equals(proof.asset())
        || proof.fencedGeneration() != p.generation()
        || proof.observedAt() == null
        || proof.observedAt().isBefore(p.uploadSettlesAt())
        || proof.digest() == null
        || !proof.digest().matches("[a-f0-9]{64}"))
      return json.writeValueAsString(new Result("WRITER_DRAIN_UNPROVEN", null));
    var storage = objects.getIfAvailable();
    if (storage == null) return json.writeValueAsString(new Result("STORAGE_DISABLED", null));
    boolean absent = storage.purge(p.quarantineKey(), "variants/" + p.asset() + "/");
    return json.writeValueAsString(new Result(absent ? "CONFIRMED" : "INCOMPLETE", proof.digest()));
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if (prepared.isEmpty()) return;
    var pass = json.readValue(payload, Pass.class);
    if (erasure.checkpoint(pass) == null) return;
    accounts.guard(pass.userId(), false);
    var c = erasure.begin(pass.userId(), Step.MEDIA, pass.version());
    if (c == null) return;
    var p = json.readValue(prepared, Prepared.class);
    if (!c.userId().equals(p.subject())
        || !c.requestId().equals(p.request())
        || c.round() != p.round()
        || c.version() != p.stepVersion())
      throw new IllegalStateException("MEDIA_ERASURE_RECEIPT_STALE");
    assets.quota(c.userId(), erasure.databaseTime());
    var a = assets.asset(p.asset(), true);
    if (a == null
        || !c.requestId().equals(a.erasureRequest())
        || a.version() != p.assetVersion()
        || a.generation() != p.generation()) {
      next(c, "PURGE", 0);
      return;
    }
    var r = json.readValue(result, Result.class);
    switch (r.status()) {
      case "WRITER_DRAIN_UNPROVEN", "STORAGE_DISABLED" ->
          waitFor(c, "PURGE", r.status(), erasure.databaseTime().plusSeconds(60));
      case "INCOMPLETE" -> next(c, "PURGE", 0);
      case "CONFIRMED" -> {
        if (!store.complete(
            c.userId(),
            c.requestId(),
            p.asset(),
            p.assetVersion(),
            r.drainDigest(),
            erasure.databaseTime())) throw new IllegalStateException("MEDIA_ERASURE_RECEIPT_STALE");
        next(c, "PURGE", 1);
      }
      default -> throw new IllegalStateException("MEDIA_ERASURE_RESULT_INVALID");
    }
  }

  public void failed(String payload, Lease lease) {
    erasure.executionFailed(json.readValue(payload, Pass.class), lease.attempt());
  }
}
