package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.media.api.MediaCommands;
import com.flyvo.socialcore.media.api.MediaQueries;
import com.flyvo.socialcore.media.application.port.MediaRepository;
import com.flyvo.socialcore.media.application.port.ObjectStorage;
import com.flyvo.socialcore.media.domain.MediaState.Asset;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Images implements MediaCommands, MediaQueries {
  private final MediaRepository store;
  private final AccountQueries accounts;
  private final DurableWork work;
  private final Idempotency idempotency;
  private final Clock clock;
  private final ObjectProvider<ObjectStorage> storage;
  private final TransactionTemplate tx;
  private final tools.jackson.databind.ObjectMapper json;
  private final MediaLimits limits;

  public Images(
      MediaRepository store,
      AccountQueries accounts,
      DurableWork work,
      Idempotency idempotency,
      Clock clock,
      ObjectProvider<ObjectStorage> storage,
      org.springframework.transaction.PlatformTransactionManager manager,
      tools.jackson.databind.ObjectMapper json,
      MediaLimits limits) {
    this.store = store;
    this.accounts = accounts;
    this.work = work;
    this.idempotency = idempotency;
    this.clock = clock;
    this.storage = storage;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
    this.limits = limits;
  }

  public record UploadRequest(String purpose, String mime, long bytes, String sha256) {}

  public record UploadIntent(
      UUID id,
      String state,
      String uploadUrl,
      java.util.Map<String, List<String>> headers,
      Instant expiresAt) {}

  public record Status(UUID id, String purpose, String state, long version) {}

  private ObjectStorage storage() {
    var s = storage.getIfAvailable();
    if (s == null) throw new Failure(503, "STORAGE_DISABLED");
    return s;
  }

  public UploadIntent upload(ActorContext actor, UploadRequest request, String key) {
    var s = storage();
    if (request == null
        || !java.util.Set.of("POST", "AVATAR")
            .contains(request.purpose() == null ? "" : request.purpose())) throw Failure.invalid();
    // Validate signing inputs before reserving DB quota (no storage network IO).
    s.upload("incoming/validation", request.mime(), request.bytes(), request.sha256());
    var a =
        tx.execute(
            status -> {
              guard(actor);
              UUID previous =
                  idempotency.claim(
                      actor.userId(), "POST /media/uploads", key, json.writeValueAsString(request));
              if (previous != null) {
                var old = owned(actor, previous, false);
                if (!old.finalizable(actor.userId(), clock.instant()))
                  throw new Failure(409, "UPLOAD_NOT_PENDING");
                return old;
              }
              var q = store.quota(actor.userId(), clock.instant());
              boolean reset = !clock.instant().isBefore(q.window().plusSeconds(60));
              if (!reset && q.issued() >= limits.uploadsPerMinute())
                throw new Failure(429, "RATE_LIMITED");
              if (q.reserved() + request.bytes() > limits.pendingBytes()
                  || q.reserved() + q.retained() + request.bytes() > limits.retainedBytes())
                throw new Failure(409, "MEDIA_QUOTA_EXCEEDED");
              var id = UUID.randomUUID();
              var now = clock.instant();
              var asset =
                  new Asset(
                      id,
                      actor.userId(),
                      request.purpose(),
                      "UPLOAD_PENDING",
                      "incoming/" + id,
                      null,
                      request.mime(),
                      request.bytes(),
                      request.sha256(),
                      0,
                      0,
                      now.plusSeconds(900),
                      now.plusSeconds(86400),
                      null,
                      0);
              store.create(asset, now);
              store.charge(actor.userId(), request.bytes(), 0);
              store.issued(actor.userId(), now, reset);
              cleanup(asset, now.plusSeconds(900), "pending");
              idempotency.complete(actor.userId(), "POST /media/uploads", key, id);
              return asset;
            });
    Duration remaining = Duration.between(clock.instant(), a.uploadExpires());
    var upload =
        s.upload(
            a.key(),
            a.mime(),
            a.declaredBytes(),
            a.sha256(),
            remaining.compareTo(Duration.ofMinutes(10)) < 0 ? remaining : Duration.ofMinutes(10));
    return new UploadIntent(a.id(), a.state(), upload.url(), upload.headers(), a.uploadExpires());
  }

  public Status finalizeUpload(ActorContext actor, UUID id, String version) {
    var observed =
        tx.execute(
            s -> {
              require(actor);
              return owned(actor, id, false);
            });
    if (observed.uploadedVersion() != null) {
      if (!observed.uploadedVersion().equals(version))
        throw new Failure(409, "UPLOAD_VERSION_CONFLICT");
      return status(actor, id);
    }
    if (!observed.finalizable(actor.userId(), clock.instant()))
      throw new Failure(409, "UPLOAD_EXPIRED");
    var info = storage().inspectUpload(observed.key(), version);
    if (info.bytes() != observed.declaredBytes()
        || !observed.mime().equals(info.contentType())
        || !observed.sha256().equals(info.sha256()))
      throw new Failure(409, "UPLOAD_METADATA_MISMATCH");
    return tx.execute(
        s -> {
          guard(actor);
          store.quota(actor.userId(), clock.instant());
          var a = owned(actor, id, true);
          if (a.uploadedVersion() != null) {
            if (!a.uploadedVersion().equals(version))
              throw new Failure(409, "UPLOAD_VERSION_CONFLICT");
            return status(a);
          }
          if (!a.finalizable(actor.userId(), clock.instant()) || a.version() != observed.version())
            throw new Failure(409, "UPLOAD_NOT_PENDING");
          store.finalizeUpload(id, version, clock.instant());
          work.job("MEDIA_PROCESS", id.toString(), id.toString(), clock.instant());
          work.event(
              "MediaUploaded",
              "media",
              id,
              a.version() + 1,
              0,
              json.writeValueAsString(java.util.Map.of("assetId", id)));
          return status(store.asset(id, false));
        });
  }

  @Transactional(readOnly = true)
  public Status status(ActorContext actor, UUID id) {
    require(actor);
    return status(owned(actor, id, false));
  }

  @Transactional
  public void delete(ActorContext actor, UUID id) {
    guard(actor);
    store.quota(actor.userId(), clock.instant());
    var a = owned(actor, id, true);
    if (store.binding(id) != null) throw new Failure(409, "MEDIA_ALREADY_BOUND");
    if (java.util.Set.of("UPLOADED", "PROCESSING").contains(a.state()))
      throw new Failure(409, "MEDIA_PROCESSING");
    if (a.state().equals("DELETED") || a.state().equals("DELETING")) return;
    store.state(id, "DELETING", null, clock.instant());
    cleanup(a, clock.instant(), "owner-delete");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void bind(UUID owner, String purpose, UUID context, List<UUID> ids) {
    if (ids == null
        || ids.size() > 10
        || ids.stream().anyMatch(java.util.Objects::isNull)
        || new java.util.HashSet<>(ids).size() != ids.size()) throw Failure.invalid();
    if (ids.isEmpty()) return;
    store.quota(owner, clock.instant());
    var sorted = ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    for (var id : sorted) {
      var a = store.asset(id, true);
      if (a == null
          || !a.owner().equals(owner)
          || !a.purpose().equals(purpose)
          || !a.state().equals("READY")
          || store.binding(id) != null
          || !clock.instant().isBefore(a.orphanExpires()))
        throw new Failure(409, "MEDIA_NOT_BINDABLE");
    }
    for (var id : sorted) store.bind(id, purpose, context, clock.instant());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void release(
      UUID owner, String purpose, UUID context, List<UUID> ids, Instant purgeAfter) {
    if (ids.isEmpty()) return;
    store.quota(owner, clock.instant());
    for (var id : ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
      var a = store.asset(id, true);
      var b = store.binding(id);
      if (a == null
          || !a.owner().equals(owner)
          || b == null
          || !b.purpose().equals(purpose)
          || !b.context().equals(context)) throw new Failure(409, "MEDIA_BINDING_MISMATCH");
      store.release(id, clock.instant(), purgeAfter);
      cleanup(a, purgeAfter, "release-" + a.version());
    }
  }

  @Transactional(readOnly = true)
  public List<MediaQueries.Variant> access(
      String purpose, UUID context, UUID id, boolean historical) {
    var a = store.asset(id, false);
    var b = store.binding(id);
    if (a == null
        || !a.state().equals("READY")
        || b == null
        || !b.purpose().equals(purpose)
        || !b.context().equals(context)
        || b.released() != null
            && (!historical || a.purgeAfter() == null || !clock.instant().isBefore(a.purgeAfter())))
      throw new Failure(404, "NOT_FOUND");
    var variants = store.variants(id, a.generation());
    if (variants.size() != 2) throw new Failure(404, "NOT_FOUND");
    return variants.stream()
        .map(
            v ->
                new MediaQueries.Variant(
                    v.name(),
                    storage().variantAccess(v.key(), v.objectVersion(), Duration.ofSeconds(60)),
                    v.mime(),
                    v.width(),
                    v.height()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<BindingFacts> bindingFacts(List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull))
      throw Failure.invalid();
    return store.bindingFacts(ids);
  }

  private void require(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
  }

  private void guard(ActorContext actor) {
    require(actor);
    if (!accounts.guard(actor.userId(), false).active()) throw Failure.unauthorized();
  }

  private Asset owned(ActorContext actor, UUID id, boolean lock) {
    var a = store.asset(id, lock);
    if (a == null || !a.owner().equals(actor.userId())) throw new Failure(404, "NOT_FOUND");
    return a;
  }

  private Status status(Asset a) {
    return new Status(a.id(), a.purpose(), a.state(), a.version());
  }

  public void cleanup(Asset a, Instant due, String key) {
    work.job("MEDIA_CLEANUP", a.id() + ":" + key, a.id().toString(), due);
  }
}
