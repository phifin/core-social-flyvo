package com.flyvo.socialcore.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.api.ContentQueries.*;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.feed.api.FeedQueries;
import com.flyvo.socialcore.graph.api.GraphCommands;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.moderation.api.Reports;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.application.Profiles;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(FeedBenchmarkTool.Config.class)
class FeedsIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-10-11T00:00:00Z");

    public Instant instant() {
      return now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    MutableClock feedClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired FeedQueries feeds;
  @Autowired ContentQueries content;
  @Autowired ContentCommands posts;
  @Autowired Interactions interactions;
  @Autowired Reports reports;
  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired GraphCommands graph;
  @Autowired CommunityCommands communities;
  @Autowired MembershipCommands members;
  @Autowired MembershipQueries memberships;
  @Autowired MockMvc mvc;
  @Autowired AccessTokens tokens;
  @Autowired MeterRegistry metrics;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @MockitoSpyBean PostRepository store;
  @Autowired JdbcTemplate jdbc;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    clock.now = Instant.parse("2026-10-11T00:00:00Z");
    tx = new TransactionTemplate(manager);
  }

  ActorContext user() {
    var id =
        tx.execute(
            s -> {
              var u = accounts.createVerified();
              profiles.provision(u);
              return u;
            });
    var sid = UUID.randomUUID();
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

  CommunityCommands.Info community(ActorContext owner, String visibility) {
    return communities.create(
        owner, "f-" + UUID.randomUUID(), "Synthetic feed scope", "", visibility, "OPEN", null);
  }

  PostView publish(ActorContext a, String audience, UUID community, List<UUID> recipients) {
    return posts.publish(
        a,
        new ContentCommands.Publish(
            "Synthetic feed text",
            null,
            community,
            new ContentCommands.Audience(audience, recipients),
            List.of(),
            List.of()),
        null);
  }

  PostView publish(ActorContext a) {
    return publish(a, "PUBLIC", null, List.of());
  }

  List<UUID> ids(FeedQueries.Page page) {
    return page.items().stream().map(i -> i.post().id()).toList();
  }

  void denied(Runnable run, int status) {
    assertThatThrownBy(run::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  void release(CountDownLatch l) {
    try {
      assertThat(l.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void realHighRejectionProducesEmptyProgressingPagesUntilTheCandidateRangeIsExhausted() {
    var viewer = user();
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) SELECT gen_random_uuid(),?,'Synthetic high rejection','ONLY_ME','PUBLISHED',1,timestamptz '2026-10-10 00:00:00Z',now(),now() FROM generate_series(1,1001)",
        viewer.userId());
    var revoke = new TransactionTemplate(manager);
    revoke.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    doAnswer(
            call -> {
              List<UUID> ids = call.getArgument(2);
              revoke.executeWithoutResult(
                  s ->
                      jdbc.update(
                          "UPDATE content.post SET status='DELETED',deleted_at=now(),version=version+1 WHERE id IN ("
                              + String.join(",", Collections.nCopies(ids.size(), "?"))
                              + ")",
                          ids.toArray()));
              return call.callRealMethod();
            })
        .when(store)
        .feedLoad(eq(viewer.userId()), any(), anyList(), any());
    try {
      String cursor = null;
      int pages = 0;
      var tokens = new HashSet<String>();
      do {
        var page = feeds.home(viewer, 20, cursor);
        assertThat(page.items()).isEmpty();
        cursor = page.nextCursor();
        if (cursor != null) assertThat(tokens.add(cursor)).isTrue();
        assertThat(++pages).isLessThan(15);
      } while (cursor != null);
      assertThat(pages).isGreaterThan(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM content.post WHERE author_id=? AND status='DELETED'",
                  Long.class,
                  viewer.userId()))
          .isEqualTo(1001);
    } finally {
      reset(store);
    }
  }

  @Test
  void actualStatementCountIsIndependentOfOneVersusFiftyReturnedPosts() {
    var viewer = user();
    for (int i = 0; i < 55; i++) publish(viewer);
    feeds.home(viewer, 50, null);
    FeedBenchmarkTool.STATEMENTS.set(0);
    feeds.home(viewer, 1, null);
    long one = FeedBenchmarkTool.STATEMENTS.get();
    FeedBenchmarkTool.STATEMENTS.set(0);
    feeds.home(viewer, 50, null);
    long fifty = FeedBenchmarkTool.STATEMENTS.get();
    assertThat(fifty).isEqualTo(one).isBetween(1L, 30L);
    var trace = FeedBenchmarkTool.candidateTrace;
    var plan =
        jdbc.queryForObject(
            "EXPLAIN (FORMAT JSON) " + trace.sql(), String.class, trace.bindings().toArray());
    var nodes = java.util.regex.Pattern.compile("\"Node Type\": \"([^\"]+)\"").matcher(plan);
    assertThat(nodes.find()).isTrue();
    assertThat(nodes.group(1)).isEqualTo("Limit");
    assertThat(nodes.find()).isTrue();
    assertThat(nodes.group(1))
        .as("No full eligible-set sort before LIMIT")
        .isEqualTo("Nested Loop");
  }

  @Test
  void
      homeUnionIncludesOwnFollowFriendJoinedCustomDeduplicatedButNotUnrelatedCommunityOrPublicDiscovery() {
    var viewer = user();
    var followed = user();
    var friend = user();
    var custom = user();
    var outsider = user();
    graph.follow(viewer, followed.userId(), true);
    var request = graph.request(viewer, friend.userId());
    graph.respond(friend, request.id(), "accept");
    graph.follow(viewer, friend.userId(), true);
    var joined = community(outsider, "PRIVATE");
    members.join(viewer, joined.id());
    var unrelated = community(followed, "PUBLIC");
    var own = publish(viewer, "ONLY_ME", null, List.of());
    var follow = publish(followed, "FOLLOWERS", null, List.of());
    var friends = publish(friend, "FRIENDS", null, List.of());
    var direct = publish(custom, "CUSTOM", null, List.of(viewer.userId()));
    var scoped = publish(outsider, "COMMUNITY", joined.id(), List.of());
    var leak = publish(followed, "COMMUNITY", unrelated.id(), List.of());
    var discovery = publish(outsider);
    assertThat(ids(feeds.home(viewer, 50, null)))
        .containsExactlyInAnyOrder(own.id(), follow.id(), friends.id(), direct.id(), scoped.id())
        .doesNotContain(leak.id(), discovery.id());
    assertThat(ids(feeds.timeline(viewer, followed.userId(), 50, null)))
        .containsExactly(follow.id());
    assertThat(ids(feeds.community(viewer, unrelated.id(), 50, null))).containsExactly(leak.id());
    graph.follow(viewer, friend.userId(), false);
    assertThat(ids(feeds.home(viewer, 50, null))).contains(friends.id());
  }

  @Test
  void muteOnlyHomeAndBlocksInactiveAuthorsAndEveryAudienceAffectAllSources() {
    var viewer = user();
    var author = user();
    graph.follow(viewer, author.userId(), true);
    var p = publish(author);
    var c = community(author, "PUBLIC");
    var scoped = publish(author, "COMMUNITY", c.id(), List.of());
    members.join(viewer, c.id());
    graph.mute(viewer, author.userId(), true);
    assertThat(ids(feeds.home(viewer, 50, null))).isEmpty();
    assertThat(ids(feeds.timeline(viewer, author.userId(), 50, null))).contains(p.id());
    assertThat(ids(feeds.community(viewer, c.id(), 50, null))).contains(scoped.id());
    graph.block(author, viewer.userId(), true);
    denied(() -> feeds.timeline(viewer, author.userId(), 50, null), 404);
    assertThat(ids(feeds.community(viewer, c.id(), 50, null))).isEmpty();
    assertThat(ids(feeds.timeline(ActorContext.anonymous(), author.userId(), 50, null)))
        .contains(p.id());
    tx.executeWithoutResult(s -> accounts.requestDeletion(author.userId()));
    denied(() -> feeds.timeline(viewer, author.userId(), 50, null), 404);
    assertThat(ids(feeds.community(viewer, c.id(), 50, null))).isEmpty();
  }

  @Test
  void privateAndPublicScopesEnforceCurrentMembershipSuspensionAndArchiveReadsEvenWhenEmpty() {
    var owner = user();
    var viewer = user();
    var privateC = community(owner, "PRIVATE");
    denied(() -> feeds.community(viewer, privateC.id(), 20, null), 404);
    denied(() -> feeds.community(ActorContext.anonymous(), privateC.id(), 20, null), 404);
    var m = members.join(viewer, privateC.id());
    assertThat(feeds.community(viewer, privateC.id(), 20, null).items()).isEmpty();
    members.leave(viewer, privateC.id(), m.version());
    denied(() -> feeds.community(viewer, privateC.id(), 20, null), 404);
    var publicC = community(owner, "PUBLIC");
    var pub = publish(owner, "COMMUNITY", publicC.id(), List.of());
    var membership = members.join(viewer, publicC.id());
    tx.executeWithoutResult(
        s ->
            members.sanction(
                owner,
                publicC.id(),
                viewer.userId(),
                membership.version(),
                "SUSPEND_MEMBER",
                clock.instant().plusSeconds(60)));
    denied(() -> feeds.community(viewer, publicC.id(), 20, null), 404);
    assertThat(ids(feeds.community(ActorContext.anonymous(), publicC.id(), 20, null)))
        .contains(pub.id());
    var current = memberships.scopes(List.of(publicC.id())).getFirst();
    var version =
        jdbc.queryForObject(
            "SELECT version FROM community.community WHERE id=?",
            Long.class,
            current.communityId());
    communities.archive(owner, publicC.id(), version);
    assertThat(ids(feeds.community(ActorContext.anonymous(), publicC.id(), 20, null)))
        .contains(pub.id());
  }

  @Test
  void
      equalTimestampsFullTraversalDoesNotDropPrefetchedTailOrRepeatAndCursorIsViewerScopeSizeBound() {
    var author = user();
    var viewer = user();
    graph.follow(viewer, author.userId(), true);
    var expected = new HashSet<UUID>();
    for (int i = 0; i < 63; i++) expected.add(publish(author).id());
    var actual = new ArrayList<UUID>();
    String cursor = null;
    int pages = 0;
    do {
      var p = feeds.home(viewer, 7, cursor);
      actual.addAll(ids(p));
      cursor = p.nextCursor();
      assertThat(++pages).isLessThan(15);
    } while (cursor != null);
    assertThat(actual)
        .hasSize(63)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(expected);
    var first = feeds.home(viewer, 7, null);
    var token = first.nextCursor();
    assertThat(token).isNotNull();
    denied(() -> feeds.home(viewer, 8, token), 400);
    denied(() -> feeds.home(author, 7, token), 400);
    denied(() -> feeds.timeline(viewer, author.userId(), 7, token), 400);
    var bytes = Base64.getUrlDecoder().decode(token);
    bytes[bytes.length - 1] ^= 1;
    denied(
        () -> feeds.home(viewer, 7, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)),
        400);
    clock.now = clock.now.plusSeconds(1800);
    denied(() -> feeds.home(viewer, 7, token), 400);
  }

  @Test
  void cutoffExcludesNewPublicationEditsDoNotBumpAndOlderNewlyEligibleCanJoinTraversal() {
    var viewer = user();
    var author = user();
    var older = user();
    graph.follow(viewer, author.userId(), true);
    var old = publish(older);
    publish(author);
    clock.now = clock.now.plusSeconds(1);
    var recent = publish(author);
    var first = feeds.home(viewer, 1, null);
    var before = content.read(author, recent.id()).publishedAt();
    posts.edit(
        author,
        recent.id(),
        0,
        new ContentCommands.Edit("Edited without chronology bump", null, List.of(), List.of()));
    assertThat(content.read(author, recent.id()).publishedAt()).isEqualTo(before);
    var cutoff = content.feedCutoff();
    var late = publish(author);
    jdbc.update(
        "UPDATE content.post SET published_at=? WHERE id=?",
        cutoff.plusSeconds(86400).atOffset(ZoneOffset.UTC),
        late.id());
    graph.follow(viewer, older.userId(), true);
    var remaining = new ArrayList<UUID>();
    String cursor = first.nextCursor();
    assertThat(cursor).isNotNull();
    do {
      var next = feeds.home(viewer, 1, cursor);
      remaining.addAll(ids(next));
      cursor = next.nextCursor();
    } while (cursor != null);
    assertThat(remaining).contains(old.id()).doesNotContain(late.id());
    assertThat(ids(feeds.home(viewer, 50, null))).doesNotContain(late.id());
  }

  @Test
  void currentUnfollowBlockMuteMembershipAndDeletionBeforeHydrationDefeatStaleCandidates()
      throws Exception {
    for (var change :
        List.of(
            "UNFOLLOW",
            "UNFRIEND",
            "BLOCK",
            "REVERSE_BLOCK",
            "MUTE",
            "MEMBERSHIP",
            "DELETE",
            "REMOVE",
            "AUDIENCE",
            "ACCOUNT")) {
      var viewer = user();
      var author = user();
      graph.follow(viewer, author.userId(), true);
      if (change.equals("UNFRIEND")) {
        graph.follow(viewer, author.userId(), false);
        var friendship = graph.request(viewer, author.userId());
        graph.respond(author, friendship.id(), "accept");
      }
      var c = community(author, "PRIVATE");
      var m = members.join(viewer, c.id());
      var p =
          Set.of("MEMBERSHIP", "REMOVE").contains(change)
              ? publish(author, "COMMUNITY", c.id(), List.of())
              : publish(author);
      var reported =
          change.equals("REMOVE")
              ? reports.submit(
                  viewer,
                  new Reports.Submit(Reports.Target.POST, p.id(), Reports.Category.SPAM, null),
                  null)
              : null;
      var held = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      doAnswer(
              call -> {
                var keys = call.callRealMethod();
                held.countDown();
                release(finish);
                return keys;
              })
          .when(store)
          .feedCandidates(eq(viewer.userId()), any(), anyInt(), any(), any(), any());
      try (var pool = Executors.newSingleThreadExecutor()) {
        var request = pool.submit(() -> feeds.home(viewer, 20, null));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        switch (change) {
          case "UNFOLLOW" -> graph.follow(viewer, author.userId(), false);
          case "UNFRIEND" -> graph.unfriend(viewer, author.userId());
          case "BLOCK" -> graph.block(author, viewer.userId(), true);
          case "REVERSE_BLOCK" -> graph.block(viewer, author.userId(), true);
          case "MUTE" -> graph.mute(viewer, author.userId(), true);
          case "MEMBERSHIP" -> members.leave(viewer, c.id(), m.version());
          case "DELETE" -> posts.delete(author, p.id(), 0);
          case "REMOVE" ->
              reports.resolve(
                  author,
                  c.id(),
                  0,
                  new Reports.Resolution(
                      reported.id(),
                      Reports.Action.REMOVE_CONTENT,
                      "Synthetic report/feed regression",
                      0L,
                      null));
          case "ACCOUNT" -> tx.executeWithoutResult(s -> accounts.requestDeletion(author.userId()));
          case "AUDIENCE" ->
              posts.audience(author, p.id(), 0, new ContentCommands.Audience("ONLY_ME", List.of()));
        }
        finish.countDown();
        assertThat(ids(request.get(15, TimeUnit.SECONDS))).doesNotContain(p.id());
      } finally {
        finish.countDown();
        reset(store);
      }
    }
  }

  @Test
  void coherentFeedSourceReadNeverCombinesAnOldCustomGrantWithNewPrivateBody() throws Exception {
    var author = user();
    var viewer = user();
    var p = publish(author, "CUSTOM", null, List.of(viewer.userId()));
    var held = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            call -> {
              var result = call.callRealMethod();
              held.countDown();
              release(finish);
              return result;
            })
        .when(store)
        .feedLoad(eq(viewer.userId()), any(), anyList(), any());
    try (var pool = Executors.newSingleThreadExecutor()) {
      var request = pool.submit(() -> feeds.home(viewer, 20, null));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      posts.audience(author, p.id(), 0, new ContentCommands.Audience("ONLY_ME", List.of()));
      posts.edit(
          author,
          p.id(),
          1,
          new ContentCommands.Edit("New private body", null, List.of(), List.of()));
      finish.countDown();
      assertThat(request.get(15, TimeUnit.SECONDS).items())
          .noneSatisfy(item -> assertThat(item.post().body()).isEqualTo("New private body"));
      assertThat(ids(feeds.home(viewer, 20, null))).doesNotContain(p.id());
    } finally {
      finish.countDown();
      reset(store);
    }
  }

  @Test
  void candidateAndAuthorizedHydrateMatchCurrentOwnerPolicyForAudienceGraphMembershipScenarios() {
    var author = user();
    var viewer = user();
    var friend = graph.request(viewer, author.userId());
    graph.respond(author, friend.id(), "accept");
    graph.follow(viewer, author.userId(), true);
    for (var audience : List.of("PUBLIC", "FOLLOWERS", "FRIENDS", "CUSTOM", "ONLY_ME")) {
      var p =
          publish(
              author,
              audience,
              null,
              audience.equals("CUSTOM") ? List.of(viewer.userId()) : List.of());
      for (var actor : List.of(author, viewer, ActorContext.anonymous())) {
        var scope = new FeedScope(FeedMode.TIMELINE, author.userId());
        var keys = content.scanCandidates(actor, scope, 100, content.feedCutoff(), null);
        boolean readable;
        try {
          content.read(actor, p.id());
          readable = true;
        } catch (Failure denied) {
          readable = false;
        }
        assertThat(keys.stream().map(Candidate::id).toList().contains(p.id())).isEqualTo(readable);
        assertThat(content.hydrateAuthorized(actor, scope, List.of(p.id())).isEmpty())
            .isEqualTo(!readable);
      }
    }
  }

  @Test
  void communitySqlAndHydrationMatchCurrentOwnerPolicyForVisibilityAndSanctionMatrix() {
    for (String visibility : List.of("PUBLIC", "PRIVATE")) {
      for (String state : List.of("OUTSIDER", "MEMBER", "LEFT", "SUSPENDED", "BANNED")) {
        var owner = user();
        var viewer = user();
        var c = community(owner, visibility);
        var post = publish(owner, "COMMUNITY", c.id(), List.of());
        if (!state.equals("OUTSIDER")) {
          var m = members.join(viewer, c.id());
          switch (state) {
            case "LEFT" -> members.leave(viewer, c.id(), m.version());
            case "SUSPENDED", "BANNED" ->
                tx.executeWithoutResult(
                    s ->
                        members.sanction(
                            owner,
                            c.id(),
                            viewer.userId(),
                            m.version(),
                            state.equals("BANNED") ? "BAN_MEMBER" : "SUSPEND_MEMBER",
                            state.equals("BANNED") ? null : clock.instant().plusSeconds(60)));
          }
        }
        var scope = new FeedScope(FeedMode.COMMUNITY, c.id());
        for (var actor : List.of(owner, viewer, ActorContext.anonymous())) {
          boolean readable;
          try {
            content.read(actor, post.id());
            readable = true;
          } catch (Failure failure) {
            assertThat(failure.status()).isEqualTo(404);
            readable = false;
          }
          if (readable) {
            assertThat(content.scanCandidates(actor, scope, 100, content.feedCutoff(), null))
                .extracting(Candidate::id)
                .contains(post.id());
            assertThat(content.hydrateAuthorized(actor, scope, List.of(post.id())))
                .extracting(PostView::id)
                .contains(post.id());
          } else {
            denied(
                () -> content.scanCandidates(actor, scope, 100, content.feedCutoff(), null), 404);
            denied(() -> content.hydrateAuthorized(actor, scope, List.of(post.id())), 404);
          }
        }
      }
    }
  }

  @Test
  void
      hydrationAndEngagementAreBatchedMetricsHaveNoViewerLabelsAndHttpSessionRevocationIsImmediate()
          throws Exception {
    var author = user();
    var viewer = user();
    graph.follow(viewer, author.userId(), true);
    for (int i = 0; i < 55; i++) publish(author);
    clearInvocations(store);
    var page = feeds.home(viewer, 50, null);
    assertThat(page.items()).hasSize(50).allSatisfy(i -> assertThat(i.engagement()).isNotNull());
    verify(store, times(1))
        .feedLoad(
            eq(viewer.userId()),
            any(),
            argThat(ids -> ids.size() == 100 || ids.size() == 55),
            any());
    assertThat(metrics.get("feed.scan.ratio").tag("mode", "home").summary().count()).isPositive();
    assertThat(
            metrics.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("feed."))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .toList())
        .allMatch(key -> Set.of("mode", "kind").contains(key));
    var bearer = "Bearer " + tokens.issue(viewer);
    mvc.perform(get("/api/v1/feeds/home").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].post.body").exists())
        .andExpect(jsonPath("$.total").doesNotExist());
    mvc.perform(get("/api/v1/feeds/home")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/users/" + author.userId() + "/timeline")).andExpect(status().isOk());
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=now(),revoke_reason='TEST' WHERE id=?",
        viewer.sessionId());
    mvc.perform(get("/api/v1/feeds/home").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
    denied(() -> feeds.home(viewer, 51, null), 400);
    denied(() -> feeds.home(viewer, 0, null), 400);
  }
}
