package com.flyvo.socialcore.community;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.community.application.*;
import com.flyvo.socialcore.community.application.port.CommunityRepository;
import com.flyvo.socialcore.community.infrastructure.jobs.InvitationExpiry;
import com.flyvo.socialcore.identity.application.Authentication;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.moderation.application.MemberModeration;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
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
class CommunityIT {
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
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired CommunityLifecycle communities;
  @Autowired MembershipLifecycle members;
  @Autowired CommunityAdmissions admissions;
  @Autowired MembershipQueries queries;
  @Autowired MemberModeration moderation;
  @Autowired Authentication auth;
  @Autowired AccountCommands accountCommands;
  @Autowired AccountQueries accounts;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokens tokens;
  @Autowired MockMvc mvc;
  @Autowired tools.jackson.databind.ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired InvitationExpiry expiry;
  @MockitoSpyBean CommunityRepository store;
  @MockitoSpyBean DurableWorkStore work;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    clock.now = Instant.parse("2026-10-10T00:00:00Z");
    tx = new TransactionTemplate(manager);
  }

  ActorContext user() {
    var id = tx.execute(s -> accountCommands.createVerified());
    var sid = UUID.randomUUID();
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

  CommunityCommands.Info community(ActorContext owner, String visibility, String mode) {
    return communities.create(
        owner,
        "group-" + UUID.randomUUID(),
        "Synthetic group",
        "Synthetic description",
        visibility,
        mode,
        null);
  }

  long cv(UUID id) {
    return jdbc.queryForObject(
        "SELECT version FROM community.community WHERE id=?", Long.class, id);
  }

  long mv(UUID c, UUID u) {
    return jdbc.queryForObject(
        "SELECT version FROM community.membership WHERE community_id=? AND user_id=?",
        Long.class,
        c,
        u);
  }

  String memberStatus(UUID c, UUID u) {
    return jdbc.queryForObject(
        "SELECT status FROM community.membership WHERE community_id=? AND user_id=?",
        String.class,
        c,
        u);
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  void failure(Runnable action, int code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(code));
  }

  void promote(ActorContext owner, UUID c, ActorContext u, String role) {
    members.role(owner, c, u.userId(), mv(c, u.userId()), role);
  }

  void sanction(ActorContext actor, UUID c, ActorContext target, String action, Instant until) {
    moderation.apply(
        actor,
        c,
        target.userId(),
        mv(c, target.userId()),
        action,
        "Synthetic policy reason",
        until);
  }

  @Test
  void allVisibilityAndJoinModesHaveHttpCoverageAndPrivateCardsHideAuthority() throws Exception {
    var owner = user();
    var outsider = user();
    for (String visibility : List.of("PUBLIC", "PRIVATE"))
      for (String mode : List.of("OPEN", "REQUEST", "INVITE_ONLY")) {
        var c = community(owner, visibility, mode);
        mvc.perform(get("/api/v1/communities/" + c.id()))
            .andExpect(status().is(visibility.equals("PUBLIC") ? 200 : 404));
        mvc.perform(
                get("/api/v1/communities/" + c.id())
                    .header("Authorization", "Bearer " + tokens.issue(outsider)))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.ownerId")
                    .value(visibility.equals("PUBLIC") ? owner.userId().toString() : null));
        mvc.perform(
                get("/api/v1/communities/" + c.id() + "/members")
                    .header("Authorization", "Bearer " + tokens.issue(outsider)))
            .andExpect(status().isNotFound());
        if (mode.equals("OPEN"))
          mvc.perform(
                  put("/api/v1/communities/" + c.id() + "/members/me")
                      .header("Authorization", "Bearer " + tokens.issue(outsider))
                      .header("Origin", "https://social-core.invalid")
                      .with(csrf().asHeader()))
              .andExpect(status().isOk());
        else if (mode.equals("REQUEST")) {
          var r = admissions.request(outsider, c.id(), "Synthetic request", null);
          admissions.respondRequest(owner, r.id(), "approve");
        } else {
          var i = admissions.invite(owner, c.id(), outsider.userId(), null);
          admissions.respondInvite(outsider, i.id(), "accept");
        }
        assertThat(memberStatus(c.id(), outsider.userId())).isEqualTo("ACTIVE");
      }
  }

  @Test
  void httpValidationCsrfVersionsUnknownFieldsAndScopeDenials() throws Exception {
    var owner = user();
    var outsider = user();
    var c = community(owner, "PRIVATE", "OPEN");
    String token = tokens.issue(owner);
    mvc.perform(
            post("/api/v1/communities")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"valid-slug\",\"name\":\"Test\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/communities")
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\"valid-slug\",\"name\":\"Test\",\"ownerId\":\""
                        + outsider.userId()
                        + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            patch("/api/v1/communities/" + c.id())
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\"}"))
        .andExpect(status().isPreconditionRequired());
    mvc.perform(
            patch("/api/v1/communities/" + c.id())
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://social-core.invalid")
                .header("If-Match", "\"99\"")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\"}"))
        .andExpect(status().isPreconditionFailed());
    mvc.perform(get("/api/v1/communities").param("size", "101")).andExpect(status().isBadRequest());
    var other = community(outsider, "PRIVATE", "OPEN");
    failure(() -> members.role(owner, other.id(), outsider.userId(), 0, "ADMIN"), 403);
    failure(() -> communities.read(owner, UUID.randomUUID()), 404);
    failure(() -> members.join(ActorContext.anonymous(), c.id()), 401);
    assertThat(cv(c.id())).isZero();
  }

  @Test
  void lifecycleTransferArchiveAndOwnerDeletionBlockerAreAtomic() {
    var owner = user();
    var successor = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(successor, c.id());
    failure(() -> members.leave(owner, c.id(), 0), 409);
    failure(() -> auth.close(owner, accounts.find(owner.userId()).version()), 409);
    assertThat(accounts.find(owner.userId()).active()).isTrue();
    communities.transfer(owner, c.id(), cv(c.id()), successor.userId());
    assertThat(queries.facts(owner.userId(), List.of(c.id())).getFirst().role()).isEqualTo("ADMIN");
    assertThat(queries.facts(successor.userId(), List.of(c.id())).getFirst().role())
        .isEqualTo("OWNER");
    failure(
        () -> members.remove(owner, c.id(), successor.userId(), mv(c.id(), successor.userId())),
        403);
    communities.archive(successor, c.id(), cv(c.id()));
    failure(() -> members.join(user(), c.id()), 409);
    failure(
        () -> communities.settings(successor, c.id(), cv(c.id()), "New", null, null, null), 409);
    assertThat(members.members(successor, c.id(), 20, 0)).hasSize(2);
    auth.close(successor, accounts.find(successor.userId()).version());
    assertThat(accounts.find(successor.userId()).status()).isEqualTo("DELETION_PENDING");
  }

  @Test
  void staffRoleMatrixAndSanctionHierarchyAreEnforcedOnRealState() {
    var owner = user();
    var admin = user();
    var mod = user();
    var member = user();
    var c = community(owner, "PUBLIC", "OPEN");
    for (var u : List.of(admin, mod, member)) members.join(u, c.id());
    promote(owner, c.id(), admin, "ADMIN");
    promote(owner, c.id(), mod, "MODERATOR");
    var newcomer = user();
    failure(() -> admissions.invite(member, c.id(), newcomer.userId(), null), 403);
    failure(() -> admissions.invite(mod, c.id(), newcomer.userId(), null), 403);
    var invite = admissions.invite(admin, c.id(), newcomer.userId(), null);
    admissions.respondInvite(newcomer, invite.id(), "accept");
    failure(() -> promote(admin, c.id(), member, "ADMIN"), 403);
    failure(() -> promote(mod, c.id(), member, "MODERATOR"), 403);
    failure(() -> promote(admin, c.id(), admin, "MEMBER"), 403);
    promote(admin, c.id(), member, "MODERATOR");
    promote(admin, c.id(), member, "MEMBER");
    for (var pair :
        List.of(
            List.of(member, newcomer),
            List.of(mod, mod),
            List.of(mod, admin),
            List.of(admin, owner),
            List.of(owner, owner)))
      failure(() -> sanction(pair.get(0), c.id(), pair.get(1), "BAN_MEMBER", null), 403);
    sanction(mod, c.id(), member, "SUSPEND_MEMBER", clock.instant().plusSeconds(10));
    failure(() -> members.join(member, c.id()), 403);
    failure(() -> tx.executeWithoutResult(s -> queries.guardWrite(member, c.id())), 403);
    clock.now = clock.now.plusSeconds(10);
    members.join(member, c.id());
    sanction(admin, c.id(), mod, "BAN_MEMBER", null);
    sanction(owner, c.id(), admin, "BAN_MEMBER", null);
    sanction(owner, c.id(), admin, "UNBAN_MEMBER", null);
    assertThat(memberStatus(c.id(), admin.userId())).isEqualTo("REMOVED");
    assertThat(count("SELECT count(*) FROM moderation.action WHERE community_id=?", c.id()))
        .isEqualTo(4);
  }

  @Test
  void banPreventsInvitationAcceptanceAndUnbanNeverRejoins() {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "INVITE_ONLY");
    var invite = admissions.invite(owner, c.id(), target.userId(), null);
    // Existing historical membership is retained for an excluded invitee.
    tx.executeWithoutResult(s -> store.join(c.id(), target.userId(), clock.instant()));
    sanction(owner, c.id(), target, "BAN_MEMBER", null);
    failure(() -> admissions.respondInvite(target, invite.id(), "accept"), 403);
    sanction(owner, c.id(), target, "UNBAN_MEMBER", null);
    assertThat(memberStatus(c.id(), target.userId())).isEqualTo("REMOVED");
    admissions.respondInvite(target, invite.id(), "accept");
    assertThat(memberStatus(c.id(), target.userId())).isEqualTo("ACTIVE");
    assertThat(queries.facts(target.userId(), List.of(c.id())).getFirst().role())
        .isEqualTo("MEMBER");
  }

  @Test
  void changedModeDoesNotApproveRequestsButStaffCanReviewOutstandingRequest() {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "REQUEST");
    var r = admissions.request(target, c.id(), "Test", null);
    communities.settings(owner, c.id(), cv(c.id()), null, null, null, "INVITE_ONLY");
    assertThat(
            count(
                "SELECT count(*) FROM community.membership WHERE community_id=? AND user_id=?",
                c.id(),
                target.userId()))
        .isZero();
    failure(() -> admissions.request(user(), c.id(), null, null), 409);
    admissions.respondRequest(owner, r.id(), "approve");
    admissions.respondRequest(owner, r.id(), "approve");
    assertThat(memberStatus(c.id(), target.userId())).isEqualTo("ACTIVE");
    var late = user();
    var invite = admissions.invite(owner, c.id(), late.userId(), null);
    communities.settings(owner, c.id(), cv(c.id()), null, null, null, "OPEN");
    admissions.respondInvite(late, invite.id(), "accept");
  }

  @Test
  void invitationRequestCollisionExplicitlyResolvesTheOlderPath() {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "REQUEST");
    var r = admissions.request(target, c.id(), null, null);
    var i = admissions.invite(owner, c.id(), target.userId(), null);
    assertThat(store.request(r.id()).status()).isEqualTo("CANCELLED");
    var next = admissions.request(target, c.id(), "Prefer request", null);
    assertThat(store.invitation(i.id()).status()).isEqualTo("REVOKED");
    admissions.respondRequest(owner, next.id(), "reject");
    var nextInvite = admissions.invite(owner, c.id(), target.userId(), null);
    admissions.respondInvite(target, nextInvite.id(), "decline");
    assertThat(store.invitation(nextInvite.id()).status()).isEqualTo("DECLINED");
  }

  @Test
  void expiryIsLiveWithoutCleanupAndPendingReplacementIsSerialized() {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "INVITE_ONLY");
    var i = admissions.invite(owner, c.id(), target.userId(), null);
    clock.now = i.expiresAt();
    failure(() -> admissions.respondInvite(target, i.id(), "accept"), 409);
    assertThat(admissions.invitations(target, 20, 0)).isEmpty();
    var next = admissions.invite(owner, c.id(), target.userId(), null);
    assertThat(next.id()).isNotEqualTo(i.id());
    assertThat(store.invitation(i.id()).status()).isEqualTo("EXPIRED");
    clock.now = next.expiresAt();
    for (int n = 0; n < 100 && "PENDING".equals(store.invitation(next.id()).status()); n++)
      work.consumeJob(UUID.randomUUID(), List.of(expiry));
    assertThat(store.invitation(next.id()).status()).isEqualTo("EXPIRED");
    tx.executeWithoutResult(s -> expiry.apply(next.id() + ":" + c.id()));
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE kind='COMMUNITY_INVITE_EXPIRY' AND dedup_key=? AND status='COMPLETED'",
                next.id().toString()))
        .isEqualTo(1);
  }

  @Test
  void invitationIdorAndRequesterReviewDenialsLeaveSourceUntouched() {
    var owner = user();
    var target = user();
    var attacker = user();
    var c = community(owner, "PRIVATE", "REQUEST");
    var i = admissions.invite(owner, c.id(), target.userId(), null);
    failure(() -> admissions.respondInvite(attacker, i.id(), "accept"), 404);
    failure(() -> admissions.respondInvite(attacker, i.id(), "revoke"), 403);
    var r = admissions.request(target, c.id(), null, null);
    failure(() -> admissions.respondRequest(attacker, r.id(), "cancel"), 404);
    failure(() -> admissions.respondRequest(attacker, r.id(), "approve"), 403);
    assertThat(store.request(r.id()).status()).isEqualTo("PENDING");
    admissions.respondRequest(target, r.id(), "cancel");
  }

  @Test
  void idempotencyReplaysLogicalReferenceButRechecksCurrentAuthorityAndBody() {
    var owner = user();
    var admin = user();
    var target = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(admin, c.id());
    promote(owner, c.id(), admin, "ADMIN");
    var i = admissions.invite(admin, c.id(), target.userId(), "same-key");
    assertThat(admissions.invite(admin, c.id(), target.userId(), "same-key").id())
        .isEqualTo(i.id());
    failure(() -> admissions.invite(admin, c.id(), user().userId(), "same-key"), 409);
    promote(owner, c.id(), admin, "MEMBER");
    failure(() -> admissions.invite(admin, c.id(), target.userId(), "same-key"), 403);
    assertThat(count("SELECT count(*) FROM community.invitation WHERE community_id=?", c.id()))
        .isEqualTo(1);
  }

  @Test
  void mandatoryAuditFailureRollsBackSanctionActionAndOutbox() {
    var owner = user();
    var target = user();
    var c = community(owner, "PUBLIC", "OPEN");
    members.join(target, c.id());
    long before = cv(c.id());
    DurableWorkStore spy = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(work);
    doThrow(new IllegalStateException("TEST_AUDIT_UNAVAILABLE"))
        .when(spy)
        .auditScoped(
            eq(owner.userId()),
            eq("BAN_MEMBER"),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString());
    try {
      assertThatThrownBy(() -> sanction(owner, c.id(), target, "BAN_MEMBER", null))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      reset(spy);
    }
    assertThat(memberStatus(c.id(), target.userId())).isEqualTo("ACTIVE");
    assertThat(cv(c.id())).isEqualTo(before);
    assertThat(count("SELECT count(*) FROM moderation.action WHERE community_id=?", c.id()))
        .isZero();
  }

  @Test
  void deferredOwnerFkAndTriggerRejectInvalidCommitAndPermitAtomicCreationTransfer() {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(target, c.id());
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s ->
                        jdbc.update(
                            "UPDATE community.membership SET status='LEFT',left_at=now() WHERE community_id=? AND user_id=?",
                            c.id(),
                            owner.userId())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s ->
                        jdbc.update(
                            "UPDATE community.membership SET suspended_until=now()+interval '1 day' WHERE community_id=? AND user_id=?",
                            c.id(),
                            owner.userId())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s ->
                        jdbc.update(
                            "UPDATE community.community SET owner_user_id=? WHERE id=?",
                            user().userId(),
                            c.id())))
        .isInstanceOf(RuntimeException.class);
    assertThat(memberStatus(c.id(), owner.userId())).isEqualTo("ACTIVE");
    communities.transfer(owner, c.id(), cv(c.id()), target.userId());
  }

  @Test
  void duplicateJoinAndAcceptRaceCommitOnlyOneMembershipAndJoinFact() throws Exception {
    var owner = user();
    var target = user();
    var c = community(owner, "PUBLIC", "OPEN");
    race(() -> members.join(target, c.id()), () -> members.join(target, c.id()));
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='MemberJoinedCommunity'",
                c.id()))
        .isEqualTo(1);
    var invitee = user();
    var i = admissions.invite(owner, c.id(), invitee.userId(), null);
    race(
        () -> admissions.respondInvite(invitee, i.id(), "accept"),
        () -> admissions.respondInvite(invitee, i.id(), "accept"));
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='MemberJoinedCommunity'",
                c.id()))
        .isEqualTo(2);
  }

  @Test
  void ownerTransferAgainstSuccessorLeaveOrRemovalAlwaysPreservesEffectiveOwner() throws Exception {
    for (boolean removal : List.of(false, true)) {
      var owner = user();
      var successor = user();
      var c = community(owner, "PRIVATE", "OPEN");
      members.join(successor, c.id());
      long v = cv(c.id());
      raceAllowFailure(
          () -> communities.transfer(owner, c.id(), v, successor.userId()),
          () -> {
            if (removal) members.remove(owner, c.id(), successor.userId(), 0);
            else members.leave(successor, c.id(), 0);
          });
      assertThat(
              count(
                  "SELECT count(*) FROM community.community c JOIN community.membership m ON m.community_id=c.id AND m.user_id=c.owner_user_id WHERE c.id=? AND m.status='ACTIVE' AND (m.suspended_until IS NULL OR m.suspended_until<=now())",
                  c.id()))
          .isEqualTo(1);
    }
  }

  @Test
  void sharedCommunityWriteGuardBlocksRevocationAndRevalidatesAfterCommit() throws Exception {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(target, c.id());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    doAnswer(
            call -> {
              if (Thread.currentThread().getName().contains("revoke")) attempted.countDown();
              return call.callRealMethod();
            })
        .when(store)
        .community(eq(c.id()), eq(true), eq(true));
    try {
      var writer =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        accounts.guard(target.userId(), false);
                        queries.guardWrite(target, c.id());
                        entered.countDown();
                        await(release);
                        assertThat(memberStatus(c.id(), target.userId())).isEqualTo("ACTIVE");
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var revoke =
          pool.submit(
              () -> {
                Thread.currentThread().setName("revoke-community");
                members.remove(owner, c.id(), target.userId(), 0);
              });
      assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(revoke.isDone()).isFalse();
      release.countDown();
      writer.get(10, TimeUnit.SECONDS);
      revoke.get(10, TimeUnit.SECONDS);
      failure(
          () ->
              tx.executeWithoutResult(
                  s -> {
                    accounts.guard(target.userId(), false);
                    queries.guardWrite(target, c.id());
                  }),
          403);
    } finally {
      release.countDown();
      pool.shutdownNow();
      reset(store);
    }
  }

  @Test
  void communityCreationAndTransferSerializeAgainstAccountClosure() throws Exception {
    var owner = user();
    var successor = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(successor, c.id());
    raceAllowFailure(
        () -> communities.transfer(owner, c.id(), cv(c.id()), successor.userId()),
        () -> auth.close(successor, 0));
    boolean successorOwns = queries.ownsActiveCommunity(successor.userId());
    assertThat(successorOwns).isEqualTo(accounts.find(successor.userId()).active());
    var creator = user();
    raceAllowFailure(() -> community(creator, "PRIVATE", "OPEN"), () -> auth.close(creator, 0));
    assertThat(queries.ownsActiveCommunity(creator.userId()))
        .isEqualTo(accounts.find(creator.userId()).active());
  }

  @Test
  void publishedViewsMatchObjectPolicyWithSuspensionStatusAndOwnerDerivation() {
    var owner = user();
    var target = user();
    for (String visibility : List.of("PUBLIC", "PRIVATE")) {
      var c = community(owner, visibility, "OPEN");
      members.join(target, c.id());
      for (String status : List.of("ACTIVE", "LEFT", "REMOVED", "BANNED"))
        for (Instant until : Arrays.asList(null, clock.instant(), clock.instant().plusSeconds(1))) {
          if (!status.equals("ACTIVE") && until != null) continue;
          tx.executeWithoutResult(
              s ->
                  store.membership(
                      c.id(), target.userId(), "MEMBER", status, until, clock.instant()));
          var object =
              CommunityPermissionPolicy.readable(
                  store.community(c.id(), false, false),
                  store.member(c.id(), target.userId()),
                  true,
                  clock.instant());
          var sql =
              jdbc.queryForObject(
                  "SELECT NOT (m.status='BANNED' OR (m.status='ACTIVE' AND m.suspended_until IS NOT NULL AND m.suspended_until>?)) AND (c.visibility='PUBLIC' OR (m.status='ACTIVE' AND (m.suspended_until IS NULL OR m.suspended_until<=?))) FROM community_api.communities_v1 c JOIN community_api.memberships_v1 m ON m.community_id=c.community_id WHERE c.community_id=? AND m.user_id=?",
                  Boolean.class,
                  clock.instant().atOffset(ZoneOffset.UTC),
                  clock.instant().atOffset(ZoneOffset.UTC),
                  c.id(),
                  target.userId());
          assertThat(sql).isEqualTo(object);
        }
    }
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='community_api' AND table_name='communities_v1' ORDER BY ordinal_position",
                String.class))
        .containsExactly("community_id", "visibility", "status", "owner_user_id");
  }

  private void race(Runnable a, Runnable b) throws Exception {
    runRace(a, b, false);
  }

  @Test
  void httpAdmissionRoleModerationTransferAndArchiveEndpointsAreOperational() throws Exception {
    var owner = user();
    var target = user();
    var created =
        send(
            owner,
            post("/api/v1/communities"),
            "{\"slug\":\"http-"
                + UUID.randomUUID()
                + "\",\"name\":\"Synthetic HTTP group\",\"visibility\":\"PRIVATE\",\"joinMode\":\"REQUEST\"}",
            null,
            201);
    var c = UUID.fromString(json.readTree(created).get("id").asString());
    var requested =
        send(
            target,
            post("/api/v1/communities/" + c + "/join-requests"),
            "{\"message\":\"Synthetic request\"}",
            null,
            201);
    var request = UUID.fromString(json.readTree(requested).get("id").asString());
    send(owner, post("/api/v1/join-requests/" + request + "/approve"), null, null, 200);
    send(
        owner,
        patch("/api/v1/communities/" + c + "/members/" + target.userId() + "/role"),
        "{\"role\":\"MODERATOR\"}",
        mv(c, target.userId()),
        200);
    send(
        owner,
        post("/api/v1/communities/" + c + "/moderation/actions"),
        "{\"userId\":\""
            + target.userId()
            + "\",\"action\":\"SUSPEND_MEMBER\",\"reason\":\"Synthetic reason\",\"suspendedUntil\":\""
            + clock.instant().plusSeconds(60)
            + "\"}",
        mv(c, target.userId()),
        200);
    clock.now = clock.now.plusSeconds(60);
    var invitee = user();
    var invited =
        send(
            owner,
            post("/api/v1/communities/" + c + "/invitations"),
            "{\"userId\":\"" + invitee.userId() + "\"}",
            null,
            201);
    var invitation = UUID.fromString(json.readTree(invited).get("id").asString());
    send(invitee, post("/api/v1/community-invitations/" + invitation + "/accept"), null, null, 200);
    send(
        owner,
        post("/api/v1/communities/" + c + "/ownership-transfers"),
        "{\"userId\":\"" + target.userId() + "\"}",
        cv(c),
        200);
    send(target, post("/api/v1/communities/" + c + "/archive"), null, cv(c), 200);
    mvc.perform(
            get("/api/v1/communities/" + c + "/join-requests")
                .header("Authorization", "Bearer " + tokens.issue(target)))
        .andExpect(status().isOk());
    send(
        target,
        delete("/api/v1/communities/" + c + "/members/me"),
        null,
        mv(c, target.userId()),
        204);
  }

  private String send(
      ActorContext actor,
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String body,
      Long version,
      int expected)
      throws Exception {
    request
        .header("Authorization", "Bearer " + tokens.issue(actor))
        .header("Origin", "https://social-core.invalid")
        .with(csrf().asHeader());
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    if (version != null) request.header("If-Match", "\"" + version + "\"");
    return mvc.perform(request)
        .andExpect(status().is(expected))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void versionsMembershipReactivationAndInvalidStatesPreserveAuthority() {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(target, c.id());
    failure(() -> members.role(owner, c.id(), target.userId(), 0, "OWNER"), 400);
    failure(() -> communities.transfer(owner, c.id(), cv(c.id()), owner.userId()), 400);
    failure(() -> sanction(owner, c.id(), target, "SUSPEND_MEMBER", clock.instant()), 400);
    members.leave(target, c.id(), 0);
    failure(() -> members.role(owner, c.id(), target.userId(), 0, "MODERATOR"), 412);
    var rejoined = members.join(target, c.id());
    assertThat(rejoined.version()).isEqualTo(2);
    assertThat(rejoined.role()).isEqualTo("MEMBER");
    failure(() -> members.leave(target, c.id(), 0), 412);
    members.remove(owner, c.id(), target.userId(), 2);
    members.join(target, c.id());
    assertThat(mv(c.id(), target.userId())).isEqualTo(4);
    var unknown = UUID.randomUUID();
    failure(() -> admissions.invite(owner, c.id(), unknown, null), 404);
    failure(
        () -> communities.create(owner, "invalid slug", "Name", "", "PUBLIC", "OPEN", null), 400);
    failure(() -> admissions.request(target, c.id(), "x".repeat(1001), null), 400);
  }

  @Test
  void createAndRequestIdempotencyRejectsBodyCollisionsAndExpiredKeysCanBeReused() {
    var owner = user();
    var slug = "idem-" + UUID.randomUUID();
    var c =
        communities.create(
            owner, slug, "Name, with commas", "Description", "PRIVATE", "REQUEST", "create-key");
    assertThat(
            communities
                .create(
                    owner,
                    slug,
                    "Name, with commas",
                    "Description",
                    "PRIVATE",
                    "REQUEST",
                    "create-key")
                .id())
        .isEqualTo(c.id());
    failure(
        () ->
            communities.create(
                owner,
                slug,
                "Name",
                "with commas, Description",
                "PRIVATE",
                "REQUEST",
                "create-key"),
        409);
    var target = user();
    var request = admissions.request(target, c.id(), null, "request-key");
    failure(() -> admissions.request(target, c.id(), "", "request-key"), 409);
    admissions.respondRequest(target, request.id(), "cancel");
    clock.now = clock.now.plusSeconds(86400);
    assertThat(admissions.request(target, c.id(), "New attempt", "request-key").id())
        .isNotEqualTo(request.id());
    failure(() -> admissions.invite(owner, c.id(), user().userId(), " "), 400);
  }

  @Test
  void suspensionAndAccountRevocationDenyHttpEvenWithAnUnexpiredBearer() throws Exception {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(target, c.id());
    String token = tokens.issue(target);
    sanction(owner, c.id(), target, "SUSPEND_MEMBER", clock.instant().plusSeconds(60));
    mvc.perform(
            get("/api/v1/communities/" + c.id() + "/members")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/communities/" + c.id()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerId").value((String) null));
    auth.logoutAll(target);
    mvc.perform(get("/api/v1/communities/" + c.id()).header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void largeCommunityUsesIndexedPointFactsAndBoundedMemberPages() {
    var owner = user();
    var c = community(owner, "PUBLIC", "OPEN");
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "INSERT INTO account.user_account(id,status,platform_role,created_at,updated_at) SELECT gen_random_uuid(),'ACTIVE','USER',now(),now() FROM generate_series(1,10000)");
          jdbc.update(
              "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at) SELECT ?,id,'MEMBER','ACTIVE',now(),now() FROM account.user_account WHERE id<>?",
              c.id(),
              owner.userId());
        });
    jdbc.execute("ANALYZE community.membership");
    var plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "EXPLAIN (ANALYZE,BUFFERS) SELECT role,status,suspended_until FROM community_api.memberships_v1 WHERE community_id=? AND user_id=?",
                String.class,
                c.id(),
                owner.userId()));
    assertThat(plan).contains("membership_pkey");
    assertThat(members.members(owner, c.id(), 100, 0)).hasSize(100);
    assertThat(queries.facts(owner.userId(), List.of(c.id())).getFirst().role()).isEqualTo("OWNER");
    System.out.println("Phase 2 synthetic 10,000-member point-fact plan:\n" + plan);
  }

  @Test
  void archivedOwnerCanLeaveWithoutGrantingAnyOtherArchivedMutation() {
    var owner = user();
    var c = community(owner, "PRIVATE", "OPEN");
    communities.archive(owner, c.id(), cv(c.id()));
    members.leave(owner, c.id(), 0);
    assertThat(memberStatus(c.id(), owner.userId())).isEqualTo("LEFT");
    failure(() -> members.join(owner, c.id()), 409);
    failure(() -> admissions.invite(owner, c.id(), user().userId(), null), 409);
    assertThat(queries.ownsActiveCommunity(owner.userId())).isFalse();
  }

  @Test
  void requestInvitationRaceNeverCommitsTwoPendingAdmissionPaths() throws Exception {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "REQUEST");
    race(
        () -> admissions.request(target, c.id(), null, null),
        () -> admissions.invite(owner, c.id(), target.userId(), null));
    assertThat(
            count(
                "SELECT (SELECT count(*) FROM community.invitation WHERE community_id=? AND status='PENDING') + (SELECT count(*) FROM community.join_request WHERE community_id=? AND status='PENDING')",
                c.id(),
                c.id()))
        .isEqualTo(1);
  }

  @Test
  void removedGuardWaiterCannotUsePermissionObservedBeforeRevocation() throws Exception {
    var owner = user();
    var target = user();
    var c = community(owner, "PRIVATE", "OPEN");
    members.join(target, c.id());
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    doAnswer(
            call -> {
              attempted.countDown();
              return call.callRealMethod();
            })
        .when(store)
        .community(eq(c.id()), eq(true), eq(false));
    try {
      var revoke =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        accounts.guard(owner.userId(), false);
                        accounts.guard(target.userId(), false);
                        store.community(c.id(), true, true);
                        store.membership(
                            c.id(), target.userId(), "MEMBER", "REMOVED", null, clock.instant());
                        held.countDown();
                        await(release);
                      }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      var writer =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        accounts.guard(target.userId(), false);
                        queries.guardWrite(target, c.id());
                      }));
      assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(writer.isDone()).isFalse();
      release.countDown();
      revoke.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(Failure.class);
    } finally {
      release.countDown();
      pool.shutdownNow();
      reset(store);
    }
  }

  @Test
  void databaseJobsRollbackRetryFenceLeasesAndCompleteOnceAcrossTwoWorkers() throws Exception {
    var key = UUID.randomUUID().toString();
    var effect = UUID.randomUUID();
    String kind = "PHASE2_TEST_" + key.substring(0, 8);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      work.job(kind, key, "test", clock.instant());
                      throw new IllegalStateException("ROLLBACK");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("SELECT count(*) FROM platform.job WHERE kind=?", kind)).isZero();
    tx.executeWithoutResult(s -> work.job(kind, key, "test", clock.instant()));
    JobHandler expired =
        new JobHandler() {
          public String kind() {
            return kind;
          }

          public void apply(String payload) {
            jdbc.update(
                "INSERT INTO platform.consumer_receipt(consumer_name,event_id,processed_at) VALUES ('phase2-job',?,now())",
                effect);
            clock.now = clock.now.plusSeconds(61);
          }
        };
    assertThat(work.consumeJob(UUID.randomUUID(), List.of(expired))).isFalse();
    assertThat(count("SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?", effect))
        .isZero();
    // Simulate process loss after claim, before handler; next generation reclaims it.
    jdbc.update(
        "UPDATE platform.job SET status='RUNNING',lease_owner=?,lease_until=?,available_at=? WHERE kind=?",
        UUID.randomUUID(),
        clock.instant().minusSeconds(1).atOffset(ZoneOffset.UTC),
        clock.instant().atOffset(ZoneOffset.UTC),
        kind);
    JobHandler succeeding =
        new JobHandler() {
          public String kind() {
            return kind;
          }

          public void apply(String payload) {
            jdbc.update(
                "INSERT INTO platform.consumer_receipt(consumer_name,event_id,processed_at) VALUES ('phase2-job',?,now()) ON CONFLICT DO NOTHING",
                effect);
          }
        };
    race(
        () -> work.consumeJob(UUID.randomUUID(), List.of(succeeding)),
        () -> work.consumeJob(UUID.randomUUID(), List.of(succeeding)));
    assertThat(count("SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?", effect))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE kind=? AND status='COMPLETED' AND attempts=2 AND lease_generation=2",
                kind))
        .isEqualTo(1);
    var failureKey = UUID.randomUUID().toString();
    String failingKind = "PHASE2_FAILURE_" + failureKey.substring(0, 8);
    tx.executeWithoutResult(s -> work.job(failingKind, failureKey, "test", clock.instant()));
    JobHandler fails =
        new JobHandler() {
          public String kind() {
            return failingKind;
          }

          public void apply(String payload) {
            throw new IllegalStateException("TEST_FAILURE");
          }
        };
    for (int i = 0; i < 10; i++) {
      assertThat(work.consumeJob(UUID.randomUUID(), List.of(fails))).isFalse();
      clock.now = clock.now.plusSeconds(3601);
    }
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE kind=? AND status='DEAD' AND attempts=10",
                failingKind))
        .isEqualTo(1);
    work.prune();
    assertThat(
            count("SELECT count(*) FROM platform.job WHERE kind=? AND status='DEAD'", failingKind))
        .isEqualTo(1);
  }

  private void raceAllowFailure(Runnable a, Runnable b) throws Exception {
    runRace(a, b, true);
  }

  private void runRace(Runnable a, Runnable b, boolean allowFailure) throws Exception {
    var barrier = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var futures =
          List.of(
              pool.submit(
                  () -> {
                    barrier(barrier);
                    a.run();
                  }),
              pool.submit(
                  () -> {
                    barrier(barrier);
                    b.run();
                  }));
      int successes = 0;
      for (var f : futures)
        try {
          f.get(15, TimeUnit.SECONDS);
          successes++;
        } catch (ExecutionException e) {
          if (!allowFailure) throw e;
          assertThat(e.getCause()).isInstanceOf(Failure.class);
        }
      assertThat(successes).isGreaterThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static void barrier(CyclicBarrier b) {
    try {
      b.await(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
