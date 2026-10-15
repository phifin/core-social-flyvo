package com.flyvo.socialcore.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.graph.api.GraphCommands;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.application.Profiles;
import com.flyvo.socialcore.search.api.*;
import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import com.flyvo.socialcore.search.application.*;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
  }

  static class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-10T00:00:00Z");

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
    TestClock searchClock() {
      return new TestClock();
    }
  }

  @Autowired TestClock clock;
  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired ContentCommands posts;
  @Autowired CommunityCommands communities;
  @Autowired AdmissionCommands admissions;
  @Autowired MembershipCommands members;
  @Autowired MembershipQueries memberships;
  @Autowired GraphCommands graph;
  @Autowired SearchQueries queries;
  @Autowired SearchMaintenance maintenance;
  @Autowired SearchSources sources;
  @Autowired SearchRebuildWorker rebuild;
  @Autowired SearchAccountRepair repair;
  @Autowired DurableWorkStore worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired AccessTokens tokens;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @MockitoSpyBean ContentSearchSources content;
  @MockitoSpyBean PostRepository postStore;
  @MockitoSpyBean SearchIndex index;
  @MockitoSpyBean com.flyvo.socialcore.profile.api.ProfileQueries profileQueries;
  @MockitoSpyBean DurableWork durable;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    clock.now = clock.now.plusSeconds(120);
    tx = new TransactionTemplate(manager);
  }

  String word() {
    return "cedar" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  ActorContext user() {
    UUID id =
        tx.execute(
            s -> {
              var u = accounts.createVerified();
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

  ActorContext admin() {
    var a = user();
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", a.userId());
    return a;
  }

  ContentQueries.PostView post(ActorContext a, String body, UUID community) {
    return posts.publish(
        a,
        new ContentCommands.Publish(
            body,
            null,
            community,
            new ContentCommands.Audience(community == null ? "PUBLIC" : "COMMUNITY", List.of()),
            List.of(),
            List.of()),
        null);
  }

  void drain() {
    for (int i = 0; i < 2000; i++) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM platform.outbox_delivery WHERE status IN ('RETRY','DEAD')",
                  Integer.class))
          .isZero();
      if (jdbc.queryForObject(
              "SELECT count(*) FROM platform.outbox_delivery WHERE status<>'COMPLETED'",
              Integer.class)
          == 0) return;
      worker.consumeOne(UUID.randomUUID());
    }
    fail("Delivery drain exceeded bound");
  }

  void jobs() {
    for (int i = 0; i < 100; i++) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM platform.job WHERE kind IN ('SEARCH_REBUILD','SEARCH_ACCOUNT') AND status<>'COMPLETED'",
              Integer.class)
          == 0) return;
      assertThat(worker.consumeJob(UUID.randomUUID(), List.of(rebuild, repair))).isTrue();
    }
    fail("Search job drain exceeded bound");
  }

  void denied(Runnable call, int status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  @Test
  void liveWorkerIndexesAccentedNamesHandlesCardsAndPostsWithoutUnaccentClaim() {
    var owner = user();
    var viewer = user();
    var name =
        profiles.change(owner, 0, "viet_" + word().substring(0, 12), "Nguyễn Hà Nội", "", "PUBLIC");
    var p = post(owner, "Kỳ nghỉ hè Hà Nội", null);
    var c =
        communities.create(
            owner,
            "village-" + word(),
            "Hội Hà Nội",
            "Thành viên tổng hợp",
            "PRIVATE",
            "INVITE_ONLY",
            null);
    drain();
    assertThat(queries.users(viewer, name.handle(), 20, null).items())
        .extracting(SearchQueries.User::id)
        .contains(owner.userId());
    assertThat(queries.users(viewer, "nguyễn", 20, null).items())
        .extracting(SearchQueries.User::id)
        .contains(owner.userId());
    assertThat(queries.users(viewer, "nguyen", 20, null).items())
        .extracting(SearchQueries.User::id)
        .doesNotContain(owner.userId());
    assertThat(queries.posts(viewer, "hà nội", null, 20, null).items())
        .extracting(SearchQueries.Post::id)
        .contains(p.id());
    assertThat(queries.posts(viewer, "ha noi", null, 20, null).items())
        .extracting(SearchQueries.Post::id)
        .doesNotContain(p.id());
    assertThat(queries.communities(viewer, "hội hà", 20, null).items())
        .extracting(SearchQueries.Community::id)
        .contains(c.id());
  }

  @Test
  void stoppedWorkerNeverReturnsNewPrivateTextDeletedPostOrPrivateProfile() {
    var owner = user();
    var viewer = user();
    String q = word();
    profiles.change(owner, 0, "name_" + q.substring(0, 12), q, "", "PUBLIC");
    var p = post(owner, q + " public body", null);
    drain();
    assertThat(queries.posts(viewer, q, null, 20, null).items()).hasSize(1);
    var privatePost =
        posts.audience(
            owner, p.id(), p.version(), new ContentCommands.Audience("ONLY_ME", List.of()));
    var edited =
        posts.edit(
            owner,
            p.id(),
            privatePost.version(),
            new ContentCommands.Edit("PRIVATE_NEVER_FALLBACK", null, List.of(), List.of()));
    profiles.change(
        owner, 1, "name_" + q.substring(0, 12), "PRIVATE_NAME_NEVER_FALLBACK", "", "PRIVATE");
    assertThat(queries.posts(viewer, q, null, 20, null).items()).isEmpty();
    assertThat(queries.users(viewer, q, 20, null).items()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM search.document WHERE source_id=?", String.class, p.id()))
        .contains("public body");
    posts.delete(owner, p.id(), edited.version());
    assertThat(queries.posts(owner, q, null, 20, null).items()).isEmpty();
  }

  @Test
  void currentBlockAndAccountStatusDenyWithoutProjectionAndOlderSnapshotsCannotRevive() {
    var owner = user();
    var viewer = user();
    var admin = admin();
    String q = word();
    profiles.change(owner, 0, "user_" + q.substring(0, 12), q, "", "PUBLIC");
    var p = post(owner, q, null);
    drain();
    var old = sources.inspect(Kind.POST, List.of(p.id())).getFirst();
    graph.block(viewer, owner.userId(), true);
    assertThat(queries.posts(viewer, q, null, 20, null).items()).isEmpty();
    assertThat(queries.users(viewer, q, 20, null).items()).isEmpty();
    graph.block(viewer, owner.userId(), false);
    accounts.suspend(admin, owner.userId(), "TEST_STATUS");
    assertThat(queries.posts(viewer, q, null, 20, null).items()).isEmpty();
    assertThat(queries.users(viewer, q, 20, null).items()).isEmpty();
    drain();
    jobs();
    assertThat(tx.<Boolean>execute(s -> index.upsert(old))).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT tombstone FROM search.document WHERE source_id=?", Boolean.class, p.id()))
        .isTrue();
  }

  @Test
  void privateCommunityDiscoveryIsNotPostPermissionAndMembershipRevokesImmediately() {
    var owner = user();
    var viewer = user();
    String q = word();
    var c =
        communities.create(
            owner, "c-" + q, q, "Discovery description", "PRIVATE", "INVITE_ONLY", null);
    var p = post(owner, q, c.id());
    drain();
    assertThat(queries.communities(viewer, q, 20, null).items()).hasSize(1);
    assertThat(queries.posts(viewer, q, null, 20, null).items()).isEmpty();
    denied(() -> queries.posts(viewer, q, c.id(), 20, null), 404);
    var invite = admissions.invite(owner, c.id(), viewer.userId(), null);
    admissions.respondInvite(viewer, invite.id(), "accept");
    assertThat(queries.posts(viewer, q, c.id(), 20, null).items())
        .extracting(SearchQueries.Post::id)
        .containsExactly(p.id());
    var member = memberships.facts(viewer.userId(), List.of(c.id())).getFirst();
    members.leave(viewer, c.id(), member.version());
    assertThat(queries.posts(viewer, q, null, 20, null).items()).isEmpty();
    long currentVersion = queries.communities(owner, q, 20, null).items().getFirst().version();
    communities.archive(owner, c.id(), currentVersion);
    assertThat(queries.communities(viewer, q, 20, null).items()).isEmpty();
  }

  @Test
  void rankIdCursorTiesAreBoundTamperProofAndAdvanceWithoutDuplicates() {
    var owner = user();
    var viewer = user();
    String q = word();
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 6; i++) ids.add(post(owner, q, null).id());
    drain();
    var first = queries.posts(viewer, q, null, 2, null);
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();
    denied(() -> queries.posts(owner, q, null, 2, first.nextCursor()), 400);
    denied(() -> queries.posts(viewer, q + " other", null, 2, first.nextCursor()), 400);
    denied(() -> queries.posts(viewer, q, null, 3, first.nextCursor()), 400);
    denied(() -> queries.users(viewer, q, 2, first.nextCursor()), 400);
    String tamper =
        (first.nextCursor().startsWith("A") ? "B" : "A") + first.nextCursor().substring(1);
    denied(() -> queries.posts(viewer, q, null, 2, tamper), 400);
    var all = new ArrayList<UUID>();
    String after = null;
    int pages = 0;
    do {
      var page = queries.posts(viewer, q, null, 2, after);
      all.addAll(page.items().stream().map(SearchQueries.Post::id).toList());
      after = page.nextCursor();
      assertThat(++pages).isLessThan(10);
    } while (after != null);
    assertThat(all)
        .doesNotHaveDuplicates()
        .containsExactlyElementsOf(
            ids.stream().sorted(Comparator.comparing(UUID::toString)).toList());
    clock.now = clock.now.plusSeconds(1801);
    denied(() -> queries.posts(viewer, q, null, 2, first.nextCursor()), 400);
  }

  @Test
  void versionVectorsMissingSourceAndDuplicateStateRetainOrderingWithoutFabricatedVersion() {
    var owner = user();
    UUID id = UUID.randomUUID();
    var current =
        new SearchIndex.Document(
            Kind.POST, id, 5L, owner.userId(), null, 5L, false, false, "", "", "current");
    assertThat(tx.<Boolean>execute(s -> index.upsert(current))).isTrue();
    assertThat(tx.<Boolean>execute(s -> index.upsert(current))).isFalse();
    assertThat(
            tx.<Boolean>execute(
                s ->
                    index.upsert(
                        new SearchIndex.Document(
                            Kind.POST,
                            id,
                            6L,
                            owner.userId(),
                            null,
                            4L,
                            false,
                            false,
                            "",
                            "",
                            "stale account"))))
        .isFalse();
    assertThat(
            tx.<Boolean>execute(
                s ->
                    index.upsert(
                        new SearchIndex.Document(
                            Kind.POST,
                            id,
                            4L,
                            owner.userId(),
                            null,
                            6L,
                            false,
                            false,
                            "",
                            "",
                            "stale source"))))
        .isFalse();
    tx.executeWithoutResult(s -> sources.refresh(Kind.POST, List.of(id)));
    assertThat(tx.<Boolean>execute(s -> index.upsert(current))).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT source_version FROM search.document WHERE source_id=?", Long.class, id))
        .isEqualTo(5L);
    UUID absent = UUID.randomUUID();
    tx.executeWithoutResult(s -> sources.refresh(Kind.POST, List.of(absent)));
    assertThat(
            jdbc.queryForObject(
                "SELECT source_version FROM search.document WHERE source_id=?", Long.class, absent))
        .isNull();
  }

  @Test
  void rebuildUsesCurrentLegacySourcesResumesAndLiveNewerStateWinsOldExport() {
    var admin = admin();
    var owner = user();
    String q = word();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?, ?,?,'PUBLIC','PUBLISHED',1,now(),now(),now())",
        id,
        owner.userId(),
        q);
    var old = sources.inspect(Kind.POST, List.of(id)).getFirst();
    var r = maintenance.start(admin, Kind.POST, null, null);
    assertThat(worker.consumeJob(UUID.randomUUID(), List.of(rebuild))).isTrue();
    var partial = maintenance.read(admin, r.id());
    assertThat(partial.version()).isGreaterThan(0);
    posts.edit(owner, id, 0, new ContentCommands.Edit(q + " latest", null, List.of(), List.of()));
    drain();
    assertThat(tx.<Boolean>execute(s -> index.upsert(old))).isFalse();
    denied(() -> maintenance.start(admin, Kind.POST, r.id(), 0L), 412);
    maintenance.start(admin, Kind.POST, r.id(), partial.version());
    jobs();
    assertThat(maintenance.read(admin, r.id()).stage()).isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM search.document WHERE source_id=?", String.class, id))
        .endsWith("latest");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_entry WHERE action='SEARCH_REBUILD_COMPLETED' AND target_id=?",
                Integer.class,
                r.id()))
        .isEqualTo(1);
  }

  @Test
  void queryLimitsAndAuthenticatedIndependentRateBudgetRejectAbuse() throws Exception {
    var a = user();
    String token = tokens.issue(a);
    for (String q : List.of("x", "a".repeat(201), "a b c d e f g h i", "%_!"))
      denied(() -> queries.posts(a, q, null, 20, null), 400);
    assertThat(queries.posts(a, "zz' OR 1=1 --", null, 20, null).items()).isEmpty();
    denied(() -> queries.posts(a, "words", null, 51, null), 400);
    mvc.perform(get("/api/v1/search/posts").param("q", "words"))
        .andExpect(status().isUnauthorized());
    for (int i = 0; i < 30; i++)
      mvc.perform(
              get("/api/v1/search/posts")
                  .header("Authorization", "Bearer " + token)
                  .param("q", "nomatch"))
          .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/search/posts")
                .header("Authorization", "Bearer " + token)
                .param("q", "nomatch"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));
    mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void fiftyPostHydrationIsOneOwnerBatchAndOperatorReadsRequireCurrentAdmin() {
    var owner = user();
    var viewer = user();
    var admin = admin();
    String q = word();
    for (int i = 0; i < 50; i++) post(owner, q, null);
    drain();
    clearInvocations(content, postStore);
    assertThat(queries.posts(viewer, q, null, 50, null).items()).hasSize(50);
    verify(content, times(1)).hydrate(eq(viewer), argThat(ids -> ids.size() == 50), isNull());
    verify(postStore, times(1))
        .searchLoad(eq(viewer.userId()), argThat(ids -> ids.size() == 50), isNull(), any());
    denied(() -> maintenance.start(viewer, Kind.USER, null, null), 403);
    var r = maintenance.start(admin, Kind.USER, null, null);
    jobs();
    assertThat(maintenance.read(admin, r.id()).stage()).isEqualTo("DONE");
    jdbc.update("UPDATE account.user_account SET platform_role='USER' WHERE id=?", admin.userId());
    denied(() -> maintenance.read(admin, r.id()), 403);
  }

  @Test
  void bodyAndPermissionAreCoherentWhenPrivacyEditCommitsAfterSourceStatement() throws Exception {
    var owner = user();
    var viewer = user();
    String q = word();
    var p = post(owner, q + " original public", null);
    drain();
    var once = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (once.compareAndSet(true, false)) {
                try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                  pool.submit(
                          () -> {
                            var hidden =
                                posts.audience(
                                    owner,
                                    p.id(),
                                    p.version(),
                                    new ContentCommands.Audience("ONLY_ME", List.of()));
                            posts.edit(
                                owner,
                                p.id(),
                                hidden.version(),
                                new ContentCommands.Edit(
                                    "PRIVATE_CONCURRENT_BODY", null, List.of(), List.of()));
                          })
                      .get(5, java.util.concurrent.TimeUnit.SECONDS);
                }
              }
              return invocation.callRealMethod();
            })
        .when(profileQueries)
        .attributionSummaries(anyList());
    assertThat(queries.posts(viewer, q, null, 20, null).items())
        .singleElement()
        .satisfies(
            card ->
                assertThat(card.snippet())
                    .isEqualTo(q + " original public")
                    .doesNotContain("PRIVATE_CONCURRENT_BODY"));
    assertThat(queries.posts(viewer, q, null, 20, null).items()).isEmpty();
  }

  @Test
  void failedCheckpointRollsBackDocumentsAndNextJobAndRetryResumesTheSamePass() {
    var admin = admin();
    var r = maintenance.start(admin, Kind.USER, null, null);
    int before = jdbc.queryForObject("SELECT count(*) FROM search.document", Integer.class);
    tx.executeWithoutResult(
        s ->
            doThrow(new IllegalStateException("SYNTHETIC_CHECKPOINT_FAILURE"))
                .when(index)
                .checkpoint(any(), anyString(), any(), anyInt(), anyInt(), any()));
    assertThat(worker.consumeJob(UUID.randomUUID(), List.of(rebuild))).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM search.document", Integer.class))
        .isEqualTo(before);
    assertThat(maintenance.read(admin, r.id()).version()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.job WHERE kind='SEARCH_REBUILD' AND dedup_key LIKE ?",
                Integer.class,
                r.id() + "%"))
        .isEqualTo(1);
    tx.executeWithoutResult(
        s ->
            doCallRealMethod()
                .when(index)
                .checkpoint(any(), anyString(), any(), anyInt(), anyInt(), any()));
    clock.now = clock.now.plusSeconds(61);
    jobs();
    assertThat(maintenance.read(admin, r.id()).stage()).isEqualTo("DONE");
  }

  @Test
  void boundedRefillAdvancesPastOneThousandMissingSourcesWithoutProjectionFallback() {
    var owner = user();
    var viewer = user();
    String q = word();
    var p = post(owner, q, null);
    drain();
    jdbc.batchUpdate(
        "INSERT INTO search.document(source_kind,source_id,source_version,owner_id,account_version,tombstone,missing_source,source_key,title,body,key_normalized,name_normalized,updated_at) VALUES ('POST',?,0,?,0,false,false,'','',?,'','',clock_timestamp())",
        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
          public int getBatchSize() {
            return 1000;
          }

          public void setValues(java.sql.PreparedStatement s, int i) throws java.sql.SQLException {
            s.setObject(1, new UUID(0, 10000 + i));
            s.setObject(2, owner.userId());
            s.setString(3, q);
          }
        });
    var ids = new ArrayList<UUID>();
    String after = null;
    int pages = 0;
    do {
      var page = queries.posts(viewer, q, null, 20, after);
      ids.addAll(page.items().stream().map(SearchQueries.Post::id).toList());
      after = page.nextCursor();
      assertThat(++pages).isLessThanOrEqualTo(11);
    } while (after != null);
    assertThat(ids).containsExactly(p.id());
  }

  @Test
  void snapshotChecksumsDetectCorruptionAndRepairUnicodeTextWithoutExposingProjectionBody() {
    var owner = user();
    var p = post(owner, "Hà Nội synthetic 🪷", null);
    drain();
    var snapshot = sources.inspect(Kind.POST, List.of(p.id()));
    tx.executeWithoutResult(s -> index.verifySnapshots(snapshot));
    jdbc.update(
        "UPDATE search.document SET body='CORRUPTED_SYNTHETIC_TEXT' WHERE source_id=?", p.id());
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> index.verifySnapshots(snapshot)))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasMessage("SEARCH_SNAPSHOT_CHECKSUM_MISMATCH");
    tx.executeWithoutResult(s -> sources.refresh(Kind.POST, List.of(p.id())));
    tx.executeWithoutResult(s -> index.verifySnapshots(snapshot));
  }

  @Test
  void operatorAuditFailureRollsBackRebuildAndJobAndRawExportsHaveNoHttpRoute() throws Exception {
    var admin = admin();
    int before = jdbc.queryForObject("SELECT count(*) FROM search.rebuild", Integer.class);
    int jobsBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.job WHERE kind='SEARCH_REBUILD'", Integer.class);
    tx.executeWithoutResult(
        s ->
            doThrow(new IllegalStateException("SYNTHETIC_AUDIT_FAILURE"))
                .when(durable)
                .audit(
                    eq(admin.userId()),
                    eq("SEARCH_REBUILD_STARTED"),
                    eq("search_rebuild"),
                    any(),
                    eq("SUCCESS"),
                    eq("COMMUNITY")));
    assertThatThrownBy(() -> maintenance.start(admin, Kind.COMMUNITY, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM search.rebuild", Integer.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.job WHERE kind='SEARCH_REBUILD'", Integer.class))
        .isEqualTo(jobsBefore);
    mvc.perform(
            get("/api/v1/search/export").header("Authorization", "Bearer " + tokens.issue(admin)))
        .andExpect(status().isForbidden());
  }

  @Test
  void concurrentCurrentAndOlderSourceSnapshotsSerializeAtTheProjectionRow() throws Exception {
    var owner = user();
    String q = word();
    var p = post(owner, q, null);
    var old = sources.inspect(Kind.POST, List.of(p.id())).getFirst();
    posts.edit(
        owner,
        p.id(),
        p.version(),
        new ContentCommands.Edit(q + " newest", null, List.of(), List.of()));
    var current = sources.inspect(Kind.POST, List.of(p.id())).getFirst();
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                barrier.await();
                return tx.<Boolean>execute(s -> index.upsert(old));
              });
      var b =
          pool.submit(
              () -> {
                barrier.await();
                return tx.<Boolean>execute(s -> index.upsert(current));
              });
      a.get(5, java.util.concurrent.TimeUnit.SECONDS);
      b.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT source_version FROM search.document WHERE source_id=?", Long.class, p.id()))
        .isEqualTo(current.version());
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM search.document WHERE source_id=?", String.class, p.id()))
        .endsWith("newest");
  }
}
