package com.flyvo.socialcore.content;

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
import com.flyvo.socialcore.content.application.CommentRetention;
import com.flyvo.socialcore.content.application.port.InteractionRepository;
import com.flyvo.socialcore.content.domain.comment.Comment;
import com.flyvo.socialcore.graph.api.GraphCommands;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.moderation.application.CommunityPostModeration;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.application.Profiles;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
class InteractionsIT {
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

    public Clock withZone(ZoneId z) {
      return this;
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    MutableClock interactionClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired ContentCommands posts;
  @Autowired ContentQueries content;
  @Autowired Interactions interactions;
  @Autowired GraphCommands graph;
  @Autowired CommunityCommands communities;
  @Autowired MembershipCommands members;
  @Autowired MembershipQueries memberships;
  @Autowired CommunityPostModeration moderation;
  @Autowired CommentModeration commentsModeration;
  @Autowired CommentRetention retention;
  @Autowired com.flyvo.socialcore.content.application.PostRetention postRetention;
  @Autowired AccessTokens tokens;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @MockitoSpyBean InteractionRepository store;
  @MockitoSpyBean JdbcTemplate jdbc;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    tx = new TransactionTemplate(manager);
    clock.now = Instant.parse("2026-10-11T00:00:00Z");
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

  ContentQueries.PostView publish(
      ActorContext a, String audience, UUID community, List<UUID> recipients) {
    return posts.publish(
        a,
        new ContentCommands.Publish(
            "Synthetic post",
            null,
            community,
            new ContentCommands.Audience(audience, recipients),
            List.of(),
            List.of()),
        null);
  }

  ContentQueries.PostView publish(ActorContext a) {
    return publish(a, "PUBLIC", null, List.of());
  }

  CommunityCommands.Info community(ActorContext a, String visibility) {
    return communities.create(
        a, "c-" + UUID.randomUUID(), "Synthetic", "", visibility, "OPEN", null);
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  void denied(Runnable r, int status) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  void release(CountDownLatch latch) {
    try {
      assertThat(latch.await(8, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  void waiting(Future<?> waiter) {
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

  @Test
  void replyToReplyNormalizesRootAndRejectsCrossPostAndRawDepthViolation() {
    var a = user();
    var b = user();
    var p = publish(a);
    var other = publish(a);
    var root = interactions.create(a, p.id(), null, "root", null);
    var reply = interactions.create(b, p.id(), root.id(), "reply", null);
    var nested = interactions.create(a, p.id(), reply.id(), "nested", null);
    assertThat(nested.rootId()).isEqualTo(root.id());
    assertThat(nested.replyToUserId()).isEqualTo(b.userId());
    assertThat(interactions.comments(a, p.id(), root.id(), 100, null).items())
        .extracting(Interactions.CommentView::id)
        .containsExactlyInAnyOrder(reply.id(), nested.id());
    denied(() -> interactions.create(a, other.id(), root.id(), "wrong post", null), 404);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s ->
                        store.insert(
                            new Comment(
                                UUID.randomUUID(),
                                p.id(),
                                a.userId(),
                                reply.id(),
                                null,
                                "bad depth",
                                "VISIBLE",
                                clock.instant(),
                                null,
                                null,
                                null,
                                null,
                                0))))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE content.comment SET post_id=? WHERE id=?", other.id(), root.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void blockedAndDeletedRootsAreNonIdentifyingTombstonesOnlyWithVisibleChildren() {
    var author = user();
    var hidden = user();
    var visible = user();
    var viewer = user();
    var p = publish(author);
    var root = interactions.create(hidden, p.id(), null, "root private text", null);
    var child = interactions.create(visible, p.id(), root.id(), "visible child", null);
    var alone = interactions.create(hidden, p.id(), null, "hidden alone", null);
    graph.block(viewer, hidden.userId(), true);
    var tomb = interactions.readComment(viewer, root.id());
    assertThat(tomb.tombstone()).isTrue();
    assertThat(tomb.authorId()).isNull();
    assertThat(tomb.displayName()).isNull();
    assertThat(tomb.body()).isNull();
    assertThat(tomb.version()).isNull();
    assertThat(tomb.replyToUserId()).isNull();
    denied(() -> interactions.readComment(viewer, alone.id()), 404);
    var children = interactions.comments(viewer, p.id(), root.id(), 20, null).items();
    assertThat(children).hasSize(1);
    assertThat(children.getFirst().id()).isEqualTo(child.id());
    assertThat(children.getFirst().replyToUserId()).isNull();
    denied(() -> interactions.create(viewer, p.id(), root.id(), "blocked reply", null), 404);
    graph.block(viewer, hidden.userId(), false);
    interactions.delete(hidden, root.id(), root.version());
    assertThat(interactions.readComment(viewer, root.id()).tombstone()).isTrue();
    assertThat(interactions.comments(viewer, p.id(), root.id(), 20, null).items()).hasSize(1);
    denied(() -> interactions.react(viewer, null, root.id(), Interactions.ReactionType.LIKE), 404);
    interactions.delete(visible, child.id(), child.version());
    denied(() -> interactions.readComment(viewer, root.id()), 404);
  }

  @Test
  void parentAudienceMatrixGatesEveryCommentAndReactionPath() {
    var a = user();
    var b = user();
    var outsider = user();
    var anon = ActorContext.anonymous();
    for (var audience : List.of("PUBLIC", "FOLLOWERS", "FRIENDS", "CUSTOM", "ONLY_ME")) {
      if (audience.equals("FOLLOWERS")) graph.follow(b, a.userId(), true);
      if (audience.equals("FRIENDS")) graph.respond(a, graph.request(b, a.userId()).id(), "accept");
      var p =
          publish(a, audience, null, audience.equals("CUSTOM") ? List.of(b.userId()) : List.of());
      var c = interactions.create(a, p.id(), null, "source", null);
      interactions.react(a, p.id(), null, Interactions.ReactionType.LOVE);
      interactions.react(a, null, c.id(), Interactions.ReactionType.LIKE);
      if (!audience.equals("ONLY_ME")) {
        assertThat(interactions.readComment(b, c.id()).body()).isEqualTo("source");
        assertThat(interactions.reactions(b, p.id(), null, 20, null).counts())
            .containsEntry(Interactions.ReactionType.LOVE, 1L);
      } else denied(() -> interactions.create(b, p.id(), null, "denied", null), 404);
      if (!audience.equals("PUBLIC")) {
        for (var v : List.of(outsider, anon)) {
          denied(() -> interactions.readComment(v, c.id()), 404);
          denied(() -> interactions.comments(v, p.id(), null, 20, null), 404);
          denied(() -> interactions.reactions(v, p.id(), null, 20, null), 404);
          denied(() -> interactions.reactions(v, null, c.id(), 20, null), 404);
          assertThat(interactions.engagement(v, List.of(p.id()))).isEmpty();
        }
      }
      graph.block(b, a.userId(), true);
      denied(() -> interactions.readComment(b, c.id()), 404);
      denied(() -> interactions.react(b, p.id(), null, Interactions.ReactionType.LIKE), 404);
      graph.block(b, a.userId(), false);
    }
  }

  @Test
  void publicNonmemberAndPrivateDepartedMemberCannotInteract() {
    var a = user();
    var b = user();
    for (var visibility : List.of("PUBLIC", "PRIVATE")) {
      var community = community(a, visibility);
      var p = publish(a, "COMMUNITY", community.id(), List.of());
      var c = interactions.create(a, p.id(), null, "root", null);
      if (visibility.equals("PUBLIC"))
        assertThat(interactions.readComment(b, c.id()).body()).isEqualTo("root");
      else denied(() -> interactions.readComment(b, c.id()), 404);
      denied(() -> interactions.create(b, p.id(), null, "outsider", null), 403);
      denied(() -> interactions.react(b, p.id(), null, Interactions.ReactionType.LIKE), 403);
      var m = members.join(b, community.id());
      var own = interactions.create(b, p.id(), null, "member", null);
      members.leave(b, community.id(), m.version());
      denied(() -> interactions.edit(b, own.id(), own.version(), "lost permission"), 403);
      denied(() -> interactions.unreact(b, null, c.id()), 403);
      if (visibility.equals("PRIVATE")) {
        denied(() -> interactions.readComment(b, c.id()), 404);
        assertThat(interactions.engagement(b, List.of(p.id()))).isEmpty();
      }
    }
  }

  @Test
  void reactionsPreserveStableIdentityVersionsAndNoOpEffectsAcrossRecreation() {
    var a = user();
    var p = publish(a);
    var first = interactions.react(a, p.id(), null, Interactions.ReactionType.LIKE);
    assertThat(interactions.react(a, p.id(), null, Interactions.ReactionType.LIKE))
        .isEqualTo(first);
    var replaced = interactions.react(a, p.id(), null, Interactions.ReactionType.LOVE);
    assertThat(replaced.id()).isEqualTo(first.id());
    assertThat(replaced.version()).isEqualTo(1);
    interactions.unreact(a, p.id(), null);
    interactions.unreact(a, p.id(), null);
    var recreated = interactions.react(a, p.id(), null, Interactions.ReactionType.SUPPORT);
    assertThat(recreated.id()).isEqualTo(first.id());
    assertThat(recreated.version()).isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM content.reaction WHERE actor_id=? AND post_id=?",
                a.userId(),
                p.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT aggregate_version FROM platform.outbox_event WHERE aggregate_id=? ORDER BY aggregate_version",
                Long.class,
                first.id()))
        .containsExactly(0L, 1L, 2L, 3L);
    var events =
        jdbc.queryForList(
            "SELECT payload::text FROM platform.outbox_event WHERE aggregate_id=? ORDER BY aggregate_version",
            String.class,
            first.id());
    assertThat(events.get(1)).contains("LOVE", "LIKE");
    assertThat(events.get(2)).contains("\"type\": null");
    assertThat(events.get(3)).contains("SUPPORT", "\"previousType\": null");
    // The producer keeps the same aggregate identity and total sequence through deletion.
    // Real delivery/receipt retry is covered by AuthenticationIT; social consumers are Phase 7.
    assertThat(
            count(
                "SELECT count(DISTINCT aggregate_id) FROM platform.outbox_event WHERE aggregate_id=?",
                first.id()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE content.reaction SET post_id=? WHERE id=?",
                    publish(a).id(),
                    first.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void countsAndActorsFilterBilateralBlocksInactiveActorsAndInactiveTombstones() {
    var author = user();
    var viewer = user();
    var blocked = user();
    var inactive = user();
    var removed = user();
    var p = publish(author);
    for (var a : List.of(viewer, blocked, inactive, removed)) {
      interactions.react(a, p.id(), null, Interactions.ReactionType.LIKE);
      interactions.create(a, p.id(), null, "comment", null);
    }
    interactions.unreact(removed, p.id(), null);
    graph.block(blocked, viewer.userId(), true);
    jdbc.update(
        "UPDATE account.user_account SET status='SUSPENDED',version=version+1 WHERE id=?",
        inactive.userId());
    var page = interactions.reactions(viewer, p.id(), null, 100, null);
    assertThat(page.counts()).containsExactlyEntriesOf(Map.of(Interactions.ReactionType.LIKE, 1L));
    assertThat(page.items())
        .extracting(Interactions.ActorReaction::actorId)
        .containsExactly(viewer.userId());
    var engagement = interactions.engagement(viewer, List.of(p.id())).get(p.id());
    assertThat(engagement.reactions()).isEqualTo(page.counts());
    assertThat(engagement.comments()).isEqualTo(2);
    graph.mute(viewer, author.userId(), true);
    assertThat(interactions.reactions(viewer, p.id(), null, 100, null).counts())
        .isEqualTo(page.counts());
    jdbc.update(
        "UPDATE account.user_account SET status='SUSPENDED',version=version+1 WHERE id=?",
        author.userId());
    denied(() -> interactions.reactions(viewer, p.id(), null, 20, null), 404);
    assertThat(interactions.engagement(viewer, List.of(p.id()))).isEmpty();
  }

  @Test
  void sameTimestampPagesHaveProgressAndOpaqueViewerRootBoundCursors() {
    var a = user();
    var b = user();
    var p = publish(a);
    for (var i = 0; i < 5; i++) interactions.create(a, p.id(), null, "c" + i, null);
    var seen = new HashSet<UUID>();
    String cursor = null;
    for (var i = 0; i < 4; i++) {
      var page = interactions.comments(a, p.id(), null, 2, cursor);
      for (var c : page.items()) assertThat(seen.add(c.id())).isTrue();
      cursor = page.nextCursor();
      if (cursor == null) break;
    }
    assertThat(seen).hasSize(5);
    assertThat(cursor).isNull();
    var after = interactions.comments(a, p.id(), null, 1, null).nextCursor();
    denied(() -> interactions.comments(b, p.id(), null, 1, after), 400);
    denied(() -> interactions.comments(a, p.id(), null, 1, after + "bad"), 400);
    clock.now = clock.now.plusSeconds(1801);
    denied(() -> interactions.comments(a, p.id(), null, 1, after), 400);
  }

  @Test
  void hiddenRootBatchCanBeEmptyAndStillContinueToVisibleRoot() {
    var a = user();
    var b = user();
    var hidden = user();
    var p = publish(a);
    var first = interactions.create(hidden, p.id(), null, "hidden", null);
    clock.now = clock.now.plusSeconds(1);
    var last = interactions.create(a, p.id(), null, "visible", null);
    graph.block(b, hidden.userId(), true);
    var page = interactions.comments(b, p.id(), null, 1, null);
    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNotNull();
    assertThat(interactions.comments(b, p.id(), null, 1, page.nextCursor()).items())
        .extracting(Interactions.CommentView::id)
        .containsExactly(last.id());
    assertThat(first.id()).isNotEqualTo(last.id());
  }

  @Test
  void editsDeleteAndIdempotencyRecheckAuthorizationAndRollbackMandatoryAudit() {
    var a = user();
    var b = user();
    var p = publish(a);
    var c = interactions.create(a, p.id(), null, "initial", "comment-key");
    assertThat(interactions.create(a, p.id(), null, "initial", "comment-key").id())
        .isEqualTo(c.id());
    denied(() -> interactions.create(a, p.id(), null, "different", "comment-key"), 409);
    denied(() -> interactions.edit(b, c.id(), 0, "foreign"), 403);
    denied(() -> interactions.edit(a, c.id(), 9, "stale"), 412);
    var changed = interactions.edit(a, c.id(), 0, "edited");
    assertThat(changed.version()).isEqualTo(1);
    assertThat(changed.editedAt()).isNotNull();
    doAnswer(
            call -> {
              if (((String) call.getArgument(0)).startsWith("INSERT INTO platform.audit_entry")
                  && Arrays.asList(call.getArguments()).contains("COMMENT_DELETED"))
                throw new IllegalStateException("synthetic audit outage");
              return call.callRealMethod();
            })
        .when(jdbc)
        .update(anyString(), any(Object[].class));
    assertThatThrownBy(() -> interactions.delete(a, c.id(), 1))
        .isInstanceOf(IllegalStateException.class);
    assertThat(interactions.readComment(a, c.id()).body()).isEqualTo("edited");
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='CommentDeleted'",
                c.id()))
        .isZero();
    reset(jdbc);
    graph.block(a, b.userId(), true);
    denied(() -> interactions.create(b, p.id(), null, "hidden", "key"), 404);
  }

  @Test
  void deletedCommentRetentionIsDurableBoundedAndIdempotent() {
    var a = user();
    var p = publish(a);
    var c = interactions.create(a, p.id(), null, "private deleted body", null);
    interactions.delete(a, c.id(), 0);
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE kind='COMMENT_RETENTION' AND payload->>'data'=?",
                c.id().toString()))
        .isEqualTo(1);
    tx.executeWithoutResult(s -> retention.apply(c.id().toString()));
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM content.comment WHERE id=?", String.class, c.id()))
        .isEqualTo("private deleted body");
    clock.now = clock.now.plusSeconds(30L * 86400);
    tx.executeWithoutResult(s -> retention.apply(c.id().toString()));
    tx.executeWithoutResult(s -> retention.apply(c.id().toString()));
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM content.comment WHERE id=?", String.class, c.id()))
        .isEmpty();
    assertThat(count("SELECT version FROM content.comment WHERE id=?", c.id())).isEqualTo(1);
  }

  @Test
  void concurrentSameReactionHasOneRowAndOneCreationEvent() throws Exception {
    var a = user();
    var p = publish(a);
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Interactions.ReactionView> command =
          () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return interactions.react(a, p.id(), null, Interactions.ReactionType.LOVE);
          };
      var one = pool.submit(command);
      var two = pool.submit(command);
      assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
    }
    assertThat(count("SELECT count(*) FROM content.reaction WHERE post_id=?", p.id())).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id IN (SELECT id FROM content.reaction WHERE post_id=?)",
                p.id()))
        .isEqualTo(1);
  }

  @Test
  void commentFirstSerializesPostDeletionAndBlockAndMembershipRevocation() throws Exception {
    for (var conflict : List.of("DELETE", "BLOCK", "MEMBERSHIP", "REMOVE")) {
      var author = user();
      var actor = user();
      var c = community(author, "PRIVATE");
      var m = members.join(actor, c.id());
      var p = publish(author, "COMMUNITY", c.id(), List.of());
      var held = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      doAnswer(
              call -> {
                var result = call.callRealMethod();
                var value = (Comment) call.getArgument(0);
                if (value.post().equals(p.id())) {
                  held.countDown();
                  release(finish);
                }
                return result;
              })
          .when(store)
          .insert(any());
      try (var pool = Executors.newFixedThreadPool(2)) {
        var writer =
            pool.submit(
                () ->
                    interactions.create(actor, p.id(), null, "committed before revocation", null));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var mutation =
            pool.submit(
                () -> {
                  switch (conflict) {
                    case "DELETE" -> posts.delete(author, p.id(), 0);
                    case "BLOCK" -> graph.block(author, actor.userId(), true);
                    case "MEMBERSHIP" ->
                        members.remove(author, c.id(), actor.userId(), m.version());
                    case "REMOVE" ->
                        moderation.apply(author, c.id(), p.id(), 0, "REMOVE_CONTENT", "Synthetic");
                  }
                  return true;
                });
        try {
          waiting(mutation);
        } finally {
          finish.countDown();
        }
        var comment = writer.get(10, TimeUnit.SECONDS);
        mutation.get(10, TimeUnit.SECONDS);
        denied(() -> interactions.readComment(actor, comment.id()), 404);
      } finally {
        finish.countDown();
        reset(store);
      }
    }
  }

  @Test
  void blockAndRevocationFirstDenyLaterInteractionAfterActualLockWait() throws Exception {
    for (var conflict : List.of("BLOCK", "MEMBERSHIP", "DELETE", "REMOVE")) {
      var author = user();
      var actor = user();
      var c = community(author, "PRIVATE");
      var m = members.join(actor, c.id());
      var p = publish(author, "COMMUNITY", c.id(), List.of());
      var held = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var mutation =
            pool.submit(
                () ->
                    tx.execute(
                        s -> {
                          switch (conflict) {
                            case "BLOCK" -> graph.block(author, actor.userId(), true);
                            case "MEMBERSHIP" ->
                                members.remove(author, c.id(), actor.userId(), m.version());
                            case "DELETE" -> posts.delete(author, p.id(), 0);
                            case "REMOVE" ->
                                moderation.apply(
                                    author, c.id(), p.id(), 0, "REMOVE_CONTENT", "Synthetic");
                          }
                          held.countDown();
                          release(finish);
                          return true;
                        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var writer =
            pool.submit(
                () -> {
                  denied(
                      () -> interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE),
                      conflict.equals("MEMBERSHIP") ? 403 : 404);
                  return true;
                });
        try {
          waiting(writer);
        } finally {
          finish.countDown();
        }
        mutation.get(10, TimeUnit.SECONDS);
        writer.get(10, TimeUnit.SECONDS);
        assertThat(count("SELECT count(*) FROM content.reaction WHERE post_id=?", p.id())).isZero();
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  void commentReactionFirstSerializesCommentDeletionWithoutLockUpgrade() throws Exception {
    var author = user();
    var actor = user();
    var p = publish(author);
    var c = interactions.create(author, p.id(), null, "root", null);
    var held = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            call -> {
              var result = call.callRealMethod();
              if (c.id().equals(call.getArgument(0))) {
                held.countDown();
                release(finish);
              }
              return result;
            })
        .when(store)
        .lockComment(eq(c.id()), eq(true));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var writer =
          pool.submit(
              () -> interactions.react(actor, null, c.id(), Interactions.ReactionType.LIKE));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      // Pause only the first holder; the deleter must then complete normally.
      doCallRealMethod().when(store).lockComment(eq(c.id()), eq(true));
      var deletion =
          pool.submit(
              () -> {
                interactions.delete(author, c.id(), 0);
                return true;
              });
      try {
        waiting(deletion);
      } finally {
        finish.countDown();
      }
      writer.get(10, TimeUnit.SECONDS);
      deletion.get(10, TimeUnit.SECONDS);
      denied(() -> interactions.reactions(actor, null, c.id(), 20, null), 404);
    } finally {
      finish.countDown();
      reset(store);
    }
  }

  @Test
  void commentStatementSnapshotCannotCombineOldCustomGrantWithNewPrivateText() throws Exception {
    var author = user();
    var viewer = user();
    var p = publish(author, "CUSTOM", null, List.of(viewer.userId()));
    var c = interactions.create(author, p.id(), null, "previous allowed text", null);
    var held = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            call -> {
              var result = call.callRealMethod();
              if (viewer.userId().equals(call.getArgument(0))
                  && c.id().equals(call.getArgument(3))) {
                held.countDown();
                release(finish);
              }
              return result;
            })
        .when(store)
        .comments(any(), eq(p.id()), isNull(), eq(c.id()), anyInt(), any(), isNull(), any());
    try (var pool = Executors.newSingleThreadExecutor()) {
      var read = pool.submit(() -> interactions.readComment(viewer, c.id()));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      posts.audience(
          author, p.id(), p.version(), new ContentCommands.Audience("ONLY_ME", List.of()));
      interactions.edit(author, c.id(), 0, "new private text");
      finish.countDown();
      assertThat(read.get(10, TimeUnit.SECONDS).body()).isEqualTo("previous allowed text");
      denied(() -> interactions.readComment(viewer, c.id()), 404);
    } finally {
      finish.countDown();
      reset(store);
    }
  }

  @Test
  void commentDeletionFirstDeniesConcurrentReactionWithoutCreatingSource() throws Exception {
    var author = user();
    var actor = user();
    var p = publish(author);
    var c = interactions.create(author, p.id(), null, "root", null);
    var held = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var deletion =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        interactions.delete(author, c.id(), 0);
                        held.countDown();
                        release(finish);
                        return true;
                      }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      var reaction =
          pool.submit(
              () -> {
                denied(
                    () -> interactions.react(actor, null, c.id(), Interactions.ReactionType.LIKE),
                    404);
                return true;
              });
      try {
        waiting(reaction);
      } finally {
        finish.countDown();
      }
      deletion.get(10, TimeUnit.SECONDS);
      reaction.get(10, TimeUnit.SECONDS);
      assertThat(count("SELECT count(*) FROM content.reaction WHERE comment_id=?", c.id()))
          .isZero();
    } finally {
      finish.countDown();
    }
  }

  @Test
  void scopedCommentOwnerApiDeniesWrongScopeAndCannotRestoreAuthorDeletion() {
    var owner = user();
    var author = user();
    var viewer = user();
    var c = community(owner, "PUBLIC");
    var other = community(owner, "PUBLIC");
    members.join(author, c.id());
    members.join(viewer, c.id());
    var p = publish(owner, "COMMUNITY", c.id(), List.of());
    var root = interactions.create(author, p.id(), null, "root text", null);
    interactions.create(viewer, p.id(), root.id(), "child", null);
    denied(
        () ->
            tx.executeWithoutResult(
                s ->
                    commentsModeration.applyComment(
                        owner, other.id(), root.id(), 0, "REMOVE_CONTENT", UUID.randomUUID())),
        404);
    denied(
        () ->
            tx.executeWithoutResult(
                s ->
                    commentsModeration.applyComment(
                        viewer, c.id(), root.id(), 0, "REMOVE_CONTENT", UUID.randomUUID())),
        403);
    graph.block(owner, author.userId(), true);
    var change =
        tx.execute(
            s ->
                commentsModeration.applyComment(
                    owner, c.id(), root.id(), 0, "REMOVE_CONTENT", UUID.randomUUID()));
    assertThat(change.after()).isEqualTo("REMOVED");
    assertThat(interactions.readComment(viewer, root.id()).tombstone()).isTrue();
    tx.executeWithoutResult(
        s ->
            commentsModeration.applyComment(
                owner, c.id(), root.id(), 1, "RESTORE_CONTENT", UUID.randomUUID()));
    assertThat(interactions.readComment(viewer, root.id()).body()).isEqualTo("root text");
    graph.block(owner, author.userId(), false);
    interactions.delete(author, root.id(), 2);
    denied(
        () ->
            tx.executeWithoutResult(
                s ->
                    commentsModeration.applyComment(
                        owner, c.id(), root.id(), 3, "RESTORE_CONTENT", UUID.randomUUID())),
        409);
  }

  @Test
  void invalidBoundsAnonymousMutationsAndInactiveViewerFailWithoutEffects() {
    var a = user();
    var p = publish(a);
    long before = count("SELECT count(*) FROM content.comment WHERE post_id=?", p.id());
    denied(() -> interactions.create(a, p.id(), null, " ", null), 400);
    denied(() -> interactions.create(a, p.id(), null, "x".repeat(5001), null), 400);
    denied(
        () -> interactions.create(ActorContext.anonymous(), p.id(), null, "anonymous", null), 401);
    denied(() -> interactions.react(a, p.id(), null, null), 400);
    denied(() -> interactions.comments(a, p.id(), null, 101, null), 400);
    denied(() -> interactions.reactions(a, p.id(), null, 0, null), 400);
    assertThat(count("SELECT count(*) FROM content.comment WHERE post_id=?", p.id()))
        .isEqualTo(before);
    jdbc.update(
        "UPDATE account.user_account SET status='SUSPENDED',version=version+1 WHERE id=?",
        a.userId());
    denied(() -> interactions.engagement(a, List.of(p.id())), 401);
    denied(() -> interactions.comments(a, p.id(), null, 20, null), 401);
  }

  @Test
  void deletedParentRetentionRedactsDescendantsAndRetainsMonotonicTombstones() {
    var a = user();
    var b = user();
    var p = publish(a);
    var root = interactions.create(b, p.id(), null, "child text", null);
    var reply = interactions.create(a, p.id(), root.id(), "reply text", null);
    posts.delete(a, p.id(), 0);
    clock.now = clock.now.plusSeconds(30L * 86400);
    tx.executeWithoutResult(s -> postRetention.apply(p.id().toString()));
    tx.executeWithoutResult(s -> postRetention.apply(p.id().toString()));
    assertThat(
            count(
                "SELECT count(*) FROM content.comment WHERE post_id=? AND body='' AND status='DELETED' AND version=1",
                p.id()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id IN (?,?) AND event_type='CommentDeleted'",
                root.id(),
                reply.id()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE target_id IN (?,?) AND action='COMMENT_PARENT_RETENTION'",
                root.id(),
                reply.id()))
        .isEqualTo(2);
  }

  @Test
  void batchEngagementStatementCountDoesNotGrowPerPost() {
    var a = user();
    var ids = new ArrayList<UUID>();
    for (var i = 0; i < 100; i++) {
      var p = publish(a);
      ids.add(p.id());
      interactions.create(a, p.id(), null, "comment", null);
      interactions.react(a, p.id(), null, Interactions.ReactionType.LIKE);
    }
    var statements = new AtomicInteger();
    doAnswer(
            call -> {
              statements.incrementAndGet();
              return call.callRealMethod();
            })
        .when(jdbc)
        .query(
            anyString(),
            any(org.springframework.jdbc.core.RowCallbackHandler.class),
            any(Object[].class));
    assertThat(interactions.engagement(a, ids.subList(0, 1))).hasSize(1);
    int one = statements.getAndSet(0);
    assertThat(interactions.engagement(a, ids)).hasSize(100);
    assertThat(statements.get()).isEqualTo(one);
    assertThat(one).isEqualTo(1);
  }

  @Test
  void httpUsesTrustedActorStrongVersionsCsrfAndRejectsPrivilegeFields() throws Exception {
    var a = user();
    var p = publish(a);
    String bearer = "Bearer " + tokens.issue(a);
    String route = "/api/v1/posts/" + p.id() + "/comments";
    mvc.perform(
            post(route)
                .header("Authorization", bearer)
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content("{\"body\":\"http\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(route)
                .with(csrf().asHeader())
                .header("Authorization", bearer)
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content("{\"body\":\"http\",\"authorId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
    var c = interactions.create(a, p.id(), null, "http", null);
    mvc.perform(get("/api/v1/comments/" + c.id()))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"0\""));
    mvc.perform(
            patch("/api/v1/comments/" + c.id())
                .with(csrf().asHeader())
                .header("Authorization", bearer)
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content("{\"body\":\"updated\"}"))
        .andExpect(status().isPreconditionRequired());
    mvc.perform(
            put("/api/v1/comments/" + c.id() + "/reactions/me")
                .with(csrf().asHeader())
                .header("Authorization", bearer)
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content("{\"type\":\"LOVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("version").value(0));
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=now(),revoke_reason='LOGOUT' WHERE id=?",
        a.sessionId());
    mvc.perform(get("/api/v1/comments/" + c.id()).header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }
}
