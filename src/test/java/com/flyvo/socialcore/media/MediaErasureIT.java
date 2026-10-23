package com.flyvo.socialcore.media;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.account.application.*;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import com.flyvo.socialcore.content.application.ContentErasure;
import com.flyvo.socialcore.media.application.MediaErasure;
import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.media.infrastructure.storage.S3ObjectStorage;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.api.ProfileCommands;
import com.flyvo.socialcore.profile.application.ProfileErasure;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.core.sync.RequestBody;

/** Positive drain evidence exists only in this closed, joined synthetic writer harness. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MediaErasureIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
              "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
          .withDatabaseName("p9a_media");

  @Container static final GenericContainer<?> SILO = StorageContractIT.SILO;
  static final Path ARCHIVE = directory();

  static Path directory() {
    try {
      return Files.createTempDirectory("social-p9a-media-archive-");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
    r.add("app.erasure.enabled", () -> true);
    r.add("app.erasure.policy", () -> AccountErasure.POLICY);
    r.add("app.erasure.lineage", () -> "834ac0bf-b581-47f9-b3d7-954cc3cd6411");
    r.add("app.erasure.archive-directory", ARCHIVE::toString);
  }

  @BeforeAll
  static void bootstrapStorage() throws Exception {
    StorageContractIT.configureStorage();
  }

  @AfterAll
  static void closeStorage() {
    StorageContractIT.closeClients();
  }

  @Autowired AccountCommands accounts;
  @Autowired AccountQueries queries;
  @Autowired ProfileCommands profiles;
  @Autowired AccountErasure erasure;
  @Autowired Erasures internal;
  @Autowired ErasureStore steps;
  @Autowired ProfileErasure profile;
  @Autowired ContentErasure content;
  @Autowired ErasureExports exports;
  @Autowired DurableWorkStore jobs;
  @Autowired MediaRepository assets;
  @Autowired com.flyvo.socialcore.media.application.ImageWork processing;
  @Autowired MediaErasureStore store;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired tools.jackson.databind.ObjectMapper json;

  TransactionTemplate tx() {
    return new TransactionTemplate(manager);
  }

  void export() {
    for (int n = 0; n < 12; n++) jobs.consumeExternalJob(UUID.randomUUID(), List.of(exports));
  }

  String pass(UUID subject, AccountErasure.Step step) {
    var s = steps.step(subject, step, false);
    return json.writeValueAsString(
        new AccountErasure.Pass(subject, step, s.view().version(), s.execution()));
  }

  UUID pending() {
    UUID id =
        tx().execute(
                s -> {
                  var u = accounts.createVerified();
                  profiles.provision(u);
                  return u;
                });
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=clock_timestamp()-interval '31 days',version=version+1 WHERE id=?",
        id);
    tx().executeWithoutResult(s -> internal.reconcile(id));
    export();
    tx().executeWithoutResult(s -> internal.reconcile(id));
    tx().executeWithoutResult(s -> profile.apply(pass(id, AccountErasure.Step.PROFILE)));
    for (int n = 0;
        n < 12
            && !"DONE".equals(steps.step(id, AccountErasure.Step.CONTENT, false).view().status());
        n++) tx().executeWithoutResult(s -> content.apply(pass(id, AccountErasure.Step.CONTENT)));
    tx().executeWithoutResult(s -> internal.reconcile(id));
    return id;
  }

  UUID asset(UUID owner, String state, long reserved) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO media.quota(owner_id,reserved_bytes,window_start) VALUES (?,?,now()) ON CONFLICT(owner_id) DO UPDATE SET reserved_bytes=media.quota.reserved_bytes+EXCLUDED.reserved_bytes",
        owner,
        reserved);
    jdbc.update(
        "INSERT INTO media.asset(id,owner_id,purpose,state,quarantine_key,declared_mime,declared_bytes,sha256,upload_expires_at,orphan_expires_at,created_at,updated_at) VALUES (?,?,'POST',?,?,'image/png',7,?,now()-interval '1 day',now()-interval '1 day',now(),now())",
        id,
        owner,
        state,
        "incoming/" + id,
        Base64.getEncoder().encodeToString(new byte[32]));
    return id;
  }

  MediaErasure handler(ObjectStorage storage, MediaWriterDrain drain) {
    var beans = new DefaultListableBeanFactory();
    if (storage != null) beans.registerSingleton("objects", storage);
    if (drain != null) beans.registerSingleton("drain", drain);
    return new MediaErasure(
        erasure,
        queries,
        assets,
        store,
        beans.getBeanProvider(ObjectStorage.class),
        beans.getBeanProvider(MediaWriterDrain.class),
        json);
  }

  ObjectStorage storage() {
    return new S3ObjectStorage(
        StorageContractIT.api,
        StorageContractIT.runtime,
        StorageContractIT.presigner,
        StorageContractIT.QUARANTINE,
        StorageContractIT.DELIVERY);
  }

  ExternalJobHandler.Lease lease() {
    return new ExternalJobHandler.Lease(1, 1, Instant.now().plusSeconds(60));
  }

  String prepare(MediaErasure h, UUID owner) {
    return tx().execute(s -> h.prepare(pass(owner, AccountErasure.Step.MEDIA), lease()));
  }

  void executePass(MediaErasure h, UUID owner) {
    String payload = pass(owner, AccountErasure.Step.MEDIA);
    var lease = lease();
    String p = tx().execute(s -> h.prepare(payload, lease));
    String r = h.execute(p);
    tx().executeWithoutResult(s -> h.complete(payload, lease, p, r));
  }

  void scan(MediaErasure h, UUID owner) {
    for (int n = 0;
        n < 10 && !"PURGE".equals(steps.step(owner, AccountErasure.Step.MEDIA, false).checkpoint());
        n++) {
      executePass(h, owner);
      export();
    }
    assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).checkpoint()).isEqualTo("PURGE");
  }

  static final class ControlledWriters implements MediaWriterDrain {
    final List<CompletableFuture<?>> accepted = new ArrayList<>();
    boolean sealed;

    synchronized void accept(CompletableFuture<?> writer) {
      if (sealed) throw new IllegalStateException("Writer admission closed");
      accepted.add(writer);
    }

    synchronized void seal() {
      sealed = true;
    }

    public synchronized Proof attest(UUID asset, long generation, Instant settles) {
      assertThat(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive())
          .isFalse();
      if (!sealed || accepted.stream().anyMatch(f -> !f.isDone() || f.isCompletedExceptionally()))
        return null;
      return new Proof(
          asset,
          generation,
          Instant.now(),
          ErasureProtocol.digest(
              "synthetic-joined-writers:" + asset + ":" + generation + ":" + accepted.size()));
    }
  }

  @Test
  void processingSnapshotCannotCommitOrExhaustAfterCloseBeforeMediaClaim() {
    UUID owner =
        tx().execute(
                s -> {
                  var id = accounts.createVerified();
                  profiles.provision(id);
                  return id;
                });
    UUID id = asset(owner, "FAILED", 7);
    jdbc.update(
        "UPDATE media.asset SET state='UPLOADED',uploaded_version_id='synthetic-version' WHERE id=?",
        id);
    var processLease = new ExternalJobHandler.Lease(7, 10, Instant.now().plusSeconds(60));
    String prepared = tx().execute(s -> processing.prepare(id.toString(), processLease));
    long version = assets.asset(id, false).version();
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=clock_timestamp(),version=version+1 WHERE id=?",
        owner);
    tx().executeWithoutResult(
            s ->
                processing.complete(
                    id.toString(),
                    processLease,
                    prepared,
                    json.writeValueAsString(
                        new com.flyvo.socialcore.media.application.ImageWork.Result(
                            List.of(), "SYNTHETIC_REJECTION"))));
    tx().executeWithoutResult(s -> processing.failed(id.toString(), processLease));
    assertThat(assets.asset(id, false).state()).isEqualTo("PROCESSING");
    assertThat(assets.asset(id, false).version()).isEqualTo(version);
    assertThat(tx().<String>execute(s -> processing.prepare(id.toString(), processLease)))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_bytes FROM media.quota WHERE owner_id=?", Long.class, owner))
        .isEqualTo(7);
  }

  @Test
  void tenThousandAllStateAssetsAreIndependentlyEnumeratedBeforeAnyMappingCanBeLost() {
    UUID owner = pending();
    String prefix = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO media.quota(owner_id,window_start) VALUES (?,now()) ON CONFLICT DO NOTHING",
        owner);
    jdbc.update(
        "INSERT INTO media.asset(id,owner_id,purpose,state,quarantine_key,declared_mime,declared_bytes,sha256,upload_expires_at,orphan_expires_at,created_at,updated_at) SELECT md5(?||n)::uuid,?,'POST',CASE WHEN n%2=0 THEN 'DELETED' ELSE 'FAILED' END,'incoming/'||md5(?||n)::uuid,'image/png',7,?,now()-interval '1 day',now()-interval '1 day',now(),now() FROM generate_series(1,10001) n",
        prefix, owner, prefix, Base64.getEncoder().encodeToString(new byte[32]));
    // FAILED fixtures carry their real reserved quota; DELETED fixtures have already released it.
    jdbc.update("UPDATE media.quota SET reserved_bytes=5001*7 WHERE owner_id=?", owner);
    var h = handler(storage(), null);
    int passes = 0;
    long peak = 0;
    while (!"PURGE".equals(steps.step(owner, AccountErasure.Step.MEDIA, false).checkpoint())) {
      long before = steps.step(owner, AccountErasure.Step.MEDIA, false).examined();
      executePass(h, owner);
      export();
      assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).examined() - before)
          .isBetween(0L, 100L);
      peak = Math.max(peak, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
      assertThat(++passes).isLessThan(250);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(cardinality(resource_ids)) FROM account.erasure_export WHERE intent_id=? AND owner='media' AND resource_kind='MEDIA_ASSET' AND receipt_digest=digest",
                Long.class,
                steps.request(owner).id()))
        .isEqualTo(10001);
    executePass(h, owner);
    assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().status())
        .isEqualTo("WAITING_EXTERNAL");
    assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().reason())
        .isEqualTo("WRITER_DRAIN_UNPROVEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM media.asset WHERE owner_id=? AND erasure_purged_at IS NOT NULL",
                Integer.class,
                owner))
        .isZero();
    System.out.println(
        "P9AII_LARGE_MEDIA assets=10001 exportedChunks=101 passes="
            + passes
            + " maxCollectionItems=100 peakHeapBytes="
            + peak
            + " drain=UNPROVEN");
  }

  @Test
  void oldGenerationVariantMetadataIsPrunedInHundredItemPassesAndCannotReturnAfterClaim() {
    UUID owner = pending(), id = asset(owner, "FAILED", 7);
    jdbc.update(
        "INSERT INTO media.variant(id,asset_id,name,object_key,object_version_id,mime,bytes,width,height,generation) SELECT gen_random_uuid(),?,'DISPLAY','variants/'||?::text||'/'||n||'/display.png','synthetic-version-'||n,'image/png',7,2,2,n FROM generate_series(1,10001) n",
        id,
        id);
    var h = handler(storage(), null);
    scan(h, owner);
    int passes = 0;
    while (jdbc.queryForObject(
            "SELECT count(*) FROM media.variant WHERE asset_id=?", Integer.class, id)
        > 0) {
      int before =
          jdbc.queryForObject(
              "SELECT count(*) FROM media.variant WHERE asset_id=?", Integer.class, id);
      executePass(h, owner);
      int after =
          jdbc.queryForObject(
              "SELECT count(*) FROM media.variant WHERE asset_id=?", Integer.class, id);
      assertThat(before - after).isBetween(1, 100);
      assertThat(++passes).isLessThan(103);
    }
    assertThat(passes).isEqualTo(101);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO media.variant(id,asset_id,name,object_key,object_version_id,mime,bytes,width,height,generation) VALUES (?,?,'DISPLAY',?,'synthetic-late-version','image/png',7,2,2,20000)",
                    UUID.randomUUID(),
                    id,
                    "variants/" + id + "/20000/display.png"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    executePass(h, owner);
    assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().reason())
        .isEqualTo("WRITER_DRAIN_UNPROVEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_bytes FROM media.quota WHERE owner_id=?", Long.class, owner))
        .isEqualTo(7);
  }

  @Test
  void missingDrainAndDisabledStorageCannotCertifyEvenPreviouslyDeletedAssets() {
    UUID owner = pending(), id = asset(owner, "DELETED", 0);
    var noDrain = handler(storage(), null);
    scan(noDrain, owner);
    executePass(noDrain, owner);
    assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().reason())
        .isEqualTo("WRITER_DRAIN_UNPROVEN");
    var writers = new ControlledWriters();
    writers.seal();
    executePass(handler(null, writers), owner);
    assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().reason())
        .isEqualTo("STORAGE_DISABLED");
    assertThat(assets.asset(id, false).erasurePurgedAt()).isNull();
  }

  @Test
  void joinedLateWritersAllVersionsCrashBeforeAckAndStaleGenerationAccountExactlyOnce()
      throws Exception {
    UUID owner = pending(), id = asset(owner, "FAILED", 7);
    String key = "incoming/" + id, variant = "variants/" + id + "/0/display.png";
    for (int n = 0; n < 55; n++) {
      StorageContractIT.api.putObject(
          r -> r.bucket(StorageContractIT.QUARANTINE).key(key),
          RequestBody.fromString("synthetic " + n));
      StorageContractIT.runtime.deleteObject(r -> r.bucket(StorageContractIT.QUARANTINE).key(key));
    }
    var writers = new ControlledWriters();
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      writers.accept(
          CompletableFuture.runAsync(
              () -> {
                try {
                  if (!release.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Synthetic writer deadline");
                } catch (InterruptedException e) {
                  throw new IllegalStateException(e);
                }
                StorageContractIT.api.putObject(
                    r -> r.bucket(StorageContractIT.QUARANTINE).key(key),
                    RequestBody.fromString("late ingress"));
                StorageContractIT.runtime.putObject(
                    r -> r.bucket(StorageContractIT.DELIVERY).key(variant),
                    RequestBody.fromString("late variant"));
              },
              executor));
      writers.seal();
      var h = handler(storage(), writers);
      scan(h, owner);
      executePass(h, owner);
      assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().reason())
          .isEqualTo("WRITER_DRAIN_UNPROVEN");
      release.countDown();
      for (var future : writers.accepted) future.get(15, TimeUnit.SECONDS);
      // External deletion can commit while its DB acknowledgement is lost.
      String stalePayload = pass(owner, AccountErasure.Step.MEDIA),
          stalePrepared = prepare(h, owner);
      String ignored = h.execute(stalePrepared);
      for (int n = 0; n < 10 && assets.asset(id, false).erasurePurgedAt() == null; n++)
        executePass(h, owner);
      assertThat(assets.asset(id, false).erasurePurgedAt()).isNotNull();
      tx().executeWithoutResult(s -> h.complete(stalePayload, lease(), stalePrepared, ignored));
      executePass(h, owner);
      assertThat(steps.step(owner, AccountErasure.Step.MEDIA, false).view().status())
          .isEqualTo("DONE");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reserved_bytes+retained_bytes FROM media.quota WHERE owner_id=?",
                  Long.class,
                  owner))
          .isZero();
      assertThat(
              StorageContractIT.runtime
                  .listObjectVersions(r -> r.bucket(StorageContractIT.QUARANTINE).prefix(key))
                  .versions())
          .isEmpty();
      assertThat(
              StorageContractIT.runtime
                  .listObjectVersions(r -> r.bucket(StorageContractIT.QUARANTINE).prefix(key))
                  .deleteMarkers())
          .isEmpty();
      assertThat(
              StorageContractIT.runtime
                  .listObjectVersions(
                      r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + id + "/"))
                  .versions())
          .isEmpty();
      assertThat(assets.asset(id, false).sha256()).isNull();
    }
  }
}
