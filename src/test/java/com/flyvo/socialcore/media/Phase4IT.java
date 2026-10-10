package com.flyvo.socialcore.media;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.graph.application.Relationships;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.media.api.MediaCommands;
import com.flyvo.socialcore.media.application.*;
import com.flyvo.socialcore.media.application.port.*;
import com.flyvo.socialcore.moderation.application.CommunityPostModeration;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.application.Profiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class Phase4IT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @Container static final GenericContainer<?> SILO = StorageContractIT.SILO;
  static final Path KEYS = keys();

  static Path keys() {
    try {
      var p = Files.createTempDirectory("social-phase4-test-");
      Files.writeString(p.resolve("api"), StorageContractIT.API_SECRET);
      Files.writeString(p.resolve("worker"), StorageContractIT.RUNTIME_SECRET);
      return p;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
    r.add("app.media.enabled", () -> true);
    r.add("app.media.endpoint", () -> StorageContractIT.endpoint().toString());
    r.add("app.media.api-key-file", () -> KEYS.resolve("api").toString());
    r.add("app.media.worker-key-file", () -> KEYS.resolve("worker").toString());
  }

  @BeforeAll
  static void bootstrap() throws Exception {
    StorageContractIT.configureStorage();
  }

  @AfterAll
  static void close() throws Exception {
    StorageContractIT.closeClients();
    Files.delete(KEYS.resolve("api"));
    Files.delete(KEYS.resolve("worker"));
    Files.delete(KEYS);
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-10-10T00:00:00Z");

    public Instant instant() {
      return now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    MutableClock phaseClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired AccountCommands accountCommands;
  @Autowired AccountQueries accounts;
  @Autowired Profiles profiles;
  @Autowired ContentCommands posts;
  @Autowired ContentQueries reads;
  @Autowired CommunityCommands communities;
  @Autowired MembershipCommands memberships;
  @Autowired MembershipQueries membershipQueries;
  @Autowired Relationships graph;
  @Autowired Images images;
  @Autowired ImageWork processing;
  @Autowired ImageCleanup cleanup;
  @Autowired GenerationCleanup generations;
  @Autowired QuarantineCleanup quarantine;
  @Autowired QuotaReconciliation quotaRepair;
  @Autowired com.flyvo.socialcore.content.application.PostBindingReconciliation postRepair;
  @Autowired com.flyvo.socialcore.content.application.PostRetention retention;
  @Autowired CommunityPostModeration moderation;
  @Autowired DurableWorkStore work;
  @MockitoSpyBean JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired AccessTokens tokens;
  @Autowired tools.jackson.databind.ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired MediaCommands bindings;
  @MockitoSpyBean ObjectStorage objects;
  @MockitoSpyBean MediaRepository assets;
  @MockitoSpyBean PostRepository repository;
  @MockitoSpyBean com.flyvo.socialcore.moderation.application.port.ModerationActions actionStore;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    tx = new TransactionTemplate(manager);
    clock.now = Instant.parse("2026-10-10T00:00:00Z");
  }

  ActorContext user() {
    UUID id =
        tx.execute(
            s -> {
              var u = accountCommands.createVerified();
              profiles.provision(u);
              return u;
            });
    UUID sid = UUID.randomUUID();
    var t = clock.instant().atOffset(ZoneOffset.UTC);
    jdbc.update(
        "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        sid,
        id,
        t,
        t,
        t,
        t.plusDays(7),
        t.plusDays(30),
        t);
    return new ActorContext(id, sid);
  }

  ContentCommands.Publish request(
      String audience, UUID community, List<UUID> recipients, List<UUID> media) {
    return new ContentCommands.Publish(
        "plain #Hello",
        "https://example.invalid/link",
        community,
        new ContentCommands.Audience(audience, recipients),
        List.of(),
        media.stream().map(id -> new ContentCommands.Attachment(id, "Synthetic image")).toList());
  }

  ContentQueries.PostView publish(ActorContext author, String audience, List<UUID> recipients) {
    return posts.publish(author, request(audience, null, recipients, List.of()), null);
  }

  void denied(Runnable action, int status) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  byte[] png(int width, int height) throws Exception {
    var im = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    im.setRGB(0, 0, 0xff21aabc);
    var b = new ByteArrayOutputStream();
    ImageIO.write(im, "png", b);
    return b.toByteArray();
  }

  Images.UploadIntent upload(ActorContext owner, String purpose, byte[] bytes) throws Exception {
    return upload(owner, purpose, "image/png", bytes);
  }

  Images.UploadIntent upload(ActorContext owner, String purpose, String mime, byte[] bytes)
      throws Exception {
    var intent =
        images.upload(
            owner,
            new Images.UploadRequest(
                purpose, mime, bytes.length, StorageContractIT.checksum(bytes)),
            null);
    var r =
        HttpRequest.newBuilder(URI.create(intent.uploadUrl()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    intent
        .headers()
        .forEach(
            (k, values) -> {
              if (!k.equalsIgnoreCase("content-length")) values.forEach(v -> r.header(k, v));
            });
    var uploaded = StorageContractIT.send(r);
    assertThat(uploaded.statusCode()).isEqualTo(200);
    images.finalizeUpload(
        owner, intent.id(), uploaded.headers().firstValue("x-amz-version-id").orElseThrow());
    return intent;
  }

  UUID ready(ActorContext owner, String purpose) throws Exception {
    var i = upload(owner, purpose, png(16, 8));
    for (int n = 0; n < 30 && !images.status(owner, i.id()).state().equals("READY"); n++)
      assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(images.status(owner, i.id()).state()).isEqualTo("READY");
    return i.id();
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  void drainCleanup(ActorContext owner, UUID id) {
    for (int n = 0; n < 200 && !images.status(owner, id).state().equals("DELETED"); n++)
      work.consumeExternalJob(UUID.randomUUID(), List.of(cleanup));
    assertThat(images.status(owner, id).state()).isEqualTo("DELETED");
  }

  void awaitDatabaseWait(Future<?> waiter) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(
                      count(
                          "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()"))
                  .isPositive();
              assertThat(waiter.isDone()).isFalse();
            });
  }

  void assertAll(ActorContext viewer, UUID id, boolean visible) {
    if (visible) assertThat(reads.read(viewer, id).id()).isEqualTo(id);
    else denied(() -> reads.read(viewer, id), 404);
    assertThat(reads.hydrate(viewer, List.of(id)).isEmpty()).isEqualTo(!visible);
    assertThat(
            reads.posts(viewer, null, null, 100, null).items().stream()
                .anyMatch(p -> p.id().equals(id)))
        .isEqualTo(visible);
  }

  @Test
  void allPersonalAudiencesAndCurrentBlocksApplyToSingleHydrateQueryHistoryAndMedia()
      throws Exception {
    var author = user();
    var friend = user();
    var outsider = user();
    graph.follow(friend, author.userId(), true);
    var invite = graph.request(author, friend.userId());
    graph.respond(friend, invite.id(), "accept");
    var image = ready(author, "POST");
    var custom =
        posts.publish(
            author,
            request("CUSTOM", null, List.of(friend.userId()), List.of(image)),
            "custom-create");
    assertAll(friend, custom.id(), true);
    assertAll(outsider, custom.id(), false);
    assertAll(ActorContext.anonymous(), custom.id(), false);
    denied(() -> reads.revisions(friend, custom.id(), 20, Integer.MAX_VALUE), 404);
    assertThat(reads.mediaAccess(friend, custom.id(), image, null)).hasSize(2);
    for (String audience : List.of("PUBLIC", "FOLLOWERS", "FRIENDS", "ONLY_ME")) {
      var p = publish(author, audience, List.of());
      assertAll(author, p.id(), true);
      assertAll(friend, p.id(), !audience.equals("ONLY_ME"));
      assertAll(outsider, p.id(), audience.equals("PUBLIC"));
      assertAll(ActorContext.anonymous(), p.id(), audience.equals("PUBLIC"));
    }
    var narrowed =
        posts.audience(
            author,
            custom.id(),
            custom.version(),
            new ContentCommands.Audience("ONLY_ME", List.of()));
    assertAll(friend, custom.id(), false);
    denied(() -> reads.mediaAccess(friend, custom.id(), image, null), 404);
    assertThat(reads.revisions(author, custom.id(), 20, Integer.MAX_VALUE).items()).hasSize(2);
    graph.block(friend, author.userId(), true);
    assertAll(friend, narrowed.id(), false);
    denied(() -> reads.mediaAccess(friend, custom.id(), image, 1), 404);
    assertThat(images.status(author, image).state()).isEqualTo("READY");
    mvc.perform(
            get("/api/v1/media/" + image).header("Authorization", "Bearer " + tokens.issue(author)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadUrl").doesNotExist())
        .andExpect(jsonPath("$.url").doesNotExist());
  }

  @Test
  void communityDepartureBlindDeleteArchiveAndScopedRemoveRestoreNeverRewriteBody()
      throws Exception {
    var owner = user();
    var author = user();
    var outsider = user();
    var privateScope =
        communities.create(
            owner,
            "scope-" + UUID.randomUUID().toString().substring(0, 8),
            "Synthetic",
            "",
            "PRIVATE",
            "OPEN",
            null);
    var member = memberships.join(author, privateScope.id());
    var image = ready(author, "POST");
    var p =
        posts.publish(
            author, request("COMMUNITY", privateScope.id(), List.of(), List.of(image)), null);
    assertAll(owner, p.id(), true);
    assertAll(outsider, p.id(), false);
    var removed =
        moderation.apply(
            owner, privateScope.id(), p.id(), p.version(), "REMOVE_CONTENT", "Synthetic reason");
    assertAll(author, p.id(), false);
    denied(() -> reads.mediaAccess(author, p.id(), image, null), 404);
    var restored =
        moderation.apply(
            owner,
            privateScope.id(),
            p.id(),
            removed.version(),
            "RESTORE_CONTENT",
            "Synthetic reason");
    assertThat(reads.read(author, p.id()).body()).isEqualTo(p.body());
    denied(
        () ->
            moderation.apply(
                outsider,
                privateScope.id(),
                p.id(),
                restored.version(),
                "REMOVE_CONTENT",
                "Synthetic"),
        403);
    memberships.leave(author, privateScope.id(), member.version());
    assertAll(author, p.id(), false);
    denied(
        () ->
            posts.edit(
                author,
                p.id(),
                restored.version(),
                new ContentCommands.Edit("not allowed", null, List.of(), List.of())),
        403);
    posts.delete(author, p.id(), restored.version());
    denied(
        () ->
            moderation.apply(
                owner,
                privateScope.id(),
                p.id(),
                restored.version() + 1,
                "RESTORE_CONTENT",
                "Synthetic"),
        409);
    assertThat(
            count(
                "SELECT count(*) FROM moderation.action WHERE target_type='POST' AND target_id=?",
                p.id()))
        .isEqualTo(2);
    var pub =
        communities.create(
            owner,
            "pub-" + UUID.randomUUID().toString().substring(0, 8),
            "Synthetic",
            "",
            "PUBLIC",
            "OPEN",
            null);
    memberships.join(author, pub.id());
    var pp = posts.publish(author, request("COMMUNITY", pub.id(), List.of(), List.of()), null);
    assertAll(ActorContext.anonymous(), pp.id(), true);
    communities.archive(
        owner,
        pub.id(),
        ((com.flyvo.socialcore.community.application.CommunityLifecycle) communities)
            .read(owner, pub.id())
            .version());
    assertAll(ActorContext.anonymous(), pp.id(), true);
    denied(
        () -> posts.publish(author, request("COMMUNITY", pub.id(), List.of(), List.of()), null),
        409);
  }

  @Test
  void httpPreconditionsIdempotencyUnknownFieldsCsrfAndRevisionPersistence() throws Exception {
    var a = user();
    String bearer = "Bearer " + tokens.issue(a);
    String body = json.writeValueAsString(request("PUBLIC", null, List.of(), List.of()));
    var response =
        mvc.perform(
                post("/api/v1/posts")
                    .with(csrf().asHeader())
                    .header("Origin", "https://social-core.invalid")
                    .header("Authorization", bearer)
                    .header("Idempotency-Key", "create-1")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().exists("ETag"))
            .andReturn();
    UUID id =
        UUID.fromString(
            json.readTree(response.getResponse().getContentAsString()).path("id").asText());
    mvc.perform(
            post("/api/v1/posts")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .header("Idempotency-Key", "create-1")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()));
    var p = reads.read(a, id);
    var edited =
        posts.edit(
            a,
            id,
            p.version(),
            new ContentCommands.Edit(
                "edited @Synthetic #Ｈello",
                null,
                List.of(new ContentCommands.Mention(a.userId(), 7, 17)),
                List.of()));
    denied(
        () ->
            posts.edit(
                a, id, p.version(), new ContentCommands.Edit("stale", null, List.of(), List.of())),
        412);
    mvc.perform(
            patch("/api/v1/posts/" + id)
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isPreconditionRequired());
    mvc.perform(
            post("/api/v1/posts")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/posts")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    body.substring(0, body.length() - 1) + ",\"authorId\":\"" + a.userId() + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(edited.revision()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM content.post_revision WHERE post_id=?", id))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM content.post_mention WHERE post_id=?", id)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM content.post_hashtag WHERE post_id=?", id)).isEqualTo(1);
    denied(
        () ->
            posts.publish(
                a,
                new ContentCommands.Publish(
                    "",
                    null,
                    null,
                    new ContentCommands.Audience("PUBLIC", List.of()),
                    List.of(),
                    List.of()),
                null),
        400);
    denied(() -> posts.publish(a, request("ONLY_ME", null, List.of(), List.of()), "create-1"), 409);
  }

  @Test
  void sqlObjectParityUsesActualPublishedViewsAcrossRelationshipAndMembershipStates() {
    var author = user();
    var viewer = user();
    var owner = user();
    var ids = new ArrayList<UUID>();
    for (String audience : List.of("PUBLIC", "FOLLOWERS", "FRIENDS", "ONLY_ME", "CUSTOM"))
      ids.add(
          publish(
                  author,
                  audience,
                  audience.equals("CUSTOM") ? List.of(viewer.userId()) : List.of())
              .id());
    var scopes = new ArrayList<UUID>();
    for (String visibility : List.of("PUBLIC", "PRIVATE")) {
      var c =
          communities.create(
              owner,
              "parity-" + UUID.randomUUID().toString().substring(0, 8),
              "Synthetic",
              "",
              visibility,
              "OPEN",
              null);
      memberships.join(author, c.id());
      scopes.add(c.id());
      ids.add(posts.publish(author, request("COMMUNITY", c.id(), List.of(), List.of()), null).id());
    }
    for (int state = 0; state < 8; state++) {
      graph.block(viewer, author.userId(), false);
      graph.block(author, viewer.userId(), false);
      graph.unfriend(viewer, author.userId());
      if ((state & 1) != 0) graph.follow(viewer, author.userId(), true);
      else graph.follow(viewer, author.userId(), false);
      if ((state & 2) != 0) {
        var fr = graph.request(author, viewer.userId());
        graph.respond(viewer, fr.id(), "accept");
      } else graph.unfriend(viewer, author.userId());
      if ((state & 4) != 0) graph.block(author, viewer.userId(), true);
      else graph.block(author, viewer.userId(), false);
      for (String memberState : List.of("NONE", "ACTIVE", "SUSPENDED", "BANNED", "LEFT")) {
        for (var scope : scopes) {
          jdbc.update(
              "DELETE FROM community.membership WHERE community_id=? AND user_id=?",
              scope,
              viewer.userId());
          if (!memberState.equals("NONE"))
            jdbc.update(
                "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at,suspended_until,left_at) VALUES (?,?,'MEMBER',?,?,?,?,?)",
                scope,
                viewer.userId(),
                memberState.equals("SUSPENDED") ? "ACTIVE" : memberState,
                clock.instant().atOffset(ZoneOffset.UTC),
                clock.instant().atOffset(ZoneOffset.UTC),
                memberState.equals("SUSPENDED")
                    ? clock.instant().plusSeconds(60).atOffset(ZoneOffset.UTC)
                    : null,
                List.of("LEFT", "REMOVED", "BANNED").contains(memberState)
                    ? clock.instant().atOffset(ZoneOffset.UTC)
                    : null);
        }
        for (ActorContext v : List.of(viewer, author, owner, ActorContext.anonymous())) {
          var source = repository.load(ids);
          var fs = repository.facts(v.userId(), ids, clock.instant());
          var expected =
              source.stream()
                  .filter(
                      p ->
                          ContentAccessPolicy.canRead(
                              v.userId(),
                              new ContentAccessPolicy.PostFacts(
                                  p.author(),
                                  p.community(),
                                  p.audience().type().name(),
                                  p.status()),
                              fs.get(p.id()),
                              clock.instant()))
                  .map(com.flyvo.socialcore.content.domain.post.Post::id)
                  .toList();
          var candidates =
              repository
                  .candidates(v.userId(), null, null, 100, clock.instant(), null, clock.instant())
                  .stream()
                  .map(PostRepository.Key::id)
                  .filter(ids::contains)
                  .toList();
          assertThat(candidates).containsExactlyInAnyOrderElementsOf(expected);
          assertThat(reads.hydrate(v, ids).stream().map(ContentQueries.PostView::id))
              .containsExactlyInAnyOrderElementsOf(expected);
        }
      }
    }
  }

  @Test
  void finalizedVersionOverwriteDoesNotAlterDecodedDeliveryAndDuplicateFinalizeIsBounded()
      throws Exception {
    var owner = user();
    byte[] original = png(21, 11);
    var upload = upload(owner, "POST", original);
    var a = assets.asset(upload.id(), false);
    String version = a.uploadedVersion();
    assertThat(images.finalizeUpload(owner, a.id(), version).state()).isEqualTo("UPLOADED");
    String originalHash = StorageContractIT.checksum(original);
    var later =
        StorageContractIT.api.putObject(
            r ->
                r.bucket(StorageContractIT.QUARANTINE)
                    .key(a.key())
                    .contentType("image/png")
                    .checksumSHA256(originalHash),
            software.amazon.awssdk.core.sync.RequestBody.fromBytes(original));
    denied(() -> images.finalizeUpload(owner, a.id(), later.versionId()), 409);
    var different = png(3, 3);
    StorageContractIT.api.putObject(
        r -> r.bucket(StorageContractIT.QUARANTINE).key(a.key()).contentType("image/png"),
        software.amazon.awssdk.core.sync.RequestBody.fromBytes(different));
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(images.status(owner, a.id()).state()).isEqualTo("READY");
    var p = posts.publish(owner, request("PUBLIC", null, List.of(), List.of(a.id())), null);
    var urls = reads.mediaAccess(ActorContext.anonymous(), p.id(), a.id(), null);
    for (var v : urls) {
      var response = StorageContractIT.send(HttpRequest.newBuilder(URI.create(v.url())).GET());
      assertThat(response.statusCode()).isEqualTo(200);
      var decoded = ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
      assertThat(decoded.getWidth()).isEqualTo(21);
      assertThat(decoded.getHeight()).isEqualTo(11);
    }
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE kind='MEDIA_PROCESS' AND dedup_key=?",
                a.id().toString()))
        .isEqualTo(1);
  }

  @Test
  void malformedImagesRemainUnservableAndReservationReleasesOnlyAfterConfirmedCleanup()
      throws Exception {
    var owner = user();
    var u =
        upload(
            owner, "POST", "not an image at all".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(images.status(owner, u.id()).state()).isEqualTo("REJECTED");
    denied(
        () -> posts.publish(owner, request("PUBLIC", null, List.of(), List.of(u.id())), null), 409);
    assertThat(count("SELECT reserved_bytes FROM media.quota WHERE owner_id=?", owner.userId()))
        .isPositive();
    drainCleanup(owner, u.id());
    assertThat(count("SELECT reserved_bytes FROM media.quota WHERE owner_id=?", owner.userId()))
        .isZero();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r ->
                        r.bucket(StorageContractIT.QUARANTINE)
                            .prefix(assets.asset(u.id(), false).key()))
                .versions())
        .isEmpty();
  }

  @Test
  void tenImageBindingRollsBackAsOneUnitAndConcurrentReuseHasExactlyOneWinner() throws Exception {
    var owner = user();
    var other = user();
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 10; i++) ids.add(ready(owner, "POST"));
    var foreign = ready(other, "POST");
    var bad = new ArrayList<>(ids);
    bad.set(9, foreign);
    long before = count("SELECT count(*) FROM content.post WHERE author_id=?", owner.userId());
    denied(() -> posts.publish(owner, request("PUBLIC", null, List.of(), bad), null), 409);
    assertThat(
            count(
                "SELECT count(*) FROM media.binding WHERE asset_id IN ("
                    + String.join(",", Collections.nCopies(10, "?"))
                    + ")",
                ids.toArray()))
        .isZero();
    assertThat(count("SELECT count(*) FROM content.post WHERE author_id=?", owner.userId()))
        .isEqualTo(before);
    var barrier = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var actions = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        actions.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    posts.publish(owner, request("PUBLIC", null, List.of(), ids), null);
                    return true;
                  } catch (Failure f) {
                    assertThat(f.code()).isEqualTo("MEDIA_NOT_BINDABLE");
                    return false;
                  }
                }));
      assertThat(
              actions.stream()
                  .map(
                      f -> {
                        try {
                          return f.get(20, TimeUnit.SECONDS);
                        } catch (Exception e) {
                          throw new AssertionError(e);
                        }
                      })
                  .filter(Boolean::booleanValue)
                  .count())
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            count(
                "SELECT count(*) FROM media.binding WHERE asset_id IN ("
                    + String.join(",", Collections.nCopies(10, "?"))
                    + ")",
                ids.toArray()))
        .isEqualTo(10);
  }

  @Test
  void avatarReplacementReleasesOldBindingAndCurrentProfilePrivacyControlsCapabilities()
      throws Exception {
    var owner = user();
    var viewer = user();
    var first = ready(owner, "AVATAR");
    var second = ready(owner, "AVATAR");
    var p = profiles.avatar(owner, profiles.visible(owner, owner.userId()).version(), first);
    assertThat(profiles.avatarAccess(viewer, owner.userId())).hasSize(2);
    var changed = profiles.avatar(owner, p.version(), second);
    assertThat(assets.binding(first).released()).isNotNull();
    assertThat(profiles.avatarAccess(owner, owner.userId()))
        .allSatisfy(v -> assertThat(v.url()).contains(second.toString()));
    denied(() -> profiles.avatar(owner, changed.version(), first), 409);
    profiles.change(
        owner,
        changed.version(),
        changed.handle(),
        changed.displayName(),
        changed.bio(),
        "PRIVATE");
    denied(() -> profiles.avatarAccess(viewer, owner.userId()), 404);
    drainCleanup(owner, first);
  }

  @Test
  void bodyEditsRetainReleasedHistoryMediaButCurrentAudienceAndRetentionStillGateAccess()
      throws Exception {
    var author = user();
    var friend = user();
    var media = ready(author, "POST");
    var p = posts.publish(author, request("PUBLIC", null, List.of(), List.of(media)), null);
    var edited =
        posts.edit(
            author,
            p.id(),
            p.version(),
            new ContentCommands.Edit("no image", null, List.of(), List.of()));
    denied(() -> reads.mediaAccess(friend, p.id(), media, null), 404);
    denied(() -> reads.mediaAccess(friend, p.id(), media, 1), 404);
    assertThat(reads.mediaAccess(author, p.id(), media, 1)).hasSize(2);
    posts.audience(
        author, p.id(), edited.version(), new ContentCommands.Audience("ONLY_ME", List.of()));
    clock.now = clock.now.plusSeconds(30L * 86400 + 1);
    denied(() -> reads.mediaAccess(author, p.id(), media, 1), 404);
  }

  @Test
  void quotaConcurrentReservationUsesTheOwnerRowAndCannotOverspend() throws Exception {
    var a = user();
    tx.executeWithoutResult(s -> assets.quota(a.userId(), clock.instant()));
    jdbc.update(
        "UPDATE media.quota SET reserved_bytes=? WHERE owner_id=?",
        100L * 1024 * 1024 - 1024,
        a.userId());
    var barrier = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    byte[] data = new byte[1024];
    String hash = StorageContractIT.checksum(data);
    try {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            executor.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    images.upload(
                        a, new Images.UploadRequest("POST", "image/png", 1024, hash), null);
                    return true;
                  } catch (Failure failure) {
                    assertThat(failure.code()).isEqualTo("MEDIA_QUOTA_EXCEEDED");
                    return false;
                  }
                }));
      int success = 0;
      for (var f : futures) if (f.get(20, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("SELECT reserved_bytes FROM media.quota WHERE owner_id=?", a.userId()))
        .isEqualTo(100L * 1024 * 1024);
  }

  @Test
  void staleLeasePartialOutputAndCrashBeforeReadyCannotCommitOrExposeAnOldGeneration()
      throws Exception {
    var a = user();
    var intent = upload(a, "POST", png(8, 8));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    doAnswer(
            invocation -> {
              if (Thread.currentThread() == thread.get()) {
                entered.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
              }
              return invocation.callRealMethod();
            })
        .when(objects)
        .writeVariant(anyString(), anyString(), any());
    var pool = Executors.newSingleThreadExecutor();
    try {
      var stale =
          pool.submit(
              () -> {
                thread.set(Thread.currentThread());
                return work.consumeExternalJob(UUID.randomUUID(), List.of(processing));
              });
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      clock.now = clock.now.plusSeconds(61);
      assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
      release.countDown();
      assertThat(stale.get(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertThat(assets.asset(intent.id(), false).generation()).isEqualTo(2);
    assertThat(images.status(a, intent.id()).state()).isEqualTo("READY");
    assertThat(assets.variants(intent.id(), 2)).hasSize(2);
    clock.now = clock.now.plusSeconds(61);
    for (int n = 0;
        n < 100
            && !StorageContractIT.admin
                .listObjectVersions(
                    r ->
                        r.bucket(StorageContractIT.DELIVERY)
                            .prefix("variants/" + intent.id() + "/1/"))
                .versions()
                .isEmpty();
        n++) assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(generations))).isTrue();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r ->
                        r.bucket(StorageContractIT.DELIVERY)
                            .prefix("variants/" + intent.id() + "/1/"))
                .versions())
        .isEmpty();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r ->
                        r.bucket(StorageContractIT.DELIVERY)
                            .prefix("variants/" + intent.id() + "/2/"))
                .versions())
        .hasSize(2);
  }

  @Test
  void partialStorageOutputRetriesInAFreshGenerationAndCleanupFailureKeepsQuotaCharged()
      throws Exception {
    var owner = user();
    var u = upload(owner, "POST", png(12, 9));
    var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(
            call -> {
              if (call.getArgument(0, String.class)
                      .equals("variants/" + u.id() + "/1/thumbnail.png")
                  && failOnce.getAndSet(false)) throw new Failure(503, "TEST_STORAGE_OUTAGE");
              return call.callRealMethod();
            })
        .when(objects)
        .writeVariant(anyString(), anyString(), any());
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isFalse();
    assertThat(images.status(owner, u.id()).state()).isEqualTo("PROCESSING");
    assertThat(assets.variants(u.id(), 1)).isEmpty();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + u.id() + "/1/"))
                .versions())
        .hasSize(1);
    denied(
        () -> posts.publish(owner, request("PUBLIC", null, List.of(), List.of(u.id())), null), 409);
    clock.now = clock.now.plusSeconds(3601);
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(assets.asset(u.id(), false).generation()).isEqualTo(2);
    assertThat(images.status(owner, u.id()).state()).isEqualTo("READY");
    long charged = count("SELECT retained_bytes FROM media.quota WHERE owner_id=?", owner.userId());
    images.delete(owner, u.id());
    failOnce.set(true);
    doAnswer(
            call -> {
              if (call.getArgument(0, String.class).equals(assets.asset(u.id(), false).key())
                  && failOnce.getAndSet(false)) throw new Failure(503, "TEST_DELETE_OUTAGE");
              return call.callRealMethod();
            })
        .when(objects)
        .purge(anyString(), anyString());
    for (int n = 0; n < 200 && failOnce.get(); n++)
      work.consumeExternalJob(UUID.randomUUID(), List.of(cleanup));
    assertThat(failOnce).isFalse();
    assertThat(images.status(owner, u.id()).state()).isEqualTo("DELETING");
    assertThat(count("SELECT retained_bytes FROM media.quota WHERE owner_id=?", owner.userId()))
        .isEqualTo(charged);
    clock.now = clock.now.plusSeconds(3601);
    drainCleanup(owner, u.id());
    assertThat(count("SELECT retained_bytes FROM media.quota WHERE owner_id=?", owner.userId()))
        .isZero();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + u.id() + "/"))
                .versions())
        .isEmpty();
  }

  @Test
  void expiredFinalAttemptIsRecoveredToFailedAndAuditedOperatorReplayPreservesGeneration()
      throws Exception {
    var owner = user();
    var u = upload(owner, "POST", png(9, 7));
    tx.executeWithoutResult(s -> assets.processing(u.id(), 10, clock.instant()));
    jdbc.update(
        "UPDATE platform.job SET status='RUNNING',attempts=10,lease_generation=10,lease_owner=?,lease_until=? WHERE kind='MEDIA_PROCESS' AND dedup_key=?",
        UUID.randomUUID(),
        clock.instant().minusSeconds(1).atOffset(ZoneOffset.UTC),
        u.id().toString());
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(images.status(owner, u.id()).state()).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM platform.job WHERE kind='MEDIA_PROCESS' AND dedup_key=?",
                String.class,
                u.id().toString()))
        .isEqualTo("DEAD");
    // Restricted DB operator procedure, not an HTTP API: same durable job and monotonic generation.
    tx.executeWithoutResult(
        s -> {
          jdbc.queryForList(
              "SELECT id FROM platform.job WHERE kind='MEDIA_PROCESS' AND dedup_key=? FOR UPDATE",
              u.id().toString());
          assets.quota(owner.userId(), clock.instant());
          assets.asset(u.id(), true);
          assets.state(u.id(), "UPLOADED", null, clock.instant());
          jdbc.update(
              "UPDATE platform.job SET status='RETRY',attempts=0,available_at=?,lease_owner=NULL,lease_until=NULL WHERE kind='MEDIA_PROCESS' AND dedup_key=? AND status='DEAD'",
              clock.instant().atOffset(ZoneOffset.UTC),
              u.id().toString());
          work.audit(
              owner.userId(),
              "MEDIA_OPERATOR_REPLAY",
              "media",
              u.id(),
              "SUCCESS",
              "TEST_OPERATOR_RECOVERY");
        });
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(images.status(owner, u.id()).state()).isEqualTo("READY");
    assertThat(assets.asset(u.id(), false).generation()).isEqualTo(11);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE action='MEDIA_OPERATOR_REPLAY' AND target_id=?",
                u.id()))
        .isEqualTo(1);
  }

  @Test
  void attachWinsBeforeExpiryAndCleanupWinsAfterExpiryUnderTheSameOwnerGuard() throws Exception {
    for (boolean attachFirst : List.of(true, false)) {
      var owner = user();
      var id = ready(owner, "POST");
      var held = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(2);
      try {
        if (attachFirst) {
          doAnswer(
                  call -> {
                    Object result = call.callRealMethod();
                    if (call.getArgument(0).equals(id)) {
                      held.countDown();
                      assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    return result;
                  })
              .when(assets)
              .bind(any(), anyString(), any(), any());
          var attach =
              pool.submit(
                  () ->
                      posts.publish(owner, request("PUBLIC", null, List.of(), List.of(id)), null));
          assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
          clock.now = clock.now.plusSeconds(86401);
          var remove =
              pool.submit(
                  () ->
                      tx.execute(
                          s ->
                              cleanup.prepare(
                                  id.toString(),
                                  new ExternalJobHandler.Lease(
                                      1, 1, clock.instant().plusSeconds(60)))));
          awaitDatabaseWait(remove);
          release.countDown();
          var p = attach.get(10, TimeUnit.SECONDS);
          assertThat(remove.get(10, TimeUnit.SECONDS)).isEmpty();
          assertThat(reads.mediaAccess(owner, p.id(), id, null)).hasSize(2);
        } else {
          clock.now = clock.now.plusSeconds(86401);
          var remove =
              pool.submit(
                  () ->
                      tx.execute(
                          s -> {
                            String prepared =
                                cleanup.prepare(
                                    id.toString(),
                                    new ExternalJobHandler.Lease(
                                        1, 1, clock.instant().plusSeconds(60)));
                            held.countDown();
                            try {
                              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                              throw new AssertionError(e);
                            }
                            return prepared;
                          }));
          assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
          var attach =
              pool.submit(
                  () ->
                      denied(
                          () ->
                              posts.publish(
                                  owner, request("PUBLIC", null, List.of(), List.of(id)), null),
                          409));
          awaitDatabaseWait(attach);
          release.countDown();
          assertThat(remove.get(10, TimeUnit.SECONDS)).isNotEmpty();
          attach.get(10, TimeUnit.SECONDS);
          assertThat(assets.binding(id)).isNull();
          assertThat(images.status(owner, id).state()).isEqualTo("DELETING");
          tx.executeWithoutResult(
              s -> work.job("MEDIA_CLEANUP", id + ":race", id.toString(), clock.instant()));
          drainCleanup(owner, id);
        }
      } finally {
        release.countDown();
        pool.shutdownNow();
        reset(assets);
      }
    }
  }

  @Test
  void publicationSerializesBothOrdersAgainstMembershipDepartureAndBlock() throws Exception {
    for (String conflict : List.of("LEAVE", "BLOCK"))
      for (boolean mutationFirst : List.of(true, false)) {
        var owner = user();
        var author = user();
        var c =
            communities.create(
                owner, "scope-" + UUID.randomUUID(), "Scope", "", "PRIVATE", "OPEN", null);
        memberships.join(author, c.id());
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Runnable mutation =
            () -> {
              if (conflict.equals("LEAVE"))
                memberships.leave(
                    author,
                    c.id(),
                    membershipQueries.facts(author.userId(), List.of(c.id())).getFirst().version());
              else graph.block(owner, author.userId(), true);
            };
        ContentCommands.Publish req =
            conflict.equals("LEAVE")
                ? request("COMMUNITY", c.id(), List.of(), List.of())
                : request("CUSTOM", null, List.of(owner.userId()), List.of());
        try {
          Future<?> first;
          if (mutationFirst)
            first =
                pool.submit(
                    () ->
                        tx.executeWithoutResult(
                            s -> {
                              mutation.run();
                              held.countDown();
                              try {
                                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                              } catch (InterruptedException e) {
                                throw new AssertionError(e);
                              }
                            }));
          else {
            doAnswer(
                    call -> {
                      Object result = call.callRealMethod();
                      held.countDown();
                      assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                      return result;
                    })
                .when(repository)
                .create(any());
            first = pool.submit(() -> posts.publish(author, req, null));
          }
          assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
          var second =
              pool.submit(
                  () -> {
                    if (mutationFirst)
                      denied(
                          () -> posts.publish(author, req, null),
                          conflict.equals("LEAVE") ? 403 : 404);
                    else mutation.run();
                  });
          awaitDatabaseWait(second);
          release.countDown();
          first.get(10, TimeUnit.SECONDS);
          second.get(10, TimeUnit.SECONDS);
          denied(() -> posts.publish(author, req, null), conflict.equals("LEAVE") ? 403 : 404);
        } finally {
          release.countDown();
          pool.shutdownNow();
          reset(repository);
        }
      }
  }

  @Test
  void expirationRateQuotaReconciliationAndContextMismatchRemainFailClosed() throws Exception {
    var a = user();
    var b = user();
    byte[] bytes = png(3, 3);
    var req =
        new Images.UploadRequest(
            "POST", "image/png", bytes.length, StorageContractIT.checksum(bytes));
    denied(
        () ->
            images.upload(
                a,
                new Images.UploadRequest(
                    "POST", "image/png", bytes.length, req.sha256().substring(0, 43)),
                null),
        400);
    assertThat(count("SELECT count(*) FROM media.asset WHERE owner_id=?", a.userId())).isZero();
    var u = images.upload(a, req, "replay");
    assertThat(images.upload(a, req, "replay").id()).isEqualTo(u.id());
    denied(() -> images.status(b, u.id()), 404);
    for (int n = 0; n < 9; n++) images.upload(a, req, null);
    denied(() -> images.upload(a, req, null), 429);
    clock.now = clock.now.plusSeconds(901);
    denied(() -> images.upload(a, req, "replay"), 409);
    denied(() -> images.finalizeUpload(a, u.id(), "absent-version"), 409);
    drainCleanup(a, u.id());
    var image = ready(b, "POST");
    var p = posts.publish(b, request("PUBLIC", null, List.of(), List.of(image)), null);
    jdbc.update(
        "UPDATE media.quota SET retained_bytes=retained_bytes+99 WHERE owner_id=?", b.userId());
    tx.executeWithoutResult(s -> quotaRepair.apply(""));
    for (int n = 0;
        n < 100
            && count("SELECT retained_bytes FROM media.quota WHERE owner_id=?", b.userId())
                != assets.asset(image, false).retainedBytes();
        n++) work.consumeJob(UUID.randomUUID(), List.of(quotaRepair));
    assertThat(count("SELECT retained_bytes FROM media.quota WHERE owner_id=?", b.userId()))
        .isEqualTo(assets.asset(image, false).retainedBytes());
    jdbc.update("UPDATE media.binding SET context_id=? WHERE asset_id=?", UUID.randomUUID(), image);
    denied(() -> reads.mediaAccess(b, p.id(), image, null), 404);
    tx.executeWithoutResult(s -> postRepair.apply(p.id().toString()));
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE action='MEDIA_BINDING_MISMATCH' AND target_id=?",
                p.id()))
        .isEqualTo(1);
  }

  @Test
  void boundedCursorIsActorFilterAndTimeBoundAndRechecksCurrentSources() {
    var a = user();
    var b = user();
    var c = user();
    var ids = new HashSet<UUID>();
    for (int n = 0; n < 5; n++) ids.add(publish(a, "PUBLIC", List.of()).id());
    var first = reads.posts(b, a.userId(), null, 2, null);
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextCursor()).isNotBlank();
    denied(() -> reads.posts(c, a.userId(), null, 2, first.nextCursor()), 400);
    denied(() -> reads.posts(b, c.userId(), null, 2, first.nextCursor()), 400);
    denied(() -> reads.posts(b, a.userId(), null, 2, first.nextCursor() + "x"), 400);
    var found = new HashSet<UUID>();
    var page = first;
    do {
      page.items().forEach(p -> assertThat(found.add(p.id())).isTrue());
      page =
          page.nextCursor() == null ? null : reads.posts(b, a.userId(), null, 2, page.nextCursor());
    } while (page != null);
    assertThat(found).isEqualTo(ids);
    graph.block(a, b.userId(), true);
    assertThat(reads.posts(b, a.userId(), null, 2, first.nextCursor()).items()).isEmpty();
    clock.now = clock.now.plusSeconds(901);
    denied(() -> reads.posts(b, a.userId(), null, 2, first.nextCursor()), 400);
    graph.block(a, b.userId(), false);
    jdbc.update(
        "UPDATE account.user_account SET status='SUSPENDED',version=version+1 WHERE id=?",
        a.userId());
    for (var id : ids) assertAll(b, id, false);
  }

  @Test
  void immutableRevisionGuardsAndRetentionRedactDeletedContentWithoutGrantingHistory() {
    var a = user();
    var p = publish(a, "CUSTOM", List.of(user().userId()));
    var other = publish(a, "PUBLIC", List.of());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE content.post_recipient SET post_id=? WHERE post_id=?",
                    other.id(),
                    p.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE content.post_revision SET body='changed' WHERE post_id=?", p.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM content.post_recipient WHERE post_id=?", p.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    posts.delete(a, p.id(), p.version());
    denied(() -> reads.revisions(a, p.id(), 20, Integer.MAX_VALUE), 404);
    clock.now = clock.now.plusSeconds(30L * 86400 + 1);
    tx.executeWithoutResult(s -> retention.apply(p.id().toString()));
    assertThat(count("SELECT count(*) FROM content.post_revision WHERE post_id=?", p.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject("SELECT body FROM content.post WHERE id=?", String.class, p.id()))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT audience FROM content.post WHERE id=?", String.class, p.id()))
        .isEqualTo("ONLY_ME");
    denied(() -> reads.read(a, p.id()), 404);
  }

  @Test
  void candidateQueryUsesActualAuthorIndexOnSkewedDataAndHydrationRemainsBatched() {
    var a = user();
    var b = user();
    tx.executeWithoutResult(
        s ->
            jdbc.update(
                """
        INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at)
        SELECT md5(?||g::text)::uuid,CASE WHEN g%10=0 THEN ?::uuid ELSE ?::uuid END,'Synthetic plan row',
        'PUBLIC','PUBLISHED',1,?::timestamptz-g*interval '1 second',?::timestamptz,?::timestamptz
        FROM generate_series(1,12000) g
        """,
                UUID.randomUUID().toString(),
                a.userId(),
                b.userId(),
                clock.instant().atOffset(ZoneOffset.UTC),
                clock.instant().atOffset(ZoneOffset.UTC),
                clock.instant().atOffset(ZoneOffset.UTC)));
    jdbc.execute("ANALYZE content.post");
    clearInvocations(jdbc);
    var keys =
        repository.candidates(null, a.userId(), null, 100, clock.instant(), null, clock.instant());
    assertThat(keys).hasSize(100);
    var invocation =
        mockingDetails(jdbc).getInvocations().stream()
            .filter(
                i ->
                    i.getArguments().length > 0
                        && i.getArgument(0) instanceof String sql
                        && sql.startsWith("SELECT id,published_at FROM ("))
            .findFirst()
            .orElseThrow();
    String sql = invocation.getArgument(0);
    Object[] args = (Object[]) invocation.getRawArguments()[2];
    String plan =
        String.join(
            "\n", jdbc.queryForList("EXPLAIN (ANALYZE,BUFFERS) " + sql, String.class, args));
    assertThat(plan).contains("post_author").contains("Limit");
    System.out.println("Phase 4 candidate plan (12000 rows, 10% selected author):\n" + plan);
    clearInvocations(jdbc);
    var result = reads.hydrate(a, keys.stream().map(PostRepository.Key::id).toList());
    assertThat(result).hasSize(100);
    long queries =
        mockingDetails(jdbc).getInvocations().stream()
            .filter(
                i ->
                    i.getMethod().getName().equals("query")
                        && i.getMethod().isVarArgs()
                        && i.getArguments().length > 1
                        && (i.getArgument(1) instanceof org.springframework.jdbc.core.RowMapper
                            || i.getArgument(1)
                                instanceof org.springframework.jdbc.core.RowCallbackHandler))
            .count();
    assertThat(queries).isLessThanOrEqualTo(12);
    clearInvocations(jdbc);
    assertThat(reads.hydrate(a, List.of(keys.getFirst().id()))).hasSize(1);
    long oneRowQueries =
        mockingDetails(jdbc).getInvocations().stream()
            .filter(
                i ->
                    i.getMethod().getName().equals("query")
                        && i.getMethod().isVarArgs()
                        && i.getArguments().length > 1
                        && (i.getArgument(1) instanceof org.springframework.jdbc.core.RowMapper
                            || i.getArgument(1)
                                instanceof org.springframework.jdbc.core.RowCallbackHandler))
            .count();
    assertThat(queries).isEqualTo(oneRowQueries);
    clearInvocations(jdbc);
  }

  @Test
  void realWebpAndJpegPipelineAndPurposeIsolationProduceOnlyCleanPinnedPngVariants()
      throws Exception {
    var a = user();
    var b = user();
    var jpeg = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(13, 7, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
    for (var entry :
        Map.of(
                "image/webp",
                Base64.getDecoder()
                    .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"),
                "image/jpeg",
                jpeg.toByteArray())
            .entrySet()) {
      var u = upload(a, "POST", entry.getKey(), entry.getValue());
      assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
      var p = posts.publish(a, request("PUBLIC", null, List.of(), List.of(u.id())), null);
      for (var v : reads.mediaAccess(b, p.id(), u.id(), null)) {
        assertThat(v.mime()).isEqualTo("image/png");
        var r = StorageContractIT.send(HttpRequest.newBuilder(URI.create(v.url())).GET());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(r.body()))).isNotNull();
      }
      denied(() -> profiles.avatar(a, profiles.visible(a, a.userId()).version(), u.id()), 409);
    }
    var avatar = ready(a, "AVATAR");
    denied(() -> posts.publish(a, request("PUBLIC", null, List.of(), List.of(avatar)), null), 409);
    var priv = posts.publish(a, request("ONLY_ME", null, List.of(), List.of()), null);
    var outsider = tokens.issue(b);
    mvc.perform(get("/api/v1/posts/" + priv.id()).header("Authorization", "Bearer " + outsider))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/posts/" + priv.id() + "/revisions")
                .header("Authorization", "Bearer " + outsider))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/posts/" + priv.id() + "/media/" + avatar + "/access")
                .header("Authorization", "Bearer " + outsider)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader()))
        .andExpect(status().isNotFound());
  }

  @Test
  void uploadAndAvatarHttpContractsRequireCsrfCurrentActorAndVersionAndNeverExposeOriginals()
      throws Exception {
    var a = user();
    String token = tokens.issue(a);
    byte[] bytes = png(4, 4);
    String input =
        json.writeValueAsString(
            new Images.UploadRequest(
                "AVATAR", "image/png", bytes.length, StorageContractIT.checksum(bytes)));
    mvc.perform(
            post("/api/v1/media/uploads")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content(input))
        .andExpect(status().isForbidden());
    var result =
        mvc.perform(
                post("/api/v1/media/uploads")
                    .header("Authorization", "Bearer " + token)
                    .header("Origin", "https://social-core.invalid")
                    .with(csrf().asHeader())
                    .contentType("application/json")
                    .content(input))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andReturn();
    var intent =
        json.readValue(result.getResponse().getContentAsString(), Images.UploadIntent.class);
    var r =
        HttpRequest.newBuilder(URI.create(intent.uploadUrl()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    intent
        .headers()
        .forEach(
            (k, values) -> {
              if (!k.equalsIgnoreCase("content-length")) values.forEach(v -> r.header(k, v));
            });
    var uploaded = StorageContractIT.send(r);
    assertThat(uploaded.statusCode()).isEqualTo(200);
    var version = uploaded.headers().firstValue("x-amz-version-id").orElseThrow();
    mvc.perform(
            post("/api/v1/media/" + intent.id() + "/finalize")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("versionId", version))))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/v1/media/" + intent.id()));
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    mvc.perform(get("/api/v1/media/" + intent.id()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.url").doesNotExist())
        .andExpect(jsonPath("$.key").doesNotExist());
    mvc.perform(
            put("/api/v1/me/avatar")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("mediaId", intent.id()))))
        .andExpect(status().isPreconditionRequired());
    var profile = profiles.visible(a, a.userId());
    mvc.perform(
            put("/api/v1/me/avatar")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .header("If-Match", "\"" + profile.version() + "\"")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("mediaId", intent.id()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.avatarMediaId").value(intent.id().toString()));
    mvc.perform(
            post("/api/v1/users/" + a.userId() + "/avatar/access")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"));
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=now(),revoke_reason='TEST_REVOKED' WHERE id=?",
        a.sessionId());
    mvc.perform(get("/api/v1/media/" + intent.id()).header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void moderationActionFailureRollsBackPostAuditAndOutboxAndSuccessfulHistoryReadsAreAudited() {
    var owner = user();
    var a = user();
    var c =
        communities.create(
            owner, "moderation-" + UUID.randomUUID(), "Scope", "", "PRIVATE", "OPEN", null);
    memberships.join(a, c.id());
    var p = posts.publish(a, request("COMMUNITY", c.id(), List.of(), List.of()), null);
    String privateReason = "Synthetic restricted reason";
    doThrow(new IllegalStateException("TEST_ACTION_FAILURE"))
        .when(actionStore)
        .appendPost(
            any(),
            any(),
            any(),
            eq(p.id()),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any());
    assertThatThrownBy(
            () ->
                moderation.apply(
                    owner, c.id(), p.id(), p.version(), "REMOVE_CONTENT", privateReason))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(reads.read(a, p.id()).version()).isEqualTo(p.version());
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE action='REMOVE_CONTENT' AND target_id=?",
                p.id()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM moderation.action WHERE target_type='POST' AND target_id=?",
                p.id()))
        .isZero();
    reset(actionStore);
    reads.revisions(owner, p.id(), 20, Integer.MAX_VALUE);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE action='POST_HISTORY_READ' AND target_id=?",
                p.id()))
        .isEqualTo(1);
    var action =
        moderation.apply(owner, c.id(), p.id(), p.version(), "REMOVE_CONTENT", privateReason);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM platform.outbox_event WHERE aggregate_id=? AND event_type='ModerationActionApplied'",
                String.class,
                action.actionId()))
        .doesNotContain(privateReason)
        .doesNotContain(p.body());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.action SET reason='changed' WHERE id=?", action.actionId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    denied(() -> reads.revisions(owner, p.id(), 20, Integer.MAX_VALUE), 404);
  }

  @Test
  void postJsonBudgetAcceptsTenThousandEscapedCodePointsAndRejectsOversizedBytes()
      throws Exception {
    var a = user();
    String token = tokens.issue(a);
    String valid =
        "{\"body\":\"" + "\\ud83d\\ude00".repeat(10000) + "\",\"audience\":{\"type\":\"PUBLIC\"}}";
    mvc.perform(
            post("/api/v1/posts")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/posts")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(" ".repeat(262145)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void concurrentFinalizeSameVersionIsIdempotentAndDifferentVersionsHaveOneCasWinner()
      throws Exception {
    for (boolean same : List.of(true, false)) {
      var owner = user();
      byte[] bytes = png(6, 5);
      var intent =
          images.upload(
              owner,
              new Images.UploadRequest(
                  "POST", "image/png", bytes.length, StorageContractIT.checksum(bytes)),
              null);
      var versions = new ArrayList<String>();
      for (int n = 0; n < 2; n++) {
        var put =
            HttpRequest.newBuilder(URI.create(intent.uploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        intent
            .headers()
            .forEach(
                (k, values) -> {
                  if (!k.equalsIgnoreCase("content-length")) values.forEach(v -> put.header(k, v));
                });
        var response = StorageContractIT.send(put);
        assertThat(response.statusCode()).isEqualTo(200);
        versions.add(response.headers().firstValue("x-amz-version-id").orElseThrow());
      }
      var barrier = new CyclicBarrier(2);
      String key = assets.asset(intent.id(), false).key();
      doAnswer(
              call -> {
                Object info = call.callRealMethod();
                barrier.await(10, TimeUnit.SECONDS);
                return info;
              })
          .when(objects)
          .inspectUpload(eq(key), anyString());
      var pool = Executors.newFixedThreadPool(2);
      try {
        var results = new ArrayList<Future<Boolean>>();
        for (int n = 0; n < 2; n++) {
          String version = versions.get(same ? 0 : n);
          results.add(
              pool.submit(
                  () -> {
                    try {
                      images.finalizeUpload(owner, intent.id(), version);
                      return true;
                    } catch (Failure f) {
                      assertThat(f.code()).isEqualTo("UPLOAD_VERSION_CONFLICT");
                      return false;
                    }
                  }));
        }
        int wins = 0;
        for (var result : results) if (result.get(15, TimeUnit.SECONDS)) wins++;
        assertThat(wins).isEqualTo(same ? 2 : 1);
        assertThat(
                count(
                    "SELECT count(*) FROM platform.job WHERE kind='MEDIA_PROCESS' AND dedup_key=?",
                    intent.id().toString()))
            .isEqualTo(1);
        assertThat(
                count(
                    "SELECT count(*) FROM platform.outbox_event WHERE event_type='MediaUploaded' AND aggregate_id=?",
                    intent.id()))
            .isEqualTo(1);
      } finally {
        pool.shutdownNow();
        reset(objects);
      }
      assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
      assertThat(images.status(owner, intent.id()).state()).isEqualTo("READY");
    }
  }

  @Test
  void crashAfterBothVariantWritesBeforeReadyRollsBackAckAndRetriesWithNewGeneration()
      throws Exception {
    var a = user();
    var u = upload(a, "POST", png(7, 5));
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new Failure(503, "TEST_ACK_CRASH");
            })
        .when(assets)
        .ready(eq(u.id()), eq(1L), anyList(), any());
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isFalse();
    assertThat(images.status(a, u.id()).state()).isEqualTo("PROCESSING");
    assertThat(assets.variants(u.id(), 1)).isEmpty();
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE event_type='MediaReady' AND aggregate_id=?",
                u.id()))
        .isZero();
    assertThat(count("SELECT retained_bytes FROM media.quota WHERE owner_id=?", a.userId()))
        .isZero();
    assertThat(count("SELECT reserved_bytes FROM media.quota WHERE owner_id=?", a.userId()))
        .isPositive();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + u.id() + "/1/"))
                .versions())
        .hasSize(2);
    reset(assets);
    clock.now = clock.now.plusSeconds(3601);
    assertThat(work.consumeExternalJob(UUID.randomUUID(), List.of(processing))).isTrue();
    assertThat(images.status(a, u.id()).state()).isEqualTo("READY");
    assertThat(assets.variants(u.id(), 2)).hasSize(2);
    for (int n = 0;
        n < 200
            && !StorageContractIT.admin
                .listObjectVersions(
                    r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + u.id() + "/1/"))
                .versions()
                .isEmpty();
        n++) work.consumeExternalJob(UUID.randomUUID(), List.of(generations));
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + u.id() + "/1/"))
                .versions())
        .isEmpty();
    assertThat(
            StorageContractIT.admin
                .listObjectVersions(
                    r -> r.bucket(StorageContractIT.DELIVERY).prefix("variants/" + u.id() + "/2/"))
                .versions())
        .hasSize(2);
  }
}
