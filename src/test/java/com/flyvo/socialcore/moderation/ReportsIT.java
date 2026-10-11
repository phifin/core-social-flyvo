package com.flyvo.socialcore.moderation;

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
import com.flyvo.socialcore.graph.api.GraphCommands;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.moderation.api.Reports;
import com.flyvo.socialcore.moderation.application.port.ReportRepository;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.application.Profiles;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class ReportsIT {
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

  @Autowired Reports reports;
  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired ContentCommands posts;
  @Autowired Interactions comments;
  @Autowired CommunityCommands communities;
  @Autowired MembershipCommands members;
  @Autowired MembershipQueries memberships;
  @Autowired GraphCommands graph;
  @Autowired AccessTokens tokens;
  @Autowired MockMvc mvc;
  @Autowired Clock clock;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @MockitoSpyBean ReportRepository store;
  @MockitoSpyBean JdbcTemplate jdbc;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
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

  ActorContext admin() {
    var a = user();
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", a.userId());
    return a;
  }

  CommunityCommands.Info community(ActorContext a) {
    return communities.create(
        a, "r-" + UUID.randomUUID(), "Synthetic scope", "", "PRIVATE", "OPEN", null);
  }

  ContentQueries.PostView publish(ActorContext a, UUID community) {
    return posts.publish(
        a,
        new ContentCommands.Publish(
            "Synthetic report evidence",
            null,
            community,
            new ContentCommands.Audience(community == null ? "PUBLIC" : "COMMUNITY", List.of()),
            List.of(),
            List.of()),
        null);
  }

  Reports.Submit input(Reports.Target t, UUID id) {
    return new Reports.Submit(t, id, Reports.Category.SPAM, "Synthetic private detail");
  }

  void privacy(ActorContext target, String visibility, String bio) {
    var p = profiles.visible(target, target.userId());
    profiles.change(target, p.version(), p.handle(), p.displayName(), bio, visibility);
  }

  Reports.View report(ActorContext a, Reports.Target t, UUID id) {
    return reports.submit(a, input(t, id), null);
  }

  Reports.Resolution resolution(Reports.View r, Reports.Action a, Long version) {
    return new Reports.Resolution(r.id(), a, "Synthetic moderation reason", version, null);
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  void denied(Runnable r, int status) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  void release(CountDownLatch l) {
    try {
      assertThat(l.await(12, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  void waiting(Future<?> f) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(
                      count(
                          "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()"))
                  .isPositive();
              assertThat(f.isDone()).isFalse();
            });
  }

  @Test
  void guardedUserProofRefreshesAnEarlierManagedProfileInsteadOfUsingOldPrivacyGrant()
      throws Exception {
    var target = user();
    var actor = user();
    var held = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var submission =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        profiles.visible(actor, target.userId());
                        held.countDown();
                        release(finish);
                        denied(() -> report(actor, Reports.Target.USER, target.userId()), 404);
                        return true;
                      }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      privacy(target, "PRIVATE", "New private text");
      finish.countDown();
      // The denied nested command marks its outer transaction rollback-only.
      assertThatThrownBy(() -> submission.get(15, TimeUnit.SECONDS))
          .hasCauseInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    } finally {
      finish.countDown();
    }
    assertThat(count("SELECT count(*) FROM moderation.report WHERE target_id=?", target.userId()))
        .isZero();
  }

  @Test
  void contentProofDeniesPrivateOutsidersBlocksHiddenCommentsAndDeletedParents() {
    var owner = user();
    var reporter = user();
    var outsider = user();
    var c = community(owner);
    members.join(reporter, c.id());
    var p = publish(owner, c.id());
    var comment = comments.create(owner, p.id(), null, "Source text", null);
    denied(() -> report(outsider, Reports.Target.POST, p.id()), 404);
    denied(() -> report(outsider, Reports.Target.COMMENT, comment.id()), 404);
    graph.block(owner, reporter.userId(), true);
    denied(() -> report(reporter, Reports.Target.POST, p.id()), 404);
    denied(() -> report(reporter, Reports.Target.COMMENT, comment.id()), 404);
    graph.block(owner, reporter.userId(), false);
    comments.delete(owner, comment.id(), 0);
    denied(() -> report(reporter, Reports.Target.COMMENT, comment.id()), 404);
    posts.delete(owner, p.id(), 0);
    denied(() -> report(reporter, Reports.Target.POST, p.id()), 404);
    assertThat(
            count(
                "SELECT count(*) FROM moderation.report WHERE target_id IN (?,?)",
                p.id(),
                comment.id()))
        .isZero();
  }

  @Test
  void duplicateResolutionsCommitOnlyOneAppendOnlyActionAndSourceTransition() throws Exception {
    var owner = user();
    var author = user();
    var c = community(owner);
    members.join(author, c.id());
    var p = publish(author, c.id());
    var r = report(author, Reports.Target.POST, p.id());
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> command =
          () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
              reports.resolve(owner, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L));
              return 200;
            } catch (Failure f) {
              return f.status();
            }
          };
      var one = pool.submit(command);
      var two = pool.submit(command);
      assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 412);
    }
    assertThat(count("SELECT count(*) FROM moderation.action WHERE report_id=?", r.id()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='PostRemoved'",
                p.id()))
        .isEqualTo(1);
  }

  @Test
  void reportActionAndStaffSanctionSerializeInBothOrders() throws Exception {
    for (boolean actionFirst : List.of(true, false)) {
      var owner = user();
      var staff = user();
      var author = user();
      var c = community(owner);
      var m = members.join(staff, c.id());
      var role = members.role(owner, c.id(), staff.userId(), m.version(), "MODERATOR");
      members.join(author, c.id());
      var p = publish(author, c.id());
      var r = report(author, Reports.Target.POST, p.id());
      var held = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first =
            pool.submit(
                () ->
                    tx.execute(
                        s -> {
                          if (actionFirst)
                            reports.resolve(
                                staff, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L));
                          else
                            members.sanction(
                                owner, c.id(), staff.userId(), role.version(), "BAN_MEMBER", null);
                          held.countDown();
                          release(finish);
                          return true;
                        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var second =
            pool.submit(
                () -> {
                  if (actionFirst)
                    tx.executeWithoutResult(
                        s ->
                            members.sanction(
                                owner, c.id(), staff.userId(), role.version(), "BAN_MEMBER", null));
                  else
                    denied(
                        () ->
                            reports.resolve(
                                staff, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L)),
                        403);
                  return true;
                });
        try {
          waiting(second);
        } finally {
          finish.countDown();
        }
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);
      } finally {
        finish.countDown();
      }
      assertThat(count("SELECT count(*) FROM moderation.action WHERE report_id=?", r.id()))
          .isEqualTo(actionFirst ? 1 : 0);
      assertThat(reports.evidence(owner, c.id(), r.id()).report().status())
          .isEqualTo(actionFirst ? "RESOLVED" : "OPEN");
    }
  }

  @Test
  void caseAuditFailureAfterOwnerAndActionAppendRollsEverythingBack() {
    var owner = user();
    var author = user();
    var c = community(owner);
    members.join(author, c.id());
    var p = publish(author, c.id());
    var r = report(author, Reports.Target.POST, p.id());
    doAnswer(
            call -> {
              if ("report".equals(call.getArgument(4)))
                throw new org.springframework.dao.DataAccessResourceFailureException(
                    "Synthetic case audit outage");
              return call.callRealMethod();
            })
        .when(jdbc)
        .update(startsWith("INSERT INTO platform.audit_entry"), any(Object[].class));
    try {
      assertThatThrownBy(
              () ->
                  reports.resolve(
                      owner, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L)))
          .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    } finally {
      reset(jdbc);
    }
    assertThat(count("SELECT count(*) FROM moderation.action WHERE report_id=?", r.id())).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM content.post WHERE id=? AND status='PUBLISHED' AND version=0",
                p.id()))
        .isEqualTo(1);
    assertThat(reports.evidence(owner, c.id(), r.id()).report().status()).isEqualTo("OPEN");
  }

  @Test
  void rawReportIdentityEvidenceAndTypedActionConstraintsRejectMutation() {
    var actor = user();
    var target = user();
    var r = report(actor, Reports.Target.USER, target.userId());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET target_id=? WHERE id=?", actor.userId(), r.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET evidence_encrypted=? WHERE id=?",
                    new byte[] {1},
                    r.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var done =
        reports.resolve(admin(), null, 0, resolution(r, Reports.Action.RESOLVE_REPORT, null));
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM moderation.action WHERE id=?", done.actionId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void fourTargetsUseOwnerProofAndPrivateEvidenceNeverEntersMetadataOrOutbox() {
    var owner = user();
    var reporter = user();
    var admin = admin();
    var c = community(owner);
    members.join(reporter, c.id());
    var p = publish(owner, c.id());
    var comment = comments.create(owner, p.id(), null, "Comment evidence", null);
    for (var entry :
        Map.of(
                Reports.Target.POST,
                p.id(),
                Reports.Target.COMMENT,
                comment.id(),
                Reports.Target.COMMUNITY,
                c.id(),
                Reports.Target.USER,
                owner.userId())
            .entrySet()) {
      var r = report(reporter, entry.getKey(), entry.getValue());
      var e = reports.evidence(r.communityId() == null ? admin : owner, r.communityId(), r.id());
      assertThat(e.detail()).isEqualTo("Synthetic private detail");
      assertThat(e.summary()).isNotBlank();
      var raw =
          jdbc.queryForObject(
              "SELECT evidence_encrypted FROM moderation.report WHERE id=?", byte[].class, r.id());
      assertThat(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
          .doesNotContain(e.summary(), e.detail());
      assertThat(
              jdbc.queryForObject(
                  "SELECT payload::text FROM platform.outbox_event WHERE aggregate_id=?",
                  String.class,
                  r.id()))
          .doesNotContain("evidence", "detail", e.detail());
      assertThat(
              count(
                  "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='REPORT_EVIDENCE_READ'",
                  r.id()))
          .isEqualTo(1);
    }
    assertThat(reports.own(reporter, 100, 0).items()).hasSize(4);
  }

  @Test
  void userVisibilityMatrixBlocksBothDirectionsAndInactiveTargets() {
    for (var visibility : List.of("PRIVATE", "FRIENDS", "AUTHENTICATED", "PUBLIC")) {
      var target = user();
      var actor = user();
      privacy(target, visibility, "");
      if (Set.of("PRIVATE", "FRIENDS").contains(visibility))
        denied(() -> report(actor, Reports.Target.USER, target.userId()), 404);
      else
        assertThat(report(actor, Reports.Target.USER, target.userId()).targetType())
            .isEqualTo(Reports.Target.USER);
      assertThat(report(target, Reports.Target.USER, target.userId()).targetId())
          .isEqualTo(target.userId());
    }
    var target = user();
    var actor = user();
    var request = graph.request(actor, target.userId());
    graph.respond(target, request.id(), "accept");
    privacy(target, "FRIENDS", "");
    assertThat(report(actor, Reports.Target.USER, target.userId())).isNotNull();
    for (boolean direction : List.of(true, false)) {
      var x = user();
      var y = user();
      graph.block(direction ? x : y, direction ? y.userId() : x.userId(), true);
      denied(() -> report(x, Reports.Target.USER, y.userId()), 404);
    }
    tx.executeWithoutResult(s -> accounts.requestDeletion(target.userId()));
    denied(() -> report(actor, Reports.Target.USER, target.userId()), 404);
  }

  @Test
  void replayRechecksCurrentPrivacyBlocksAndAccountRatherThanCachedEvidence() {
    for (var revoke : List.of("PRIVACY", "BLOCK", "ACCOUNT")) {
      var target = user();
      var actor = user();
      var i = input(Reports.Target.USER, target.userId());
      var r = reports.submit(actor, i, "replay-key");
      assertThat(reports.submit(actor, i, "replay-key").id()).isEqualTo(r.id());
      switch (revoke) {
        case "PRIVACY" -> privacy(target, "PRIVATE", "");
        case "BLOCK" -> graph.block(target, actor.userId(), true);
        case "ACCOUNT" -> tx.executeWithoutResult(s -> accounts.requestDeletion(target.userId()));
      }
      denied(() -> reports.submit(actor, i, "replay-key"), 404);
      assertThat(count("SELECT count(*) FROM moderation.report WHERE target_id=?", target.userId()))
          .isEqualTo(1);
    }
  }

  @Test
  void
      reportersAndWrongCommunityCannotReadOrResolveOthersEvidenceGlobalUserNeverInCommunityQueue() {
    var owner = user();
    var author = user();
    var reporter = user();
    var outsider = user();
    var admin = admin();
    var c = community(owner);
    members.join(author, c.id());
    members.join(reporter, c.id());
    var other = community(outsider);
    var r = report(reporter, Reports.Target.POST, publish(author, c.id()).id());
    assertThat(reports.own(outsider, 100, 0).items()).isEmpty();
    denied(() -> reports.evidence(reporter, c.id(), r.id()), 403);
    denied(() -> reports.evidence(outsider, other.id(), r.id()), 404);
    denied(() -> reports.evidence(admin, null, r.id()), 404);
    denied(
        () ->
            reports.resolve(
                outsider, other.id(), 0, resolution(r, Reports.Action.RESOLVE_REPORT, null)),
        404);
    var userReport = report(reporter, Reports.Target.USER, author.userId());
    assertThat(userReport.communityId()).isNull();
    assertThat(reports.queue(owner, c.id(), 100, 0).items()).doesNotContain(userReport);
    denied(() -> reports.evidence(owner, null, userReport.id()), 403);
    denied(() -> reports.evidence(owner, c.id(), userReport.id()), 404);
    assertThat(reports.evidence(admin, null, userReport.id()).report()).isEqualTo(userReport);
  }

  @Test
  void evidenceSurvivesSourceDeletionAndCurrentStaffRevocationDeniesIt() {
    var owner = user();
    var author = user();
    var reporter = user();
    var staff = user();
    var c = community(owner);
    members.join(author, c.id());
    members.join(reporter, c.id());
    var m = members.join(staff, c.id());
    m = members.role(owner, c.id(), staff.userId(), m.version(), "MODERATOR");
    var p = publish(author, c.id());
    var r = report(reporter, Reports.Target.POST, p.id());
    posts.delete(author, p.id(), 0);
    assertThat(reports.evidence(staff, c.id(), r.id()).summary())
        .isEqualTo("Synthetic report evidence");
    members.remove(owner, c.id(), staff.userId(), m.version());
    denied(() -> reports.evidence(staff, c.id(), r.id()), 403);
  }

  @Test
  void concurrentUnresolvedDuplicatesReturnOneCaseOneEventAndFreshCaseAfterResolution()
      throws Exception {
    var actor = user();
    var author = user();
    var p = publish(author, null);
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Reports.View> command =
          () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return report(actor, Reports.Target.POST, p.id());
          };
      var one = pool.submit(command);
      var two = pool.submit(command);
      var r = one.get(15, TimeUnit.SECONDS);
      assertThat(two.get(15, TimeUnit.SECONDS).id()).isEqualTo(r.id());
      assertThat(count("SELECT count(*) FROM moderation.report WHERE target_id=?", p.id()))
          .isEqualTo(1);
      assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=?", r.id()))
          .isEqualTo(1);
      reports.resolve(admin(), null, 0, resolution(r, Reports.Action.DISMISS_REPORT, null));
      assertThat(report(actor, Reports.Target.POST, p.id()).id()).isNotEqualTo(r.id());
    }
  }

  @Test
  void changedKeyBodyAndBoundsDenyWithoutPartialEffects() {
    var a = user();
    var target = user();
    var p = publish(target, null);
    var i = input(Reports.Target.POST, p.id());
    reports.submit(a, i, "same-key");
    denied(
        () ->
            reports.submit(
                a,
                new Reports.Submit(i.targetType(), i.targetId(), Reports.Category.OTHER, null),
                "same-key"),
        409);
    denied(
        () ->
            reports.submit(
                a,
                new Reports.Submit(i.targetType(), i.targetId(), i.category(), "x".repeat(4001)),
                null),
        400);
    denied(() -> reports.submit(ActorContext.anonymous(), i, null), 401);
    denied(() -> reports.own(a, 101, 0), 400);
    denied(() -> reports.own(a, 20, 10001), 400);
  }

  @Test
  void contentRemoveAndReportActionAuditOutboxAreAtomicAndRestoreCannotUndoAuthorDelete() {
    var owner = user();
    var author = user();
    var reporter = user();
    var c = community(owner);
    members.join(author, c.id());
    members.join(reporter, c.id());
    var p = publish(author, c.id());
    var comment = comments.create(author, p.id(), null, "reported comment", null);
    var r = report(reporter, Reports.Target.COMMENT, comment.id());
    var result =
        reports.resolve(owner, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L));
    assertThat(result.report().status()).isEqualTo("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM content.comment WHERE id=?", String.class, comment.id()))
        .isEqualTo("REMOVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT report_id FROM moderation.action WHERE id=?",
                UUID.class,
                result.actionId()))
        .isEqualTo(r.id());
    denied(
        () -> reports.resolve(owner, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L)),
        412);
    // A second reporter's already-open case survives removal and can request restore.
    var p2 = publish(author, c.id());
    var r2 = report(reporter, Reports.Target.POST, p2.id());
    posts.delete(author, p2.id(), 0);
    denied(
        () -> reports.resolve(owner, c.id(), 0, resolution(r2, Reports.Action.RESTORE_CONTENT, 1L)),
        409);
    assertThat(reports.evidence(owner, c.id(), r2.id()).report().status()).isEqualTo("OPEN");
  }

  @Test
  void reviewResolveDismissVersionsAndGlobalAdminCannotSuspendAccounts() {
    var a = user();
    var target = user();
    var admin = admin();
    var r = report(a, Reports.Target.USER, target.userId());
    var review = reports.resolve(admin, null, 0, resolution(r, Reports.Action.REVIEW_REPORT, null));
    assertThat(review.report().status()).isEqualTo("IN_REVIEW");
    denied(
        () ->
            reports.resolve(
                admin, null, 1, resolution(review.report(), Reports.Action.SUSPEND_MEMBER, 0L)),
        400);
    var done =
        reports.resolve(
            admin, null, 1, resolution(review.report(), Reports.Action.RESOLVE_REPORT, null));
    assertThat(done.report().version()).isEqualTo(2);
    denied(
        () ->
            reports.resolve(
                admin, null, 2, resolution(done.report(), Reports.Action.DISMISS_REPORT, null)),
        409);
    assertThat(count("SELECT count(*) FROM moderation.action WHERE report_id=?", r.id()))
        .isEqualTo(2);
  }

  @Test
  void sanctionsDenySelfOwnerEqualAndHigherAndPermitOwnerAgainstOrdinaryMember() {
    var owner = user();
    var staff = user();
    var reporter = user();
    var c = community(owner);
    var staffM = members.join(staff, c.id());
    members.role(owner, c.id(), staff.userId(), staffM.version(), "MODERATOR");
    members.join(reporter, c.id());
    for (var target : List.of(owner, staff)) {
      var r = report(reporter, Reports.Target.POST, publish(target, c.id()).id());
      denied(
          () -> reports.resolve(staff, c.id(), 0, resolution(r, Reports.Action.BAN_MEMBER, 0L)),
          403);
      assertThat(reports.evidence(owner, c.id(), r.id()).report().status()).isEqualTo("OPEN");
    }
    var ordinary = user();
    var m = members.join(ordinary, c.id());
    var r = report(reporter, Reports.Target.POST, publish(ordinary, c.id()).id());
    reports.resolve(owner, c.id(), 0, resolution(r, Reports.Action.BAN_MEMBER, m.version()));
    assertThat(memberships.facts(ordinary.userId(), List.of(c.id())).getFirst().status())
        .isEqualTo("BANNED");
  }

  @Test
  void auditFailureRollsBackContentCaseActionAndOutboxTogether() {
    var owner = user();
    var author = user();
    var reporter = user();
    var c = community(owner);
    members.join(author, c.id());
    members.join(reporter, c.id());
    var p = publish(author, c.id());
    var r = report(reporter, Reports.Target.POST, p.id());
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "Synthetic audit outage"))
        .when(jdbc)
        .update(startsWith("INSERT INTO platform.audit_entry"), any(Object[].class));
    try {
      assertThatThrownBy(
              () ->
                  reports.resolve(
                      owner, c.id(), 0, resolution(r, Reports.Action.REMOVE_CONTENT, 0L)))
          .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    } finally {
      reset(jdbc);
    }
    assertThat(
            jdbc.queryForObject("SELECT status FROM content.post WHERE id=?", String.class, p.id()))
        .isEqualTo("PUBLISHED");
    assertThat(reports.evidence(owner, c.id(), r.id()).report().status()).isEqualTo("OPEN");
    assertThat(count("SELECT count(*) FROM moderation.action WHERE report_id=?", r.id())).isZero();
    assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=?", p.id()))
        .isEqualTo(1);
  }

  @Test
  void userProofFirstSerializesPrivacyBlockAndAccountChanges() throws Exception {
    for (var revoke : List.of("PRIVACY", "BLOCK", "ACCOUNT")) {
      var target = user();
      var actor = user();
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
          .insertOrUnresolved(any());
      try (var pool = Executors.newFixedThreadPool(2)) {
        var submit = pool.submit(() -> report(actor, Reports.Target.USER, target.userId()));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var mutation =
            pool.submit(
                () -> {
                  switch (revoke) {
                    case "PRIVACY" -> privacy(target, "PRIVATE", "New private body");
                    case "BLOCK" -> graph.block(target, actor.userId(), true);
                    case "ACCOUNT" ->
                        tx.executeWithoutResult(s -> accounts.requestDeletion(target.userId()));
                  }
                  return true;
                });
        try {
          waiting(mutation);
        } finally {
          finish.countDown();
        }
        var r = submit.get(15, TimeUnit.SECONDS);
        mutation.get(15, TimeUnit.SECONDS);
        assertThat(reports.evidence(admin(), null, r.id()).summary())
            .doesNotContain("New private body");
        denied(() -> report(actor, Reports.Target.USER, target.userId()), 404);
      } finally {
        finish.countDown();
        reset(store);
      }
    }
  }

  @Test
  void userRevocationFirstLocksThenDeniesLaterProof() throws Exception {
    for (var revoke : List.of("PRIVACY", "BLOCK", "ACCOUNT")) {
      var target = user();
      var actor = user();
      var held = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var mutation =
            pool.submit(
                () ->
                    tx.execute(
                        s -> {
                          switch (revoke) {
                            case "PRIVACY" -> privacy(target, "PRIVATE", "private");
                            case "BLOCK" -> graph.block(target, actor.userId(), true);
                            case "ACCOUNT" -> accounts.requestDeletion(target.userId());
                          }
                          held.countDown();
                          release(finish);
                          return true;
                        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var submit =
            pool.submit(
                () -> {
                  denied(() -> report(actor, Reports.Target.USER, target.userId()), 404);
                  return true;
                });
        try {
          waiting(submit);
        } finally {
          finish.countDown();
        }
        mutation.get(15, TimeUnit.SECONDS);
        submit.get(15, TimeUnit.SECONDS);
        assertThat(
                count("SELECT count(*) FROM moderation.report WHERE target_id=?", target.userId()))
            .isZero();
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  void evidenceFirstSerializesStaffRemovalAndRemovalFirstDenies() throws Exception {
    for (boolean evidenceFirst : List.of(true, false)) {
      var owner = user();
      var staff = user();
      var author = user();
      var c = community(owner);
      var m = members.join(staff, c.id());
      var role = members.role(owner, c.id(), staff.userId(), m.version(), "MODERATOR");
      members.join(author, c.id());
      var r = report(author, Reports.Target.POST, publish(author, c.id()).id());
      var held = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first =
            pool.submit(
                () ->
                    tx.execute(
                        s -> {
                          if (evidenceFirst) reports.evidence(staff, c.id(), r.id());
                          else members.remove(owner, c.id(), staff.userId(), role.version());
                          held.countDown();
                          release(finish);
                          return true;
                        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        var second =
            pool.submit(
                () -> {
                  if (evidenceFirst) members.remove(owner, c.id(), staff.userId(), role.version());
                  else denied(() -> reports.evidence(staff, c.id(), r.id()), 403);
                  return true;
                });
        try {
          waiting(second);
        } finally {
          finish.countDown();
        }
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);
      } finally {
        finish.countDown();
      }
      denied(() -> reports.evidence(staff, c.id(), r.id()), 403);
    }
  }

  @Test
  void httpRequiresCsrfAuthCurrentSessionAndPreconditionsRejectsCallerScopeAndPrivilegeFields()
      throws Exception {
    var actor = user();
    var target = user();
    var admin = admin();
    var bearer = "Bearer " + tokens.issue(actor);
    var body =
        "{\"targetType\":\"USER\",\"targetId\":\"" + target.userId() + "\",\"category\":\"SPAM\"}";
    mvc.perform(
            post("/api/v1/reports")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/reports")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/reports")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    body.substring(0, body.length() - 1)
                        + ",\"communityId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/reports")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.evidence").doesNotExist())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    var r = reports.own(actor, 20, 0).items().getFirst();
    var adminBearer = "Bearer " + tokens.issue(admin);
    mvc.perform(
            post("/api/v1/admin/moderation/reports/" + r.id() + "/actions")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", adminBearer)
                .contentType("application/json")
                .content("{\"action\":\"RESOLVE_REPORT\",\"reason\":\"Synthetic\"}"))
        .andExpect(status().isPreconditionRequired());
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=now(),revoke_reason='TEST' WHERE id=?",
        actor.sessionId());
    mvc.perform(get("/api/v1/me/reports").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }
}
