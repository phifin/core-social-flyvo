package com.flyvo.socialcore.graph;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.graph.api.*;
import com.flyvo.socialcore.graph.application.Relationships;
import com.flyvo.socialcore.graph.application.port.GraphRepository;
import com.flyvo.socialcore.graph.domain.UserPair;
import com.flyvo.socialcore.graph.infrastructure.jobs.FriendRequestExpiry;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.application.Profiles;
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
class GraphIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", POSTGRES::getJdbcUrl);
    r.add("app.database.username", POSTGRES::getUsername);
    r.add("app.database.password", POSTGRES::getPassword);
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-10T00:00:00Z");

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
    MutableClock graphClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired Relationships graph;
  @Autowired Profiles profiles;
  @Autowired AccountCommands commands;
  @Autowired AccountQueries accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokens tokens;
  @Autowired MockMvc mvc;
  @Autowired FriendRequestExpiry expiry;
  @Autowired DurableWorkStore work;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @MockitoSpyBean GraphRepository store;
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
              var u = commands.createVerified();
              profiles.provision(u);
              return u;
            });
    UUID sid = UUID.randomUUID();
    var now = clock.instant().atOffset(ZoneOffset.UTC);
    jdbc.update(
        "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        sid,
        id,
        now,
        now,
        now,
        now.plusDays(7),
        now.plusDays(30),
        now);
    return new ActorContext(id, sid);
  }

  void visibility(ActorContext a, String scope) {
    var p = profiles.visible(a, a.userId());
    profiles.change(a, p.version(), p.handle(), "Synthetic", "", scope);
  }

  RelationshipQueries.Facts facts(ActorContext a, ActorContext b) {
    return graph.facts(a.userId(), List.of(b.userId())).getFirst();
  }

  void deny(Runnable action, int status) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  long events(ActorContext a, ActorContext b) {
    var p = UserPair.of(a.userId(), b.userId());
    return count(
        "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=(SELECT id FROM graph.pair_revision WHERE low_user_id=? AND high_user_id=?)",
        p.low(),
        p.high());
  }

  void befriend(ActorContext a, ActorContext b) {
    graph.respond(b, graph.request(a, b.userId()).id(), "accept");
  }

  @Test
  void canonicalUuidOrderingMatchesPostgresIncludingSignBoundary() {
    var values =
        List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("80000000-0000-0000-0000-000000000000"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
    for (var a : values)
      for (var b : values)
        if (!a.equals(b)) {
          var p = UserPair.of(a, b);
          assertThat(jdbc.queryForObject("SELECT LEAST(?::uuid,?::uuid)", UUID.class, a, b))
              .isEqualTo(p.low());
          assertThat(UserPair.of(b, a).lockKey()).isEqualTo(p.lockKey());
        }
    assertThat(UserPair.of(values.get(0), values.get(1)).lockKey())
        .isNotEqualTo(UserPair.of(values.get(0), values.get(2)).lockKey());
  }

  @Test
  void largeGraphPointFactsUseIndexesAndDatabaseRejectsSelfEdges() {
    var a = user();
    var b = user();
    String seed = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO account.user_account(id,status,platform_role,created_at,updated_at) SELECT md5(? || i::text)::uuid,'ACTIVE','USER',now(),now() FROM generate_series(1,10000) i",
        seed);
    jdbc.update(
        "INSERT INTO graph.follow(follower_id,followed_id,created_at) SELECT ?,md5(? || i::text)::uuid,now() FROM generate_series(1,10000) i",
        a.userId(),
        seed);
    graph.follow(a, b.userId(), true);
    jdbc.execute("ANALYZE graph.follow");
    var plan =
        jdbc.queryForList(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT 1 FROM graph.follow WHERE follower_id=? AND followed_id=?",
            String.class,
            a.userId(),
            b.userId());
    assertThat(String.join("\n", plan))
        .contains("Index Cond:")
        .containsPattern("using follow_(pkey|reverse_idx)");
    assertThat(graph.ownEdges(a, "following", 100, null).items()).hasSize(100);
    assertThatThrownBy(
            () ->
                jdbc.update("INSERT INTO graph.follow VALUES (?,?,now())", a.userId(), a.userId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO graph.friend_request(id,requester_id,addressee_id,status,created_at,expires_at) VALUES (?,?,?,'PENDING',now(),now()+interval '30 days')",
                    UUID.randomUUID(),
                    a.userId(),
                    a.userId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void graphCursorsHideFilteredIdsRejectTamperingScopeChangesAndExpiry() {
    var a = user();
    var b = user();
    var c = user();
    graph.follow(a, b.userId(), true);
    graph.follow(a, c.userId(), true);
    var first = graph.ownEdges(a, "following", 1, null);
    assertThat(first.nextCursor()).doesNotContain(b.userId().toString(), c.userId().toString());
    deny(() -> graph.ownEdges(a, "friends", 1, first.nextCursor()), 400);
    deny(() -> graph.ownEdges(b, "following", 1, first.nextCursor()), 400);
    String altered =
        (first.nextCursor().startsWith("A") ? "B" : "A") + first.nextCursor().substring(1);
    deny(() -> graph.ownEdges(a, "following", 1, altered), 400);
    deny(() -> graph.ownEdges(a, "following", 1, "x".repeat(257)), 400);
    clock.now = clock.now.plusSeconds(900);
    deny(() -> graph.ownEdges(a, "following", 1, first.nextCursor()), 400);
  }

  @Test
  void allGraphHttpCommandsAndCurrentSessionRevocationAreOperational() throws Exception {
    var a = user();
    var b = user();
    String result =
        send(a, "POST", "/api/v1/friend-requests", "{\"userId\":\"" + b.userId() + "\"}", 200);
    UUID id =
        UUID.fromString(
            new tools.jackson.databind.ObjectMapper().readTree(result).path("id").asText());
    mvc.perform(
            get("/api/v1/me/friend-requests").header("Authorization", "Bearer " + tokens.issue(b)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(id.toString()));
    send(a, "POST", "/api/v1/friend-requests/" + id + "/accept", null, 404);
    send(b, "POST", "/api/v1/friend-requests/" + id + "/accept", null, 200);
    send(a, "DELETE", "/api/v1/me/friends/" + b.userId(), null, 204);
    var second = graph.request(a, b.userId());
    send(b, "POST", "/api/v1/friend-requests/" + second.id() + "/decline", null, 200);
    var third = graph.request(a, b.userId());
    send(a, "POST", "/api/v1/friend-requests/" + third.id() + "/cancel", null, 200);
    for (String kind : List.of("following", "mutes", "blocks")) {
      send(a, "PUT", "/api/v1/me/" + kind + "/" + b.userId(), null, 204);
      send(a, "DELETE", "/api/v1/me/" + kind + "/" + b.userId(), null, 204);
    }
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=now(),revoke_reason='TEST' WHERE id=?",
        a.sessionId());
    send(a, "PUT", "/api/v1/me/following/" + b.userId(), null, 401);
  }

  private String send(ActorContext actor, String method, String path, String body, int expected)
      throws Exception {
    var r =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + tokens.issue(actor))
            .header("Origin", "https://social-core.invalid")
            .with(csrf().asHeader());
    if (body != null) r.contentType("application/json").content(body);
    return mvc.perform(r)
        .andExpect(status().is(expected))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void followAndMuteAreIndependentIdempotentAndPrivateProfileDoesNotRequireApproval() {
    var a = user();
    var b = user();
    visibility(b, "PRIVATE");
    graph.follow(a, b.userId(), true);
    graph.follow(a, b.userId(), true);
    assertThat(facts(a, b).following()).isTrue();
    assertThat(facts(a, b).friends()).isFalse();
    assertThat(events(a, b)).isEqualTo(1);
    graph.mute(a, b.userId(), true);
    graph.mute(a, b.userId(), true);
    visibility(b, "PUBLIC");
    assertThat(profiles.visible(a, b.userId()).userId()).isEqualTo(b.userId());
    assertThat(facts(a, b).muted()).isTrue();
    assertThat(facts(b, a).muted()).isFalse();
    graph.follow(a, b.userId(), false);
    graph.follow(a, b.userId(), false);
    graph.mute(a, b.userId(), false);
    graph.mute(a, b.userId(), false);
    assertThat(events(a, b)).isEqualTo(4);
    graph.follow(a, b.userId(), true);
    var p = UserPair.of(a.userId(), b.userId());
    assertThat(
            count(
                "SELECT version FROM graph.pair_revision WHERE low_user_id=? AND high_user_id=?",
                p.low(),
                p.high()))
        .isEqualTo(5);
  }

  @Test
  void requestRolesOppositeRequestAndTerminalRetryDoNotSilentlyGrant() {
    var a = user();
    var b = user();
    var stranger = user();
    var r = graph.request(a, b.userId());
    assertThat(graph.request(a, b.userId()).id()).isEqualTo(r.id());
    deny(() -> graph.request(b, a.userId()), 409);
    deny(() -> graph.respond(a, r.id(), "accept"), 404);
    deny(() -> graph.respond(stranger, r.id(), "decline"), 404);
    deny(() -> graph.respond(b, r.id(), "cancel"), 404);
    graph.respond(b, r.id(), "accept");
    graph.respond(b, r.id(), "accept");
    assertThat(facts(a, b).friends()).isTrue();
    assertThat(facts(a, b).following()).isFalse();
    assertThat(events(a, b)).isEqualTo(2);
    graph.unfriend(a, b.userId());
    graph.unfriend(a, b.userId());
    graph.respond(b, r.id(), "accept");
    assertThat(facts(a, b).friends()).isFalse();
    assertThat(events(a, b)).isEqualTo(3);
    var next = graph.request(b, a.userId());
    graph.respond(a, next.id(), "decline");
    graph.respond(a, next.id(), "decline");
    var third = graph.request(a, b.userId());
    graph.respond(a, third.id(), "cancel");
    assertThat(facts(a, b).friends()).isFalse();
  }

  @Test
  void blockAtomicallyRemovesBothFollowsFriendshipAndPendingRequestsUnblockRestoresNothing() {
    var a = user();
    var b = user();
    befriend(a, b);
    graph.follow(a, b.userId(), true);
    graph.follow(b, a.userId(), true);
    graph.block(a, b.userId(), true);
    graph.block(a, b.userId(), true);
    assertThat(facts(a, b))
        .extracting(
            RelationshipQueries.Facts::following,
            RelationshipQueries.Facts::followedBy,
            RelationshipQueries.Facts::friends,
            RelationshipQueries.Facts::blocked)
        .containsExactly(false, false, false, true);
    deny(() -> graph.follow(b, a.userId(), true), 404);
    deny(() -> graph.request(b, a.userId()), 404);
    graph.block(a, b.userId(), false);
    graph.block(a, b.userId(), false);
    assertThat(facts(a, b).friends()).isFalse();
    var pending = graph.request(a, b.userId());
    graph.follow(a, b.userId(), true);
    graph.block(b, a.userId(), true);
    assertThat(store.request(pending.id()).status()).isEqualTo("CANCELLED");
    graph.block(b, a.userId(), false);
    deny(() -> graph.respond(b, pending.id(), "accept"), 409);
    assertThat(facts(a, b).following()).isFalse();
  }

  @Test
  void friendsProfileAndBilateralBlockApplyToSingleAndGraphReads() {
    var a = user();
    var b = user();
    var c = user();
    visibility(a, "FRIENDS");
    deny(() -> profiles.visible(b, a.userId()), 404);
    deny(() -> profiles.visible(ActorContext.anonymous(), a.userId()), 404);
    befriend(a, b);
    assertThat(profiles.visible(b, a.userId()).visibility()).isEqualTo("FRIENDS");
    graph.follow(a, c.userId(), true);
    assertThat(profiles.graph(b, a.userId(), "following", 20, null).items())
        .containsExactly(c.userId());
    graph.block(b, c.userId(), true);
    assertThat(profiles.graph(b, a.userId(), "following", 20, null).items()).isEmpty();
    visibility(a, "PUBLIC");
    visibility(b, "PUBLIC");
    graph.block(a, b.userId(), true);
    deny(() -> profiles.visible(b, a.userId()), 404);
    deny(() -> profiles.visible(a, b.userId()), 404);
    deny(() -> profiles.graph(b, a.userId(), "following", 20, null), 404);
    assertThat(profiles.visible(ActorContext.anonymous(), a.userId()).visibility())
        .isEqualTo("PUBLIC");
    assertThat(graph.ownEdges(a, "blocks", 20, null).items()).contains(b.userId());
    deny(() -> profiles.graph(c, a.userId(), "blocks", 20, null), 400);
  }

  @Test
  void expiryIsLiveWithoutWorkerReplacementAndDuplicateJobAreSafe() {
    var a = user();
    var b = user();
    var old = graph.request(a, b.userId());
    clock.now = old.expiresAt();
    deny(() -> graph.respond(b, old.id(), "accept"), 409);
    assertThat(graph.requests(b, "incoming", 20, null).items()).isEmpty();
    var replacement = graph.request(a, b.userId());
    assertThat(replacement.id()).isNotEqualTo(old.id());
    assertThat(store.request(old.id()).status()).isEqualTo("EXPIRED");
    tx.executeWithoutResult(s -> expiry.apply(old.id().toString()));
    assertThat(store.request(replacement.id()).status()).isEqualTo("PENDING");
    clock.now = replacement.expiresAt();
    for (int i = 0; i < 100 && store.request(replacement.id()).status().equals("PENDING"); i++)
      work.consumeJob(UUID.randomUUID(), List.of(expiry));
    assertThat(store.request(replacement.id()).status()).isEqualTo("EXPIRED");
    long n = events(a, b);
    tx.executeWithoutResult(s -> expiry.apply(replacement.id().toString()));
    assertThat(events(a, b)).isEqualTo(n);
  }

  @Test
  void listsAreBoundedDeterministicAndFilterSuspendedAccountsLive() {
    var a = user();
    var b = user();
    var c = user();
    graph.follow(a, b.userId(), true);
    graph.follow(a, c.userId(), true);
    var first = graph.ownEdges(a, "following", 1, null);
    var second = graph.ownEdges(a, "following", 1, first.nextCursor());
    assertThat(first.items()).hasSize(1);
    assertThat(second.items()).hasSize(1);
    assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
    var admin = user();
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", admin.userId());
    commands.suspend(admin, b.userId(), "TEST");
    assertThat(graph.ownEdges(a, "following", 100, null).items()).containsExactly(c.userId());
    deny(() -> graph.follow(a, b.userId(), true), 404);
    graph.follow(a, b.userId(), false);
    deny(() -> graph.ownEdges(b, "friends", 20, null), 401);
    deny(() -> graph.ownEdges(a, "following", 101, null), 400);
    deny(() -> graph.facts(a.userId(), Collections.nCopies(101, c.userId())), 400);
  }

  @Test
  void selfRelationsUnknownUsersAndTransactionRequiredGuardAreDenied() {
    var a = user();
    for (Runnable r :
        List.<Runnable>of(
            () -> graph.follow(a, a.userId(), true),
            () -> graph.block(a, a.userId(), true),
            () -> graph.mute(a, a.userId(), true),
            () -> graph.request(a, a.userId()),
            () -> graph.unfriend(a, a.userId()))) deny(r, 400);
    deny(() -> graph.follow(a, UUID.randomUUID(), true), 404);
    deny(() -> graph.follow(ActorContext.anonymous(), a.userId(), true), 401);
    assertThatThrownBy(() -> graph.guardInteractions(a, List.of(user().userId())))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  void sourceAndJobAndEventRollbackTogether() {
    var a = user();
    var b = user();
    var id =
        tx.execute(
            s -> {
              var r = graph.request(a, b.userId());
              s.setRollbackOnly();
              return r.id();
            });
    assertThat(store.request(id)).isNull();
    assertThat(events(a, b)).isZero();
    assertThat(count("SELECT count(*) FROM platform.job WHERE dedup_key=?", id.toString()))
        .isZero();
    graph.follow(a, b.userId(), true);
    tx.executeWithoutResult(
        s -> {
          graph.block(a, b.userId(), true);
          s.setRollbackOnly();
        });
    assertThat(facts(a, b).following()).isTrue();
    assertThat(facts(a, b).blocked()).isFalse();
    assertThat(events(a, b)).isEqualTo(1);
  }

  @Test
  void duplicateAcceptanceAndOppositeRequestsAndFollowBlockRacesHaveValidCommittedStates()
      throws Exception {
    var a = user();
    var b = user();
    var r = graph.request(a, b.userId());
    race(() -> graph.respond(b, r.id(), "accept"), () -> graph.respond(b, r.id(), "accept"));
    assertThat(events(a, b)).isEqualTo(2);
    var c = user();
    var d = user();
    race(() -> graph.request(c, d.userId()), () -> graph.request(d, c.userId()));
    assertThat(store.pending(UserPair.of(c.userId(), d.userId()))).isNotNull();
    assertThat(facts(c, d).friends()).isFalse();
    var e = user();
    var f = user();
    race(() -> graph.follow(e, f.userId(), true), () -> graph.block(f, e.userId(), true));
    assertThat(facts(e, f).blocked()).isTrue();
    assertThat(facts(e, f).following()).isFalse();
  }

  private void race(Runnable a, Runnable b) throws Exception {
    var gate = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var one = pool.submit(() -> racing(gate, a));
      var two = pool.submit(() -> racing(gate, b));
      one.get(10, TimeUnit.SECONDS);
      two.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  private void racing(CyclicBarrier gate, Runnable command) {
    try {
      gate.await(5, TimeUnit.SECONDS);
      command.run();
    } catch (Failure f) {
      assertThat(f.status()).isIn(404, 409);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @Test
  void sharedInteractionGuardSerializesBothOrdersAgainstBlock() throws Exception {
    for (boolean blockFirst : List.of(false, true)) {
      var a = user();
      var b = user();
      var p = UserPair.of(a.userId(), b.userId());
      var held = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var attempted = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(2);
      doAnswer(
              call -> {
                if (Thread.currentThread().getName().equals("graph-waiter")) attempted.countDown();
                return call.callRealMethod();
              })
          .when(store)
          .guard(eq(p.lockKey()), anyBoolean());
      try {
        var first =
            pool.submit(
                () ->
                    tx.executeWithoutResult(
                        s -> {
                          if (blockFirst) graph.block(a, b.userId(), true);
                          else {
                            accounts.guard(p.low(), false);
                            accounts.guard(p.high(), false);
                            graph.guardInteractions(a, List.of(b.userId()));
                          }
                          held.countDown();
                          await(release);
                        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var second =
            pool.submit(
                () -> {
                  Thread.currentThread().setName("graph-waiter");
                  if (blockFirst)
                    deny(
                        () ->
                            tx.executeWithoutResult(
                                s -> {
                                  accounts.guard(p.low(), false);
                                  accounts.guard(p.high(), false);
                                  graph.guardInteractions(a, List.of(b.userId()));
                                }),
                        404);
                  else graph.block(a, b.userId(), true);
                });
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.isDone()).isFalse();
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(facts(a, b).blocked()).isTrue();
      } finally {
        release.countDown();
        pool.shutdownNow();
        reset(store);
      }
    }
  }

  @Test
  void httpRoutesEnforceBearerCsrfJsonAndPrivacy() throws Exception {
    var a = user();
    var b = user();
    String token = tokens.issue(a);
    String path = "/api/v1/me/following/" + b.userId();
    mvc.perform(
            put(path)
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path)
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/me/graph/following").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0]").value(b.userId().toString()));
    mvc.perform(
            post("/api/v1/friend-requests")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content("{\"userId\":\"" + b.userId() + "\",\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isBadRequest());
    visibility(b, "PUBLIC");
    graph.block(b, a.userId(), true);
    mvc.perform(
            get("/api/v1/users/" + b.userId() + "/profile")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/users/" + b.userId() + "/graph/friends")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/me/friend-requests")).andExpect(status().isUnauthorized());
  }

  @Test
  void publishedViewsExposeOnlyFactsAndMatchObjectQueries() {
    var a = user();
    var b = user();
    graph.follow(a, b.userId(), true);
    befriend(a, b);
    graph.mute(a, b.userId(), true);
    assertThat(
            count(
                "SELECT count(*) FROM graph_api.follows_v1 WHERE follower_id=? AND followed_id=?",
                a.userId(),
                b.userId()))
        .isEqualTo(1);
    var p = UserPair.of(a.userId(), b.userId());
    assertThat(
            count(
                "SELECT count(*) FROM graph_api.friendships_v1 WHERE low_user_id=? AND high_user_id=?",
                p.low(),
                p.high()))
        .isEqualTo(1);
    graph.block(b, a.userId(), true);
    assertThat(facts(a, b).blocked())
        .isEqualTo(
            count(
                    "SELECT count(*) FROM graph_api.blocks_v1 WHERE blocker_id=? AND blocked_id=?",
                    b.userId(),
                    a.userId())
                == 1);
    for (var view :
        Map.of(
                "follows_v1",
                List.of("follower_id", "followed_id"),
                "friendships_v1",
                List.of("low_user_id", "high_user_id"),
                "blocks_v1",
                List.of("blocker_id", "blocked_id"),
                "mutes_v1",
                List.of("muter_id", "muted_id"))
            .entrySet())
      assertThat(
              jdbc.queryForList(
                  "SELECT column_name FROM information_schema.columns WHERE table_schema='graph_api' AND table_name=? ORDER BY ordinal_position",
                  String.class,
                  view.getKey()))
          .containsExactlyElementsOf(view.getValue());
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='account_api' AND table_name='active_users_v1'",
                String.class))
        .containsExactly("user_id");
  }
}
