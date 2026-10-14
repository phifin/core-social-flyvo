package com.flyvo.socialcore.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.graph.api.*;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.notification.api.*;
import com.flyvo.socialcore.notification.application.port.InboxRepository;
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
class NotificationsIT {
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
    volatile Instant now = Instant.parse("2026-10-14T09:00:00Z");

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
    TestClock noticeClock() {
      return new TestClock();
    }
  }

  @Autowired TestClock clock;
  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired ContentCommands posts;
  @Autowired ContentQueries content;
  @Autowired Interactions interactions;
  @Autowired GraphCommands graph;
  @Autowired CommunityCommands communities;
  @Autowired AdmissionCommands admissions;
  @Autowired NotificationQueries queries;
  @Autowired NotificationCommands commands;
  @Autowired DurableWorkStore worker;
  @Autowired AccessTokens tokens;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @MockitoSpyBean InboxRepository store;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    tx = new TransactionTemplate(manager);
    clock.now = Instant.parse("2026-10-14T09:00:00Z");
  }

  ActorContext user() {
    UUID id =
        tx.execute(
            s -> {
              UUID u = accounts.createVerified();
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

  ContentQueries.PostView post(
      ActorContext owner, String audience, List<ContentCommands.Mention> mentions) {
    return posts.publish(
        owner,
        new ContentCommands.Publish(
            "Synthetic mention and post",
            null,
            null,
            new ContentCommands.Audience(audience, List.of()),
            mentions,
            List.of()),
        null);
  }

  UUID event(UUID source, String type, long version) {
    return jdbc.queryForObject(
        "SELECT id FROM platform.outbox_event WHERE aggregate_id=? AND event_type=? AND aggregate_version=?",
        UUID.class,
        source,
        type,
        version);
  }

  void dispatch(UUID event) {
    jdbc.update(
        "UPDATE platform.outbox_delivery SET status='RETRY',available_at=?,lease_owner=NULL,lease_until=NULL WHERE event_id=? AND consumer_name='notification-inbox-v1'",
        clock.instant().minusSeconds(50L * 365 * 86400).atOffset(ZoneOffset.UTC),
        event);
    worker.consumeOne(UUID.randomUUID());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM platform.outbox_delivery WHERE event_id=? AND consumer_name='notification-inbox-v1'",
                String.class,
                event))
        .isEqualTo("COMPLETED");
  }

  NotificationQueries.Item item(ActorContext recipient) {
    return queries.inbox(recipient, 100, null).items().getFirst();
  }

  @Test
  void realWorkerDuplicateMultiActorAggregationAndCurrentActorFilters() {
    var owner = user();
    var a = user();
    var b = user();
    var c = user();
    var d = user();
    var p = post(owner, "PUBLIC", List.of());
    for (var actor : List.of(a, b, c, d)) {
      var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
      dispatch(event(r.id(), "ReactionChanged", r.version()));
    }
    var i = item(owner);
    assertThat(i.actorCount()).isEqualTo(4);
    assertThat(i.actors()).hasSize(3);
    assertThat(i.activityVersion()).isEqualTo(4);
    assertThat(queries.unread(owner)).isEqualTo(1);
    UUID duplicate =
        jdbc.queryForObject(
            "SELECT source_event_id FROM notification.contribution WHERE notification_id=? LIMIT 1",
            UUID.class,
            i.id());
    dispatch(duplicate);
    assertThat(item(owner).activityVersion()).isEqualTo(4);
    graph.block(owner, b.userId(), true);
    graph.mute(owner, c.userId(), true);
    assertThat(item(owner).actorCount()).isEqualTo(2);
    jdbc.update("UPDATE account.user_account SET status='SUSPENDED' WHERE id=?", d.userId());
    assertThat(item(owner).actors())
        .extracting(NotificationQueries.Actor::userId)
        .containsExactly(a.userId());
    graph.mute(owner, a.userId(), true);
    assertThat(queries.inbox(owner, 100, null).items()).isEmpty();
    assertThat(queries.unread(owner)).isZero();
  }

  @Test
  void crossHourTypeChangeDeletionReactivationAndStaleCreateNeverInflateUnread() {
    var owner = user();
    var actor = user();
    var p = post(owner, "PUBLIC", List.of());
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    var create = event(r.id(), "ReactionChanged", 0);
    dispatch(create);
    var first = item(owner);
    commands.read(owner, first.id(), first.activityVersion());
    clock.now = clock.now.plusSeconds(3601);
    var changed = interactions.react(actor, p.id(), null, Interactions.ReactionType.LOVE);
    dispatch(event(r.id(), "ReactionChanged", changed.version()));
    assertThat(item(owner).id()).isEqualTo(first.id());
    assertThat(item(owner).activityVersion()).isEqualTo(1);
    assertThat(queries.unread(owner)).isZero();
    interactions.unreact(actor, p.id(), null);
    dispatch(event(r.id(), "ReactionChanged", 2));
    assertThat(queries.unread(owner)).isZero();
    assertThat(queries.inbox(owner, 100, null).items()).isEmpty();
    clock.now = clock.now.plusSeconds(3601);
    var active = interactions.react(actor, p.id(), null, Interactions.ReactionType.SUPPORT);
    dispatch(event(r.id(), "ReactionChanged", active.version()));
    var next = item(owner);
    assertThat(next.id()).isNotEqualTo(first.id());
    assertThat(next.activityVersion()).isEqualTo(1);
    assertThat(queries.unread(owner)).isEqualTo(1);
    jdbc.update(
        "DELETE FROM platform.consumer_receipt WHERE event_id=? AND consumer_name='notification-inbox-v1'",
        create);
    dispatch(create);
    assertThat(item(owner).id()).isEqualTo(next.id());
    assertThat(queries.unread(owner)).isEqualTo(1);
  }

  @Test
  void reactivationBeforeInterveningDeleteMovesWindowAndPreservesNewUnread() {
    var owner = user();
    var actor = user();
    var p = post(owner, "PUBLIC", List.of());
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    dispatch(event(r.id(), "ReactionChanged", 0));
    var first = item(owner);
    commands.read(owner, first.id(), first.activityVersion());
    interactions.unreact(actor, p.id(), null);
    var deletion = event(r.id(), "ReactionChanged", 1);
    clock.now = clock.now.plusSeconds(7201);
    interactions.react(actor, p.id(), null, Interactions.ReactionType.LOVE);
    dispatch(event(r.id(), "ReactionChanged", 2));
    var current = item(owner);
    assertThat(current.id()).isNotEqualTo(first.id());
    assertThat(queries.unread(owner)).isEqualTo(1);
    dispatch(deletion);
    assertThat(item(owner).id()).isEqualTo(current.id());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.contribution WHERE notification_id=? AND active",
                Integer.class,
                first.id()))
        .isZero();
  }

  @Test
  void projectionFailureAndLostLeaseRollbackReceiptGroupContributionAndSourceState() {
    for (String mode : List.of("FAILURE", "LEASE")) {
      var owner = user();
      var actor = user();
      var p = post(owner, "PUBLIC", List.of());
      var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
      var e = event(r.id(), "ReactionChanged", 0);
      doAnswer(
              inv -> {
                Object result = inv.callRealMethod();
                if (mode.equals("FAILURE")) throw new IllegalStateException("TEST_FAILURE");
                jdbc.update(
                    "UPDATE platform.outbox_delivery SET lease_until=? WHERE event_id=?",
                    clock.instant().minusSeconds(1).atOffset(ZoneOffset.UTC),
                    e);
                return result;
              })
          .when(store)
          .apply(any(), any());
      try {
        jdbc.update(
            "UPDATE platform.outbox_delivery SET available_at=? WHERE event_id=?",
            clock.instant().minusSeconds(50L * 365 * 86400).atOffset(ZoneOffset.UTC),
            e);
        worker.consumeOne(UUID.randomUUID());
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?",
                    Integer.class,
                    e))
            .isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM notification.source_state WHERE recipient_id=?",
                    Integer.class,
                    owner.userId()))
            .isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM notification.notification WHERE recipient_id=?",
                    Integer.class,
                    owner.userId()))
            .isZero();
      } finally {
        reset(store);
      }
      dispatch(e);
      assertThat(queries.unread(owner)).isEqualTo(1);
    }
  }

  @Test
  void deleteDeliveredBeforeCreateAndConcurrentSourceVersionsUseOneRetainedState()
      throws Exception {
    var owner = user();
    var actor = user();
    var p = post(owner, "PUBLIC", List.of());
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    var create = event(r.id(), "ReactionChanged", 0);
    interactions.unreact(actor, p.id(), null);
    dispatch(event(r.id(), "ReactionChanged", 1));
    dispatch(create);
    assertThat(queries.inbox(owner, 100, null).items()).isEmpty();
    var active = interactions.react(actor, p.id(), null, Interactions.ReactionType.LOVE);
    var latest = event(r.id(), "ReactionChanged", active.version());
    // Two actual workers contend on independently claimed duplicate delivery effects.
    dispatch(latest);
    var row = item(owner);
    assertThat(row.activityVersion()).isEqualTo(1);
    jdbc.update(
        "UPDATE platform.outbox_delivery SET status='RETRY',available_at=? WHERE event_id=?",
        clock.instant().minusSeconds(50L * 365 * 86400).atOffset(ZoneOffset.UTC),
        latest);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> worker.consumeOne(UUID.randomUUID()));
      var b = pool.submit(() -> worker.consumeOne(UUID.randomUUID()));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    assertThat(item(owner).activityVersion()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT latest_source_version FROM notification.source_state WHERE recipient_id=? AND source_id=?",
                Long.class,
                owner.userId(),
                r.id()))
        .isEqualTo(2);
  }

  @Test
  void commentsRepliesMentionsSelfAndRecipientPrecedenceUseOwnerRoutes() {
    var owner = user();
    var actor = user();
    var p = post(owner, "PUBLIC", List.of(new ContentCommands.Mention(actor.userId(), 0, 9)));
    dispatch(event(p.id(), "PostCreated", 0));
    assertThat(item(actor).kind()).isEqualTo(NotificationQueries.Kind.MENTION);
    var root = interactions.create(owner, p.id(), null, "Synthetic root", null);
    dispatch(event(root.id(), "CommentCreated", 0));
    assertThat(queries.inbox(owner, 100, null).items()).isEmpty();
    var reply = interactions.create(actor, p.id(), root.id(), "Synthetic reply", null);
    dispatch(event(reply.id(), "CommentCreated", 0));
    assertThat(item(owner).kind()).isEqualTo(NotificationQueries.Kind.REPLY_TO_COMMENT);
    assertThat(queries.inbox(owner, 100, null).items()).hasSize(1);
    var comment = interactions.create(actor, p.id(), null, "Synthetic comment", null);
    dispatch(event(comment.id(), "CommentCreated", 0));
    assertThat(queries.inbox(owner, 100, null).items())
        .extracting(NotificationQueries.Item::kind)
        .contains(NotificationQueries.Kind.COMMENT_ON_POST);
    var own = interactions.react(owner, p.id(), null, Interactions.ReactionType.LIKE);
    dispatch(event(own.id(), "ReactionChanged", 0));
    assertThat(queries.inbox(owner, 100, null).items()).hasSize(2);
  }

  @Test
  void preferencesPrivateTargetsRemovedCommentsAndPostDeletionSuppressInboxAndBadge() {
    var owner = user();
    var actor = user();
    var hidden = post(owner, "ONLY_ME", List.of(new ContentCommands.Mention(actor.userId(), 0, 9)));
    dispatch(event(hidden.id(), "PostCreated", 0));
    assertThat(queries.inbox(actor, 100, null).items()).isEmpty();
    var p = post(owner, "PUBLIC", List.of());
    commands.preferences(owner, Map.of(NotificationQueries.Kind.COMMENT_ON_POST, false));
    var comment = interactions.create(actor, p.id(), null, "Synthetic comment", null);
    dispatch(event(comment.id(), "CommentCreated", 0));
    assertThat(queries.unread(owner)).isZero();
    commands.preferences(owner, Map.of(NotificationQueries.Kind.COMMENT_ON_POST, true));
    var next = interactions.create(actor, p.id(), null, "Synthetic comment two", null);
    dispatch(event(next.id(), "CommentCreated", 0));
    assertThat(queries.unread(owner)).isEqualTo(1);
    interactions.delete(actor, next.id(), 0);
    assertThat(queries.unread(owner)).isZero();
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    dispatch(event(r.id(), "ReactionChanged", 0));
    assertThat(queries.unread(owner)).isEqualTo(1);
    posts.audience(owner, p.id(), 0, new ContentCommands.Audience("ONLY_ME", List.of()));
    assertThat(queries.unread(owner)).isEqualTo(1); // owner still sees their own target
    posts.delete(owner, p.id(), 1);
    assertThat(queries.unread(owner)).isZero();
  }

  @Test
  void observedReadAndBulkSnapshotDoNotConsumeNewActivity() throws Exception {
    var owner = user();
    var actor = user();
    var newer = user();
    var p = post(owner, "PUBLIC", List.of());
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    dispatch(event(r.id(), "ReactionChanged", 0));
    var observed = item(owner);
    var second = interactions.react(newer, p.id(), null, Interactions.ReactionType.LIKE);
    dispatch(event(second.id(), "ReactionChanged", 0));
    commands.read(owner, observed.id(), observed.activityVersion());
    assertThat(queries.unread(owner)).isEqualTo(1);
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(
            inv -> {
              if (Thread.currentThread().getName().equals("notice-bulk")) {
                entered.countDown();
                if (!resume.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("TEST_TIMEOUT");
              }
              return inv.callRealMethod();
            })
        .when(store)
        .guard(eq(owner.userId()), any());
    try (var pool = Executors.newSingleThreadExecutor()) {
      var read =
          pool.submit(
              () -> {
                Thread.currentThread().setName("notice-bulk");
                commands.readAll(owner);
              });
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var third = user();
      var rr = interactions.react(third, p.id(), null, Interactions.ReactionType.LOVE);
      dispatch(event(rr.id(), "ReactionChanged", 0));
      resume.countDown();
      read.get(10, TimeUnit.SECONDS);
    } finally {
      resume.countDown();
      reset(store);
    }
    assertThat(item(owner).readVersion()).isEqualTo(2);
    assertThat(item(owner).activityVersion()).isEqualTo(3);
    assertThat(queries.unread(owner)).isEqualTo(1);
  }

  @Test
  void requestAndInvitationExpiryCurrentBlocksAndAcceptanceAreSourceBacked() {
    var requester = user();
    var recipient = user();
    var request = graph.request(requester, recipient.userId());
    UUID created =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE event_type='FriendRequestCreated' AND payload->>'requestId'=?",
            UUID.class,
            request.id().toString());
    dispatch(created);
    assertThat(item(recipient).kind()).isEqualTo(NotificationQueries.Kind.FRIEND_REQUEST);
    graph.respond(recipient, request.id(), "accept");
    var accepted =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE event_type='FriendshipEstablished' AND payload->>'requestId'=?",
            UUID.class,
            request.id().toString());
    dispatch(accepted);
    assertThat(queries.inbox(recipient, 100, null).items()).isEmpty();
    assertThat(item(requester).kind()).isEqualTo(NotificationQueries.Kind.FRIENDSHIP_ACCEPTED);
    var owner = user();
    var invitee = user();
    var community =
        communities.create(
            owner,
            "notice-" + UUID.randomUUID(),
            "Synthetic community",
            "",
            "PRIVATE",
            "INVITE_ONLY",
            null);
    var invitation = admissions.invite(owner, community.id(), invitee.userId(), null);
    var invited =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE event_type='CommunityInvitationCreated' AND payload->>'sourceId'=?",
            UUID.class,
            invitation.id().toString());
    dispatch(invited);
    assertThat(item(invitee).kind()).isEqualTo(NotificationQueries.Kind.COMMUNITY_INVITATION);
    clock.now = clock.now.plusSeconds(7L * 86400);
    assertThat(queries.unread(invitee)).isZero();
    graph.block(requester, recipient.userId(), true);
    assertThat(queries.unread(requester)).isZero();
  }

  @Test
  void retentionKeepsOrderingAndRejectsDeadReplayPastHorizon() {
    var owner = user();
    var actor = user();
    var p = post(owner, "PUBLIC", List.of());
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    var created = event(r.id(), "ReactionChanged", 0);
    dispatch(created);
    clock.now = clock.now.plusSeconds(91L * 86400);
    tx.executeWithoutResult(s -> store.prune(clock.instant()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.notification WHERE recipient_id=?",
                Integer.class,
                owner.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.source_state WHERE recipient_id=?",
                Integer.class,
                owner.userId()))
        .isEqualTo(1);
    jdbc.update("DELETE FROM platform.consumer_receipt WHERE event_id=?", created);
    dispatch(created);
    assertThat(queries.inbox(owner, 100, null).items()).isEmpty();
  }

  @Test
  void restRecipientIdorVersionsPreferenceValidationAndCursorTampering() throws Exception {
    var owner = user();
    var actor = user();
    var p = post(owner, "PUBLIC", List.of());
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    dispatch(event(r.id(), "ReactionChanged", 0));
    var i = item(owner);
    String bearer = "Bearer " + tokens.issue(owner);
    String other = "Bearer " + tokens.issue(actor);
    mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/notifications").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].actorCount").value(1));
    mvc.perform(
            put("/api/v1/notifications/" + i.id() + "/read")
                .header("Authorization", other)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType("application/json")
                .content("{\"activityVersion\":1}"))
        .andExpect(status().isNotFound());
    assertThatThrownBy(() -> commands.read(owner, i.id(), 100)).isInstanceOf(Failure.class);
    var cursor = queries.inbox(owner, 1, null).nextCursor();
    assertThat(cursor).isNotNull();
    assertThatThrownBy(() -> queries.inbox(actor, 1, cursor)).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> queries.inbox(owner, 2, cursor)).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> queries.inbox(owner, 1, "tampered" + cursor))
        .isInstanceOf(Failure.class);
    commands.read(owner, i.id(), 1);
    assertThat(queries.unread(owner)).isZero();
  }
}
