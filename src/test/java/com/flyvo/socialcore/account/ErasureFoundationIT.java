package com.flyvo.socialcore.account;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.account.application.*;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import com.flyvo.socialcore.identity.application.Authentication;
import com.flyvo.socialcore.identity.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.api.ProfileCommands;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ErasureFoundationIT {
  static class FlowClock extends Clock {
    volatile Instant fixed;

    public Instant instant() {
      return fixed == null ? Instant.now() : fixed;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  static class SyntheticProvider implements ProviderClient {
    volatile com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof proof;
    volatile Runnable afterExchange = () -> {};

    public String authorization(String provider, String state, String nonce, String verifier) {
      return "https://synthetic-provider.invalid/?state=" + state;
    }

    public com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof verify(
        String provider, String code, byte[] nonce, String verifier) {
      assertThat(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive())
          .isFalse();
      afterExchange.run();
      return proof;
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class FlowFixtures {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    FlowClock flowClock() {
      return new FlowClock();
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    SyntheticProvider syntheticProvider() {
      return new SyntheticProvider();
    }
  }

  static final Path ARCHIVE = directory();

  static Path directory() {
    try {
      return Files.createTempDirectory("social-p9a-archive-");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
              "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
          .withDatabaseName("p9a_foundation");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
    r.add("app.erasure.enabled", () -> true);
    r.add("app.erasure.policy", () -> AccountErasure.POLICY);
    r.add("app.erasure.lineage", () -> "48139cc7-748b-4b1f-a4b8-bdef004963df");
    r.add("app.erasure.archive-directory", ARCHIVE::toString);
  }

  @Autowired AccountCommands accounts;
  @Autowired AccountQueries queries;
  @Autowired AccountErasure erasure;
  @Autowired ErasureStore store;
  @Autowired Erasures internal;
  @Autowired com.flyvo.socialcore.community.application.CommunityErasure communityErasure;
  @Autowired com.flyvo.socialcore.graph.application.GraphErasure graphErasure;
  @Autowired com.flyvo.socialcore.content.application.ContentErasure contentErasure;
  @Autowired com.flyvo.socialcore.content.application.PostRetention postRetention;
  @Autowired com.flyvo.socialcore.moderation.application.ModerationErasure moderationErasure;
  @Autowired com.flyvo.socialcore.moderation.application.ModerationPreservation preservation;
  @Autowired com.flyvo.socialcore.moderation.api.Reports reports;
  @Autowired com.flyvo.socialcore.moderation.api.CaseOperations cases;
  @Autowired com.flyvo.socialcore.graph.api.GraphCommands graphCommands;
  @Autowired com.flyvo.socialcore.community.api.AdmissionCommands admissions;
  @Autowired com.flyvo.socialcore.community.api.MembershipCommands memberships;
  @Autowired Authentication auth;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  IdentityRepository identity;

  @Autowired ProfileCommands profiles;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RecoveryArchive archive;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DurableWorkStore jobs;
  @Autowired ErasureExports exporter;
  @Autowired ErasureCompletion completion;
  @Autowired com.flyvo.socialcore.search.application.SearchSources searchSources;
  @Autowired com.flyvo.socialcore.search.application.port.SearchIndex searchIndex;
  @Autowired AccessTokens tokens;
  @Autowired MockMvc mvc;
  @Autowired java.util.List<JobHandler> dbHandlers;
  @Autowired java.util.List<ExternalJobHandler> externalHandlers;
  @Autowired ScopedWork scoped;
  @Autowired com.flyvo.socialcore.identity.application.port.PasswordHashing passwords;
  @Autowired Secrets secrets;
  @Autowired tools.jackson.databind.ObjectMapper json;

  @org.junit.jupiter.api.BeforeEach
  void isolatePriorExecutions() {
    flowClock.fixed = null;
    provider.afterExchange = () -> {};
    org.mockito.Mockito.doAnswer(
            call -> flowClock.fixed == null ? call.callRealMethod() : flowClock.fixed)
        .when(identity)
        .databaseTime();
    jdbc.update(
        "UPDATE platform.job SET available_at=timestamptz '2100-01-01 00:00:00Z' WHERE kind='MAIL' AND status IN ('PENDING','RETRY')");
    jdbc.update(
        "UPDATE platform.job SET available_at=timestamptz '2100-01-01 00:00:00Z' WHERE (kind LIKE 'ERASURE_%' OR kind LIKE '%_ERASURE' OR kind='MODERATION_PRESERVE') AND status IN ('PENDING','RETRY')");
  }

  void due(UUID user) {
    jdbc.update(
        "UPDATE platform.job SET available_at=LEAST(available_at,clock_timestamp()) WHERE status IN ('PENDING','RETRY') AND (payload->>'data' LIKE ? OR payload->>'data' IN (SELECT e.id::text FROM account.erasure_export e JOIN account.erasure_intent i ON i.id=e.intent_id WHERE i.user_id=?))",
        "%" + user + "%",
        user);
  }

  void tick(UUID user) {
    due(user);
    jobs.consumeJob(
        UUID.randomUUID(),
        dbHandlers.stream()
            .filter(
                h ->
                    Set.of("ERASURE_RECONCILE", "IDENTITY_ERASURE", "PROFILE_ERASURE")
                        .contains(h.kind()))
            .toList());
    jobs.consumeExternalJob(
        UUID.randomUUID(),
        externalHandlers.stream()
            .filter(h -> Set.of("ERASURE_EXPORT", "ERASURE_TECHNICAL").contains(h.kind()))
            .toList());
  }

  void historical(ActorContext actor) {
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=now()-interval '2 hours',version=version+1 WHERE id=?",
        actor.userId());
    tx().executeWithoutResult(s -> internal.reconcile(actor.userId()));
  }

  void converge(UUID user, int limit) {
    for (int i = 0; i < limit; i++) tick(user);
  }

  TransactionTemplate tx() {
    return new TransactionTemplate(manager);
  }

  ActorContext actor(boolean admin) {
    return tx().execute(
            s -> {
              var id = accounts.createVerified();
              profiles.provision(id);
              var session = UUID.randomUUID();
              identity.claimEmail(id, id + "@example.test", Instant.now());
              identity.createSession(session, id, Instant.now(), false, 0);
              if (admin)
                jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", id);
              return new ActorContext(id, session);
            });
  }

  void exports() {
    for (int i = 0; i < 20; i++) jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter));
  }

  UUID rejectPreparedCloseThenCommitNextVersion(ActorContext user) {
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            call -> {
              var receipt = call.callRealMethod();
              var record =
                  json.readValue(
                      new String(
                          (byte[]) call.getArgument(2), java.nio.charset.StandardCharsets.UTF_8),
                      Erasures.Journal.class);
              if (record.subject().equals(user.userId()) && changed.compareAndSet(false, true))
                jdbc.update(
                    "UPDATE account.user_account SET version=version+1 WHERE id=?", user.userId());
              return receipt;
            })
        .when(archive)
        .append(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    try {
      assertThatThrownBy(() -> auth.close(user, 0)).hasMessage("STALE_VERSION");
    } finally {
      org.mockito.Mockito.reset(archive);
    }
    assertThat(changed).isTrue();
    var rejected =
        jdbc.queryForObject(
            "SELECT id FROM account.erasure_intent WHERE user_id=? AND expected_version=0",
            UUID.class,
            user.userId());
    assertThat(store.export(rejected, "PREPARED").receipt()).isNotNull();
    auth.close(user, 1);
    assertThat(store.request(user.userId()).id()).isNotEqualTo(rejected);
    return rejected;
  }

  void onlyExportDue(UUID user, UUID export) {
    jdbc.update(
        "UPDATE platform.job SET available_at=timestamptz '2100-01-01Z' WHERE kind='ERASURE_EXPORT' AND payload->>'data' IN (SELECT e.id::text FROM account.erasure_export e JOIN account.erasure_intent i ON i.id=e.intent_id WHERE i.user_id=?) AND status IN ('PENDING','RETRY')",
        user);
    jdbc.update(
        "UPDATE platform.job SET available_at=clock_timestamp() WHERE kind='ERASURE_EXPORT' AND payload->>'data'=? AND status IN ('PENDING','RETRY')",
        export.toString());
  }

  @Test
  void rejectedPreparedDeliveryAfterLaterCloseCannotInventCommitOrCertifyAnotherRequest() {
    var user = actor(false);
    var rejected = rejectPreparedCloseThenCommitNextVersion(user);
    var request = store.request(user.userId());
    var ownCommit = store.export(request.id(), "COMMITTED");
    var before = store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false);
    onlyExportDue(user.userId(), store.export(rejected, "PREPARED").id());
    assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter))).isTrue();
    assertThat(store.export(rejected, "COMMITTED")).isNull();
    assertThat(store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false))
        .isEqualTo(before);
    assertThat(store.export(ownCommit.id()).receipt()).isNull();
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    assertThat(store.step(user.userId(), AccountErasure.Step.IDENTITY, false).view().status())
        .isEqualTo("WAITING_DEPENDENCY");
    onlyExportDue(user.userId(), ownCommit.id());
    assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter))).isTrue();
    assertThat(store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).proof())
        .isEqualTo(ownCommit.digest());
    assertThat(store.export(rejected, "COMMITTED")).isNull();
  }

  @Test
  void obsoleteCommittedExportCannotPoisonCurrentRequestOnFailureOrPublishAFalseReceipt()
      throws Exception {
    var user = actor(false);
    var rejected = rejectPreparedCloseThenCommitNextVersion(user);
    var intent = store.intent(rejected);
    var badId = UUID.randomUUID();
    // Model an immutable queued artifact left by the reviewed bug. Never rewrite its history.
    var record =
        new Erasures.Journal(
            intent.lineage(),
            badId,
            rejected,
            user.userId(),
            AccountErasure.PROTOCOL,
            AccountErasure.POLICY,
            json.readValue(store.export(rejected, "PREPARED").record(), Erasures.Journal.class)
                .rosterDigest(),
            1,
            1,
            "COMMITTED",
            null,
            null,
            null,
            Instant.now(),
            intent.createdAt(),
            List.of());
    var encoded = json.writeValueAsString(record);
    var digest =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    tx().executeWithoutResult(
            s -> {
              store.addExport(badId, rejected, "COMMITTED", null, null, null, encoded, digest);
              var export = store.export(badId);
              jobs.job(
                  "ERASURE_EXPORT", export.execution().toString(), badId.toString(), Instant.now());
            });
    var current = store.request(user.userId());
    var before = store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false);
    var offline = ARCHIVE.resolveSibling(ARCHIVE.getFileName() + "-r2-offline");
    Files.move(ARCHIVE, offline);
    try {
      onlyExportDue(user.userId(), badId);
      assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter))).isFalse();
      assertThat(store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false))
          .isEqualTo(before);
      assertThat(store.request(user.userId())).isEqualTo(current);
    } finally {
      Files.move(offline, ARCHIVE);
    }
    onlyExportDue(user.userId(), badId);
    assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter))).isFalse();
    assertThat(Files.exists(ARCHIVE.resolve(badId + ".record"))).isFalse();
    assertThat(store.export(badId).receipt()).isNull();
    assertThat(store.export(badId).record()).isEqualTo(encoded);
    assertThat(store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false))
        .isEqualTo(before);
    var own = store.export(current.id(), "COMMITTED");
    onlyExportDue(user.userId(), own.id());
    assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter))).isTrue();
    assertThat(store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).proof())
        .isEqualTo(own.digest());
  }

  @Test
  void supersededExportExecutionCannotAcknowledgeOrChargeItsSuccessorAfterRealAppend() {
    for (boolean failAfterAppend : List.of(false, true)) {
      var user = actor(false);
      auth.close(user, 0);
      var committed = store.export(store.request(user.userId()).id(), "COMMITTED");
      var nextExecution = UUID.randomUUID();
      var once = new java.util.concurrent.atomic.AtomicBoolean();
      org.mockito.Mockito.doAnswer(
              call -> {
                var result = call.callRealMethod();
                if (committed.id().equals(call.getArgument(0)) && once.compareAndSet(false, true)) {
                  tx().executeWithoutResult(
                          s -> {
                            store.resumeExport(committed.id(), nextExecution);
                            jobs.job(
                                "ERASURE_EXPORT",
                                nextExecution.toString(),
                                committed.id().toString(),
                                Instant.now());
                          });
                  if (failAfterAppend) throw new IllegalStateException("SYNTHETIC_OLD_EXECUTION");
                }
                return result;
              })
          .when(archive)
          .append(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.any());
      try {
        onlyExportDue(user.userId(), committed.id());
        assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter)))
            .isEqualTo(!failAfterAppend);
      } finally {
        org.mockito.Mockito.reset(archive);
      }
      assertThat(once).isTrue();
      assertThat(Files.exists(ARCHIVE.resolve(committed.id() + ".record"))).isTrue();
      assertThat(store.export(committed.id()).receipt()).isNull();
      assertThat(store.export(committed.id()).execution()).isEqualTo(nextExecution);
      assertThat(
              jdbc.queryForObject(
                  "SELECT failures FROM account.erasure_export WHERE id=?",
                  Long.class,
                  committed.id()))
          .isZero();
      assertThat(
              store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).view().status())
          .isNotEqualTo("DONE");
      assertThat(
              store
                  .step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false)
                  .view()
                  .failures())
          .isZero();
      for (int i = 0; i < 2; i++) {
        onlyExportDue(user.userId(), committed.id());
        jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter));
      }
      assertThat(store.export(committed.id()).receipt()).isEqualTo(committed.digest());
      assertThat(store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).proof())
          .isEqualTo(committed.digest());
    }
  }

  @Test
  void closeRequiresPreparedReceiptAndAllTwelveOwnersRemainVisible() {
    var user = actor(false);
    auth.close(user, 0);
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
    var r = store.request(user.userId());
    assertThat(r).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.erasure_step WHERE user_id=?",
                Integer.class,
                user.userId()))
        .isEqualTo(12);
    assertThat(store.export(r.id(), "PREPARED").receipt()).isNotNull();
    assertThatThrownBy(() -> auth.requireCurrent(user)).isInstanceOf(Failure.class);
    exports();
    var admin = actor(true);
    var progress = erasure.progress(admin, user.userId());
    assertThat(progress.policy()).isEqualTo("isolated-synthetic-v1");
    assertThat(progress.steps())
        .filteredOn(s -> s.step().kind() == null)
        .allSatisfy(
            s -> {
              assertThat(s.status()).isEqualTo("WAITING_DEPENDENCY");
              assertThat(s.reason()).isEqualTo("OWNER_NOT_IMPLEMENTED");
            });
    assertThatThrownBy(() -> erasure.requireCompletion(user.userId(), "wrong"))
        .isInstanceOf(Failure.class)
        .hasMessage("ERASURE_PREREQUISITE_INCOMPLETE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE account.user_account SET status='DELETED' WHERE id=?", user.userId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void missingReceiptCannotCommitLegacyDeletion() {
    var user = actor(false);
    assertThatThrownBy(
            () -> tx().executeWithoutResult(s -> accounts.requestDeletion(user.userId())))
        .isInstanceOf(Failure.class)
        .hasMessage("RECOVERY_RECEIPT_REQUIRED");
    assertThat(queries.find(user.userId()).active()).isTrue();
    assertThat(store.request(user.userId())).isNull();
  }

  @Test
  void immutableAppendSurvivesRestartAndRejectsMissingOrCorruptReceipt() throws Exception {
    var user = actor(false);
    var id = tx().execute(s -> erasure.prepare(user.userId(), 0));
    erasure.exportPrepared(id);
    var e = store.export(id, "PREPARED");
    assertThat(json.readValue(e.record(), Erasures.Journal.class).lineage())
        .isEqualTo(UUID.fromString("48139cc7-748b-4b1f-a4b8-bdef004963df"));
    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(s -> store.prepare(user.userId(), 0, UUID.randomUUID())))
        .hasMessage("RECOVERY_LINEAGE_MISMATCH");
    var receipt = new RecoveryArchive.Receipt(e.id(), e.digest());
    archive.verify(receipt);
    new com.flyvo.socialcore.platform.infrastructure.recovery.SyntheticRecoveryArchive(
            new org.springframework.mock.env.MockEnvironment()
                .withProperty("app.erasure.policy", AccountErasure.POLICY)
                .withProperty("app.erasure.archive-directory", ARCHIVE.toString()))
        .verify(receipt);
    erasure.exportPrepared(id);
    var record = ARCHIVE.resolve(e.id() + ".record");
    byte[] original = Files.readAllBytes(record);
    try {
      Files.writeString(record, "corrupted synthetic record");
      assertThatThrownBy(() -> archive.verify(receipt)).hasMessage("ARCHIVE_INTEGRITY_FAILURE");
      assertThatThrownBy(() -> erasure.exportPrepared(id)).hasMessage("ARCHIVE_INTEGRITY_FAILURE");
    } finally {
      Files.write(record, original);
    }
    assertThatThrownBy(
            () -> archive.verify(new RecoveryArchive.Receipt(UUID.randomUUID(), e.digest())))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> tx().executeWithoutResult(s -> archive.verify(receipt)))
        .hasMessage("ARCHIVE_UNDER_TRANSACTION");
  }

  @Test
  void staleAuthVersionAndOwnershipAreRecheckedAfterPreparedExport() {
    var user = actor(false);
    var id = tx().execute(s -> erasure.prepare(user.userId(), 0));
    erasure.exportPrepared(id);
    tx().executeWithoutResult(
            s -> identity.revoke(user.sessionId(), "TEST_REVOKED", Instant.now()));
    assertThatThrownBy(() -> auth.close(user, 0)).isInstanceOf(Failure.class);
    assertThat(queries.find(user.userId()).active()).isTrue();
    assertThat(store.request(user.userId())).isNull();
  }

  @Test
  void adminRoutesDenyUuidPollingAndRequireCsrfAndVersion() throws Exception {
    var user = actor(false);
    auth.close(user, 0);
    var outsider = actor(false);
    var admin = actor(true);
    String url = "/api/v1/admin/accounts/" + user.userId() + "/erasure";
    mvc.perform(get(url)).andExpect(status().isUnauthorized());
    mvc.perform(get(url).header("Authorization", "Bearer " + tokens.issue(outsider)))
        .andExpect(status().isForbidden());
    mvc.perform(get(url).header("Authorization", "Bearer " + tokens.issue(admin)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.policy").value("isolated-synthetic-v1"));
    mvc.perform(
            post(url + "/resumes")
                .header("Authorization", "Bearer " + tokens.issue(admin))
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content("{\"step\":\"IDENTITY\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(url + "/resumes")
                .with(csrf().asHeader())
                .header("Authorization", "Bearer " + tokens.issue(admin))
                .header("Origin", "https://social-core.invalid")
                .contentType("application/json")
                .content("{\"step\":\"IDENTITY\"}"))
        .andExpect(status().isPreconditionRequired());
  }

  @Test
  void foundationOnlyWorkerSubsetCannotCompleteWithoutOtherOwnerPasses() {
    var user = actor(false);
    historical(user);
    converge(user.userId(), 45);
    assertThat(store.step(user.userId(), AccountErasure.Step.IDENTITY, false).view().status())
        .isEqualTo("DONE");
    assertThat(store.step(user.userId(), AccountErasure.Step.PROFILE, false).view().status())
        .isEqualTo("DONE");
    assertThat(store.step(user.userId(), AccountErasure.Step.TECHNICAL, false).view().status())
        .isEqualTo("DONE");
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT kind FROM platform.job WHERE payload->>'data' LIKE ?",
                String.class,
                "%" + user.userId() + "%"))
        .allMatch(
            k ->
                Set.of(
                        "IDENTITY_ERASURE",
                        "PROFILE_ERASURE",
                        "COMMUNITY_ERASURE",
                        "GRAPH_ERASURE",
                        "CONTENT_ERASURE",
                        "MEDIA_ERASURE",
                        "MODERATION_ERASURE",
                        "NOTIFICATION_ERASURE",
                        "SEARCH_ERASURE",
                        "ERASURE_RECONCILE",
                        "ERASURE_TECHNICAL")
                    .contains(k));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.erasure_step WHERE user_id=? AND status='WAITING_DEPENDENCY' AND reason='OWNER_NOT_IMPLEMENTED'",
                Integer.class,
                user.userId()))
        .isEqualTo(0);
  }

  private void ownerPass(UUID subject, AccountErasure.Step step, JobHandler handler) {
    var state = store.step(subject, step, false);
    var pass = new AccountErasure.Pass(subject, step, state.view().version(), state.execution());
    tx().executeWithoutResult(s -> handler.apply(json.writeValueAsString(pass)));
  }

  @Test
  void closeFirstDeniesOutstandingInvitationFromTerminalInviterAndNewInboundBlock() {
    var owner = actor(false);
    var subject = actor(false);
    var invitee = actor(false);
    var c =
        communities.create(
            owner,
            "synthetic-invite-" + UUID.randomUUID(),
            "Synthetic",
            "",
            "PRIVATE",
            "INVITE_ONLY",
            null);
    var initial = admissions.invite(owner, c.id(), subject.userId(), null);
    admissions.respondInvite(subject, initial.id(), "accept");
    memberships.role(owner, c.id(), subject.userId(), 0, "ADMIN");
    var invitation = admissions.invite(subject, c.id(), invitee.userId(), null);
    auth.close(subject, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    assertThatThrownBy(() -> admissions.respondInvite(invitee, invitation.id(), "accept"))
        .isInstanceOf(Failure.class)
        .hasMessage("ACCOUNT_INACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM community.membership WHERE community_id=? AND user_id=?",
                Integer.class,
                c.id(),
                invitee.userId()))
        .isZero();
    ownerPass(subject.userId(), AccountErasure.Step.GRAPH, graphErasure);
    assertThat(store.step(subject.userId(), AccountErasure.Step.GRAPH, false).view().status())
        .isEqualTo("DONE");
    assertThatThrownBy(() -> graphCommands.block(owner, subject.userId(), true))
        .isInstanceOf(Failure.class)
        .hasMessage("NOT_FOUND");
    graphCommands.block(owner, subject.userId(), false);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM graph.block WHERE blocked_id=? OR blocker_id=?",
                Integer.class,
                subject.userId(),
                subject.userId()))
        .isZero();
  }

  @Test
  void historicalActiveOwnershipAnomalyWaitsWithoutTransferringOrArchivingCommunity() {
    var subject = actor(false);
    var c =
        communities.create(
            subject,
            "synthetic-anomaly-" + UUID.randomUUID(),
            "Synthetic anomaly",
            "",
            "PRIVATE",
            "INVITE_ONLY",
            null);
    historical(subject);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    ownerPass(subject.userId(), AccountErasure.Step.COMMUNITY, communityErasure);
    assertThat(store.step(subject.userId(), AccountErasure.Step.COMMUNITY, false).view().reason())
        .isEqualTo("ACTIVE_OWNERSHIP");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM community.community WHERE id=?", String.class, c.id()))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_user_id FROM community.community WHERE id=?", UUID.class, c.id()))
        .isEqualTo(subject.userId());
    assertThat(queries.find(subject.userId()).status()).isEqualTo("DELETION_PENDING");
  }

  private void awaitDatabaseLock() throws Exception {
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",
              Integer.class)
          > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Competing command never reached its PostgreSQL account guard");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void invitationAndInboundBlockAgainstRealCloseSerializeInBothWinningOrders(boolean closeFirst)
      throws Exception {
    var owner = actor(false);
    var subject = actor(false);
    var invitee = actor(false);
    var c =
        communities.create(
            owner, "race-" + UUID.randomUUID(), "Synthetic", "", "PRIVATE", "INVITE_ONLY", null);
    admissions.respondInvite(
        subject, admissions.invite(owner, c.id(), subject.userId(), null).id(), "accept");
    memberships.role(owner, c.id(), subject.userId(), 0, "ADMIN");
    var invitation = admissions.invite(subject, c.id(), invitee.userId(), null);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    Runnable pause =
        () -> {
          entered.countDown();
          try {
            if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
              throw new AssertionError("race release deadline");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        };
    DurableWorkStore auditTarget =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
    if (closeFirst)
      org.mockito.Mockito.doAnswer(
              call -> {
                var result = call.callRealMethod();
                pause.run();
                return result;
              })
          .when(auditTarget)
          .audit(
              subject.userId(),
              "ACCOUNT_DELETION_REQUESTED",
              "account",
              subject.userId(),
              "SUCCESS",
              null);
    Runnable mutation =
        () -> {
          if (closeFirst) {
            assertThatThrownBy(() -> admissions.respondInvite(invitee, invitation.id(), "accept"))
                .hasMessage("ACCOUNT_INACTIVE");
          } else {
            tx().executeWithoutResult(
                    s -> {
                      admissions.respondInvite(invitee, invitation.id(), "accept");
                      graphCommands.block(invitee, subject.userId(), true);
                      pause.run();
                    });
          }
        };
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = pool.submit(closeFirst ? () -> auth.close(subject, 0) : mutation);
      assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var second = pool.submit(closeFirst ? mutation : () -> auth.close(subject, 0));
      try {
        awaitDatabaseLock();
      } finally {
        release.countDown();
      }
      first.get(20, java.util.concurrent.TimeUnit.SECONDS);
      second.get(20, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    if (closeFirst)
      assertThatThrownBy(() -> graphCommands.block(invitee, subject.userId(), true))
          .hasMessage("NOT_FOUND");
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    for (int i = 0; i < 10; i++) {
      ownerPass(subject.userId(), AccountErasure.Step.COMMUNITY, communityErasure);
      ownerPass(subject.userId(), AccountErasure.Step.GRAPH, graphErasure);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM graph.block WHERE blocker_id=? OR blocked_id=?",
                Integer.class,
                subject.userId(),
                subject.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM community.membership WHERE community_id=? AND user_id=? AND status='ACTIVE'",
                Integer.class,
                c.id(),
                invitee.userId()))
        .isEqualTo(closeFirst ? 0 : 1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void emptyIndexLiveSnapshotAndCloseSerializeBeforeDocumentLocksInBothOrders(boolean closeFirst)
      throws Exception {
    var subject = actor(false);
    var kind = com.flyvo.socialcore.search.api.SearchQueries.Kind.USER;
    var ids = List.of(subject.userId());
    var stale = searchSources.inspect(kind, ids).getFirst();
    assertThat(stale.tombstone()).isFalse();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    Runnable pause =
        () -> {
          entered.countDown();
          try {
            if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
              throw new AssertionError("snapshot release deadline");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        };
    DurableWorkStore auditTarget =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
    if (closeFirst)
      org.mockito.Mockito.doAnswer(
              call -> {
                var result = call.callRealMethod();
                pause.run();
                return result;
              })
          .when(auditTarget)
          .audit(
              subject.userId(),
              "ACCOUNT_DELETION_REQUESTED",
              "account",
              subject.userId(),
              "SUCCESS",
              null);
    Runnable projection =
        () ->
            tx().executeWithoutResult(
                    s -> {
                      var captured = searchSources.guard(kind, ids);
                      if (!closeFirst) pause.run();
                      searchSources.materialize(captured);
                    });
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = pool.submit(closeFirst ? () -> auth.close(subject, 0) : projection);
      assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var second = pool.submit(closeFirst ? projection : () -> auth.close(subject, 0));
      try {
        awaitDatabaseLock();
      } finally {
        release.countDown();
      }
      first.get(20, java.util.concurrent.TimeUnit.SECONDS);
      try {
        second.get(20, java.util.concurrent.TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        // A source inspection can load an account entity before its guard waits.
        // Hibernate rejects that obsolete entity version rather than refreshing it
        // inside this transaction. The real worker rolls back and retries afresh.
        if (!closeFirst) throw e;
        assertThat(e.getCause())
            .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM search.document WHERE source_kind='USER' AND source_id=?",
                    Integer.class,
                    subject.userId()))
            .isZero();
      }
    } finally {
      release.countDown();
    }
    tx().executeWithoutResult(s -> searchSources.refresh(kind, ids));
    tx().executeWithoutResult(s -> assertThat(searchIndex.upsert(stale)).isFalse());
    assertThat(
            jdbc.queryForObject(
                "SELECT erased_by_account AND tombstone AND source_key='' AND title='' AND body='' AND search_vector=''::tsvector FROM search.document WHERE source_kind='USER' AND source_id=?",
                Boolean.class,
                subject.userId()))
        .isTrue();
  }

  private void preservationExports() {
    for (int i = 0; i < 20; i++) jobs.consumeExternalJob(UUID.randomUUID(), List.of(preservation));
  }

  private void allOwners(UUID user, boolean finalize) {
    due(user);
    jobs.consumeJob(
        UUID.randomUUID(),
        dbHandlers.stream()
            .filter(h -> h.kind().endsWith("_ERASURE") || h.kind().equals("ERASURE_RECONCILE"))
            .toList());
    jobs.consumeExternalJob(
        UUID.randomUUID(),
        externalHandlers.stream()
            .filter(
                h ->
                    Set.of(
                                "ERASURE_EXPORT",
                                "ERASURE_TECHNICAL",
                                "MEDIA_ERASURE",
                                "MODERATION_PRESERVE")
                            .contains(h.kind())
                        || finalize && h.kind().equals("ERASURE_COMPLETE"))
            .toList());
  }

  private ActorContext completionReady() {
    var user = actor(false);
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=clock_timestamp()-interval '31 days',version=version+1 WHERE id=?",
        user.userId());
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    for (int n = 0;
        n < 250
            && !"RUNNABLE"
                .equals(
                    store
                        .step(user.userId(), AccountErasure.Step.COMPLETION, false)
                        .view()
                        .status());
        n++) allOwners(user.userId(), false);
    assertThat(store.step(user.userId(), AccountErasure.Step.COMPLETION, false).view().status())
        .isEqualTo("RUNNABLE");
    return user;
  }

  @Test
  void
      tenThousandGraphEdgesAndNotificationActorsConvergeInBoundedPassesAndPreserveOtherReadState() {
    var subject = actor(false);
    var recipient = actor(false);
    var other = actor(false);
    UUID ownedPost = UUID.randomUUID(),
        otherPost = UUID.randomUUID(),
        group = UUID.randomUUID(),
        shared = UUID.randomUUID();
    String prefix = "synthetic-p9aii-" + UUID.randomUUID() + ":";
    jdbc.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at) SELECT md5(?||n)::uuid,'ACTIVE',now(),now() FROM generate_series(1,10001) n",
        prefix);
    jdbc.update(
        "INSERT INTO graph.follow(follower_id,followed_id,created_at) SELECT ?,md5(?||n)::uuid,now() FROM generate_series(1,10001) n",
        subject.userId(),
        prefix);
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Synthetic target','PUBLIC','PUBLISHED',1,now(),now(),now()),(?,?,'Other synthetic body','PUBLIC','PUBLISHED',1,now(),now(),now())",
        ownedPost,
        subject.userId(),
        otherPost,
        recipient.userId());
    jdbc.update(
        "INSERT INTO notification.recipient_state(recipient_id,changed_at) VALUES (?,now())",
        recipient.userId());
    jdbc.update(
        "INSERT INTO notification.notification(id,recipient_id,kind,target_type,target_id,grouping_key,group_window_start,activity_version,read_version,last_activity_at,created_at) VALUES (?,?,'REACTION_ON_CONTENT','POST',?,'synthetic-large',now(),10001,10001,now(),now()),(?,?,'REACTION_ON_CONTENT','POST',?,'synthetic-mixed',now(),2,2,now(),now())",
        group,
        recipient.userId(),
        ownedPost,
        shared,
        recipient.userId(),
        otherPost);
    jdbc.update(
        "INSERT INTO content.reaction(id,actor_id,post_id,type,active,created_at,updated_at) SELECT md5(?||'reaction:'||n)::uuid,md5(?||n)::uuid,?,'LIKE',true,now(),now() FROM generate_series(1,10001) n",
        prefix,
        prefix,
        ownedPost);
    jdbc.update(
        "INSERT INTO notification.contribution(id,notification_id,source_event_id,actor_id,source_type,source_id,source_version,active,occurred_at,reaction_type) SELECT gen_random_uuid(),?,gen_random_uuid(),md5(?||n)::uuid,'REACTION',md5(?||'reaction:'||n)::uuid,0,true,now(),'LIKE' FROM generate_series(1,10001) n",
        group,
        prefix,
        prefix);
    jdbc.update(
        "INSERT INTO notification.source_state(recipient_id,kind,source_type,source_id,actor_id,latest_source_version,activity_decided_version,active,notification_id,updated_at) SELECT ?,'REACTION_ON_CONTENT','REACTION',md5(?||'reaction:'||n)::uuid,md5(?||n)::uuid,0,0,true,?,now() FROM generate_series(1,10001) n",
        recipient.userId(),
        prefix,
        prefix,
        group);
    for (var a : List.of(subject, other))
      jdbc.update(
          "INSERT INTO notification.contribution(id,notification_id,source_event_id,actor_id,source_type,source_id,source_version,active,occurred_at,reaction_type) VALUES (?,?,?,?,'REACTION',?,0,true,now(),'LOVE')",
          UUID.randomUUID(),
          shared,
          UUID.randomUUID(),
          a.userId(),
          UUID.randomUUID());
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=clock_timestamp()-interval '31 days',version=version+1 WHERE id=?",
        subject.userId());
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    int passes = 0;
    long peak = 0;
    while (!"RUNNABLE"
        .equals(
            store.step(subject.userId(), AccountErasure.Step.COMPLETION, false).view().status())) {
      long graphBefore = store.step(subject.userId(), AccountErasure.Step.GRAPH, false).examined();
      long notificationBefore =
          store.step(subject.userId(), AccountErasure.Step.NOTIFICATION, false).examined();
      allOwners(subject.userId(), false);
      assertThat(
              store.step(subject.userId(), AccountErasure.Step.GRAPH, false).examined()
                  - graphBefore)
          .isBetween(0L, 100L);
      assertThat(
              store.step(subject.userId(), AccountErasure.Step.NOTIFICATION, false).examined()
                  - notificationBefore)
          .isBetween(0L, 100L);
      peak = Math.max(peak, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
      assertThat(++passes).isLessThan(1800);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM graph.follow WHERE follower_id=? OR followed_id=?",
                Integer.class,
                subject.userId(),
                subject.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.notification WHERE id=?", Integer.class, group))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.contribution WHERE actor_id=?",
                Integer.class,
                subject.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.contribution WHERE notification_id=? AND actor_id=? AND active AND reaction_type='LOVE'",
                Integer.class,
                shared,
                other.userId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT read_version=2 AND activity_version=2 FROM notification.notification WHERE id=?",
                Boolean.class,
                shared))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification.source_state WHERE notification_id=? OR (actor_id IN (SELECT md5(?||n)::uuid FROM generate_series(1,10001) n) AND active)",
                Integer.class,
                group,
                prefix))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content.reaction WHERE post_id=? AND type='LIKE' AND active",
                Integer.class,
                ownedPost))
        .isEqualTo(10001);
    System.out.println(
        "P9AII_LARGE_GRAPH_NOTIFICATION passes="
            + passes
            + " maxCollectionItems=100 peakHeapBytes="
            + peak
            + " graphEdges=10001 targetActors=10001");
  }

  private String completionPass(UUID user) {
    var state = store.step(user, AccountErasure.Step.COMPLETION, false);
    return json.writeValueAsString(
        new AccountErasure.Pass(
            user, AccountErasure.Step.COMPLETION, state.view().version(), state.execution()));
  }

  private ExternalJobHandler.Lease completionLease(UUID user) {
    var state = store.step(user, AccountErasure.Step.COMPLETION, false);
    return new ExternalJobHandler.Lease(
        1, 1, Instant.now().plusSeconds(60), state.execution().toString());
  }

  @Test
  void realOwnersAndIndependentNonrecursiveCertificateFinalizeEvenNoDataSubjectExactlyOnce() {
    var user = completionReady();
    for (int n = 0; n < 20 && !"DELETED".equals(queries.find(user.userId()).status()); n++)
      allOwners(user.userId(), true);
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.erasure_step WHERE user_id=? AND status='DONE' AND proof_version=version",
                Integer.class,
                user.userId()))
        .isEqualTo(12);
    var export = store.export(store.request(user.userId()).id(), "COMPLETION");
    assertThat(export.receipt()).isEqualTo(export.digest());
    var certificate =
        json.readValue(
            export.record(), com.flyvo.socialcore.account.domain.ErasureCertificate.class);
    assertThat(certificate.prerequisites())
        .hasSize(11)
        .noneMatch(p -> p.step().equals("completion-manifest"));
    assertThat(
            jdbc.queryForObject(
                "SELECT account.completion_proven(?,?)",
                Boolean.class,
                user.userId(),
                export.digest()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='AccountDeletionCompleted'",
                Integer.class,
                user.userId()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE account.user_account SET status='ACTIVE' WHERE id=?", user.userId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(
                        s -> {
                          queries.guard(user.userId(), false);
                          erasure.invalidateModeration(List.of(user.userId()));
                        }))
        .isInstanceOf(Failure.class);
  }

  @Test
  void changedPrerequisiteVectorRejectsOldCertificateAndSupersededFailureWithoutFinalSideEffects() {
    var user = completionReady();
    String payload = completionPass(user.userId());
    var lease = completionLease(user.userId());
    String prepared = tx().execute(s -> completion.prepare(payload, lease)),
        receipt = completion.execute(prepared);
    var original = json.readValue(prepared, ErasureCompletion.Prepared.class);
    tx().executeWithoutResult(
            s -> {
              queries.guard(user.userId(), false);
              erasure.invalidateModeration(List.of(user.userId()));
            });
    tx().executeWithoutResult(s -> completion.complete(payload, lease, prepared, receipt));
    tx().executeWithoutResult(s -> completion.failed(payload, lease));
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
    assertThat(store.export(original.attempt()).receipt()).isNull();
    for (int n = 0; n < 150 && !"DELETED".equals(queries.find(user.userId()).status()); n++)
      allOwners(user.userId(), true);
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.erasure_export WHERE intent_id=? AND operation='COMPLETION' AND receipt_digest=digest",
                Integer.class,
                store.request(user.userId()).id()))
        .isEqualTo(1);
    assertThat(store.export(original.attempt()).receipt()).isNull();
  }

  @Test
  void failedFinalAuditRollsBackReceiptCheckpointAccountAndEventAndSameAttemptCanRetry() {
    var user = completionReady();
    String payload = completionPass(user.userId());
    var lease = completionLease(user.userId());
    String prepared = tx().execute(s -> completion.prepare(payload, lease)),
        receipt = completion.execute(prepared);
    var original = json.readValue(prepared, ErasureCompletion.Prepared.class);
    DurableWorkStore auditTarget =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
    org.mockito.Mockito.doThrow(new IllegalStateException("SYNTHETIC_AUDIT_ROLLBACK"))
        .when(auditTarget)
        .audit(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_ERASURE_COMPLETED"),
            org.mockito.ArgumentMatchers.eq("account"),
            org.mockito.ArgumentMatchers.eq(user.userId()),
            org.mockito.ArgumentMatchers.eq("SUCCESS"),
            org.mockito.ArgumentMatchers.isNull());
    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(
                        s -> completion.complete(payload, lease, prepared, receipt)))
        .hasMessage("SYNTHETIC_AUDIT_ROLLBACK");
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
    assertThat(store.export(original.attempt()).receipt()).isNull();
    assertThat(store.step(user.userId(), AccountErasure.Step.COMPLETION, false).view().status())
        .isEqualTo("RUNNABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='AccountDeletionCompleted'",
                Integer.class,
                user.userId()))
        .isZero();
    org.mockito.Mockito.doCallRealMethod()
        .when(auditTarget)
        .audit(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACCOUNT_ERASURE_COMPLETED"),
            org.mockito.ArgumentMatchers.eq("account"),
            org.mockito.ArgumentMatchers.eq(user.userId()),
            org.mockito.ArgumentMatchers.eq("SUCCESS"),
            org.mockito.ArgumentMatchers.isNull());
    tx().executeWithoutResult(s -> completion.complete(payload, lease, prepared, receipt));
    tx().executeWithoutResult(s -> completion.complete(payload, lease, prepared, receipt));
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='AccountDeletionCompleted'",
                Integer.class,
                user.userId()))
        .isEqualTo(1);
  }

  @Test
  void
      malformedIndependentlyStoredCertificateCannotExploitMissingJsonBindingsOrUnknownResourceOwner() {
    var user = completionReady();
    String payload = completionPass(user.userId());
    var lease = completionLease(user.userId());
    String prepared = tx().execute(s -> completion.prepare(payload, lease));
    var captured = json.readValue(prepared, ErasureCompletion.Prepared.class);
    var value = (tools.jackson.databind.node.ObjectNode) json.readTree(captured.record());
    value.remove("lineage");
    String malformed = json.writeValueAsString(value), digest = ErasureProtocol.digest(malformed);
    UUID id = UUID.randomUUID();
    tx().executeWithoutResult(
            s ->
                store.addExport(
                    id,
                    store.request(user.userId()).id(),
                    "COMPLETION",
                    "account",
                    null,
                    null,
                    malformed,
                    digest));
    var receipt =
        archive.append(id, digest, malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    archive.verify(receipt);
    tx().executeWithoutResult(s -> store.receipt(id, receipt.digest()));
    assertThat(
            jdbc.queryForObject(
                "SELECT account.completion_proven(?,?)", Boolean.class, user.userId(), digest))
        .isFalse();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE account.erasure_step SET status='DONE',version=version+1,proof_version=version+1,proof=?,completed_at=clock_timestamp() WHERE user_id=? AND module='account' AND step='completion-manifest'",
                    digest,
                    user.userId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(
                        s ->
                            store.addExport(
                                UUID.randomUUID(),
                                store.request(user.userId()).id(),
                                "RESOURCE",
                                "moderation",
                                "MEDIA_ASSET",
                                List.of(UUID.randomUUID()),
                                "{}",
                                ErasureProtocol.digest("{}"))))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
  }

  @Autowired com.flyvo.socialcore.moderation.application.port.CaseStore caseStore;
  @Autowired com.flyvo.socialcore.moderation.application.port.PreservationStore preservationStore;
  @Autowired com.flyvo.socialcore.moderation.application.port.ModerationActions moderationActions;

  private static final Instant SOURCE_ANCHOR = Instant.parse("2020-02-29T12:34:56.123456Z");

  private com.flyvo.socialcore.moderation.application.port.CaseStore.Root provenanceRoot(
      String type, String status, ActorContext admin, ActorContext subject) {
    UUID id = UUID.randomUUID();
    tx().executeWithoutResult(
            s -> {
              if (type.equals("REPORT")) {
                jdbc.update(
                    "INSERT INTO moderation.report(id,reporter_id,target_type,target_id,source_author_id,category,status,resolved_at,created_at,updated_at) VALUES (?,?,'USER',?,?,'SPAM',?,?,?,?)",
                    id,
                    admin.userId(),
                    subject.userId(),
                    subject.userId(),
                    status,
                    status.equals("OPEN") ? null : SOURCE_ANCHOR.atOffset(ZoneOffset.UTC),
                    SOURCE_ANCHOR.minusSeconds(86400).atOffset(ZoneOffset.UTC),
                    SOURCE_ANCHOR.atOffset(ZoneOffset.UTC));
                jdbc.update(
                    "INSERT INTO moderation.report_evidence(report_id,ciphertext,key_id) VALUES (?,?,?)",
                    id,
                    secrets.encrypt("report:" + id, "Synthetic private evidence MUST NOT EXPORT"),
                    secrets.keyId());
              } else
                moderationActions.appendCase(
                    id,
                    null,
                    admin.userId(),
                    null,
                    "ACCOUNT",
                    subject.userId(),
                    "SUSPEND_ACCOUNT",
                    "Synthetic private reason MUST NOT EXPORT",
                    "{\"private\":\"Synthetic before MUST NOT EXPORT\"}",
                    "{\"private\":\"Synthetic after MUST NOT EXPORT\"}",
                    "synthetic-provenance",
                    SOURCE_ANCHOR);
            });
    return new com.flyvo.socialcore.moderation.application.port.CaseStore.Root(
        com.flyvo.socialcore.moderation.api.CaseOperations.Root.valueOf(type), id);
  }

  private tools.jackson.databind.JsonNode archivedPreservation(UUID id) throws Exception {
    var e = preservationStore.find(id);
    assertThat(e.receipt()).isEqualTo(e.digest());
    archive.verify(new RecoveryArchive.Receipt(id, e.digest()));
    String bytes = Files.readString(ARCHIVE.resolve(id + ".record"));
    assertThat(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(RecoveryArchive.MAX_RECORD_BYTES);
    assertThat(bytes)
        .isEqualTo(e.record())
        .doesNotContain("MUST NOT EXPORT", "ciphertext", "keyId", "holdReason");
    return json.readTree(bytes);
  }

  private void assertEligibility(
      tools.jackson.databind.JsonNode body, String type, boolean unresolved) {
    assertThat(body.path("formatVersion").asInt()).isEqualTo(2);
    var e = body.path("eligibility");
    assertThat(e.path("anchor").asText())
        .isEqualTo(type.equals("REPORT") ? "REPORT_RESOLVED_AT" : "ACTION_CREATED_AT");
    assertThat(e.path("state").asText()).isEqualTo(unresolved ? "UNRESOLVED" : "KNOWN");
    if (unresolved) {
      assertThat(e.path("anchorAt").isNull()).isTrue();
      assertThat(e.path("eligibleAt").isNull()).isTrue();
    } else {
      assertThat(Instant.parse(e.path("anchorAt").asText())).isEqualTo(SOURCE_ANCHOR);
      assertThat(Instant.parse(e.path("eligibleAt").asText()))
          .isEqualTo(SOURCE_ANCHOR.plusSeconds(365L * 86400));
      assertThat(Instant.parse(body.path("recordedAt").asText()))
          .isAfter(SOURCE_ANCHOR.plusSeconds(366L * 86400));
    }
  }

  private com.flyvo.socialcore.moderation.application.ModerationPreservation.Baseline baseline(
      com.flyvo.socialcore.moderation.application.port.CaseStore.Root root) {
    return tx().execute(s -> preservation.baseline(caseStore.control(root, true)));
  }

  private void baselineToReceipt(
      com.flyvo.socialcore.moderation.application.port.CaseStore.Root root) {
    for (int n = 0; n < 10; n++) {
      var r = baseline(root);
      assertThat(r.examined()).isBetween(0, 100);
      if (r.ready()) return;
      preservationExports();
    }
    throw new AssertionError("Bounded preservation fixture did not converge");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"OPEN", "RESOLVED", "DISMISSED", "STANDALONE"})
  void preservationArchiveUsesAuthoritativeAgeAnchorRatherThanExportTime(String status)
      throws Exception {
    String type = status.equals("STANDALONE") ? "ACTION" : "REPORT";
    var root = provenanceRoot(type, status, actor(true), actor(false));
    baselineToReceipt(root);
    var ids =
        jdbc.queryForList(
            "SELECT id FROM moderation.preservation_export WHERE root_id=? AND format_version=2",
            UUID.class,
            root.id());
    assertThat(ids).hasSize(1);
    assertEligibility(archivedPreservation(ids.getFirst()), type, status.equals("OPEN"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"REPORT", "ACTION"})
  void preservationHoldAndReleaseKeepExactEligibilityAndOriginalHistoryTimes(String type)
      throws Exception {
    var admin = actor(true);
    var root = provenanceRoot(type, "DISMISSED", admin, actor(false));
    Instant until =
        Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var held =
        cases.hold(
            admin,
            null,
            root.type(),
            root.id(),
            0,
            new com.flyvo.socialcore.moderation.api.CaseOperations.Hold(
                until, "SYNTHETIC_PRIVATE_HOLD"));
    cases.hold(
        admin,
        null,
        root.type(),
        root.id(),
        held.version(),
        new com.flyvo.socialcore.moderation.api.CaseOperations.Hold(null, null));
    preservationExports();
    baselineToReceipt(root);
    var ids =
        jdbc.queryForList(
            "SELECT id FROM moderation.preservation_export WHERE root_id=? ORDER BY created_at,id",
            UUID.class,
            root.id());
    assertThat(ids).hasSize(5);
    for (var id : ids) {
      var b = archivedPreservation(id);
      assertEligibility(b, type, false);
      if (b.path("operation").asText().equals("PRESERVE")) {
        if (b.path("version").asLong() == 0) {
          assertThat(b.path("holdUntil").isNull()).isTrue();
          assertThat(Instant.parse(b.path("proposedHoldUntil").asText())).isEqualTo(until);
        } else {
          assertThat(Instant.parse(b.path("holdUntil").asText())).isEqualTo(until);
          assertThat(b.path("proposedHoldUntil").isNull()).isTrue();
        }
      }
      if (b.path("operation").asText().equals("COMMITTED")) {
        if (b.path("version").asLong() == 1)
          assertThat(Instant.parse(b.path("holdUntil").asText())).isEqualTo(until);
        else assertThat(b.path("holdUntil").isNull()).isTrue();
      }
      for (var h : b.path("history")) {
        Instant source =
            jdbc.queryForObject(
                    "SELECT created_at FROM moderation.case_history WHERE id=?",
                    OffsetDateTime.class,
                    UUID.fromString(h.path("id").asText()))
                .toInstant();
        assertThat(Instant.parse(h.path("createdAt").asText())).isEqualTo(source);
      }
    }
  }

  private UUID seedLegacyCompletedBaseline(
      com.flyvo.socialcore.moderation.application.port.CaseStore.Root root) {
    UUID id = UUID.randomUUID();
    var control = tx().execute(t -> caseStore.control(root, true));
    String record =
        json.writeValueAsString(
            Map.of(
                "id",
                id,
                "operation",
                "BASELINE",
                "root",
                root,
                "version",
                control.version(),
                "status",
                "DISMISSED",
                "recordedAt",
                Instant.now(),
                "history",
                List.of()));
    String digest = HexFormat.of().formatHex(secrets.hash(record));
    jdbc.update(
        "INSERT INTO moderation.preservation_export(id,root_type,root_id,root_version,operation,record,digest,execution_id) VALUES (?,?,?,?,'BASELINE',?,?,?)",
        id,
        root.type().name(),
        root.id(),
        control.version(),
        record,
        digest,
        id);
    var receipt =
        archive.append(id, digest, record.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    archive.verify(receipt);
    tx().executeWithoutResult(
            s -> {
              preservationStore.receipt(id, digest);
              jdbc.update(
                  "INSERT INTO moderation.preservation_state(root_type,root_id,root_version,after_history,complete) VALUES (?,?,?, ?,true)",
                  root.type().name(),
                  root.id(),
                  caseStore.control(root, true).version(),
                  UUID.randomUUID());
            });
    return id;
  }

  @Test
  void legacyCompletedBaselineIsRecapturedInBoundedTimestampedPagesWithoutRewritingOldReceipt()
      throws Exception {
    var admin = actor(true);
    var subject = actor(false);
    var root = provenanceRoot("REPORT", "DISMISSED", admin, subject);
    jdbc.update(
        "INSERT INTO moderation.case_history(id,report_id,actor_id,operation,created_at) SELECT gen_random_uuid(),?,?,'SYNTHETIC_LEGACY',?::timestamptz+n*interval '1 second' FROM generate_series(1,205) n",
        root.id(),
        admin.userId(),
        SOURCE_ANCHOR.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    // First prove upgrade selection also includes a root whose bytes are already redacted.
    tx().executeWithoutResult(s -> caseStore.redact(root, 100));
    UUID old = seedLegacyCompletedBaseline(root);
    String original = preservationStore.find(old).record();
    assertThat(preservationStore.pendingRoot(subject.userId(), null, null)).isEqualTo(root);
    assertThat(baseline(root).ready()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT format_version=2 AND NOT complete AND after_history IS NULL FROM moderation.preservation_state WHERE root_id=?",
                Boolean.class,
                root.id()))
        .isTrue();
    baselineToReceipt(root);
    assertThat(archivedPreservation(old).has("formatVersion")).isFalse();
    assertThat(preservationStore.find(old).record()).isEqualTo(original);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.preservation_export SET format_version=2 WHERE id=?", old))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var ids =
        jdbc.queryForList(
            "SELECT id FROM moderation.preservation_export WHERE root_id=? AND format_version=2 ORDER BY created_at,id",
            UUID.class,
            root.id());
    assertThat(ids.size()).isGreaterThanOrEqualTo(3);
    // More than100 source rows remain after the first byte-bounded page.
    assertThat(archivedPreservation(ids.getFirst()).path("history").size()).isLessThan(100);
    var seen = new HashSet<UUID>();
    for (var id : ids) {
      var b = archivedPreservation(id);
      assertEligibility(b, "REPORT", false);
      assertThat(b.path("retentionState").asText()).isEqualTo("DONE");
      assertThat(b.path("history").size()).isBetween(1, 100);
      for (var h : b.path("history")) {
        UUID hid = UUID.fromString(h.path("id").asText());
        assertThat(seen.add(hid)).isTrue();
        assertThat(Instant.parse(h.path("createdAt").asText()))
            .isEqualTo(
                jdbc.queryForObject(
                        "SELECT created_at FROM moderation.case_history WHERE id=?",
                        OffsetDateTime.class,
                        hid)
                    .toInstant());
      }
    }
    assertThat(seen)
        .hasSize(
            jdbc.queryForObject(
                "SELECT count(*) FROM moderation.case_history WHERE report_id=?",
                Integer.class,
                root.id()));
    assertThat(preservationStore.pendingRoot(subject.userId(), null, null)).isNull();
  }

  @Test
  void oldRootVersionReceiptCannotCompleteSuccessorProvenanceBaseline() throws Exception {
    var admin = actor(true);
    var root = provenanceRoot("REPORT", "OPEN", admin, actor(false));
    baseline(root);
    UUID old =
        jdbc.queryForObject(
            "SELECT pending_export FROM moderation.preservation_state WHERE root_id=?",
            UUID.class,
            root.id());
    var e = preservationStore.find(old);
    var lease =
        new ExternalJobHandler.Lease(1, 1, Instant.now().plusSeconds(60), e.execution().toString());
    String prepared = tx().execute(s -> preservation.prepare(old.toString(), lease));
    cases.hold(
        admin,
        null,
        root.type(),
        root.id(),
        0,
        new com.flyvo.socialcore.moderation.api.CaseOperations.Hold(
            Instant.now().plusSeconds(3600), "SYNTHETIC_HOLD"));
    baseline(root);
    UUID next =
        jdbc.queryForObject(
            "SELECT pending_export FROM moderation.preservation_state WHERE root_id=?",
            UUID.class,
            root.id());
    assertThat(next).isNotEqualTo(old);
    String receipt = preservation.execute(prepared);
    tx().executeWithoutResult(s -> preservation.complete(old.toString(), lease, prepared, receipt));
    assertThat(archivedPreservation(old).path("version").asLong()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT NOT complete AND pending_export=? AND root_version=1 FROM moderation.preservation_state WHERE root_id=?",
                Boolean.class,
                next,
                root.id()))
        .isTrue();
    baselineToReceipt(root);
    assertThat(
            jdbc.queryForObject(
                "SELECT complete AND format_version=2 AND root_version=1 FROM moderation.preservation_state WHERE root_id=?",
                Boolean.class,
                root.id()))
        .isTrue();
  }

  @Test
  void preservationDeadExportNeedsExplicitAuditedAdminResumeAndRejectsSupersededCallbacks()
      throws Exception {
    var subject = actor(false);
    var reporter = actor(false);
    var admin = actor(true);
    UUID root = report(reporter, subject);
    auth.close(subject, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    ownerPass(subject.userId(), AccountErasure.Step.MODERATION, moderationErasure);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM moderation.preservation_export WHERE root_id=?", UUID.class, root);
    UUID old =
        jdbc.queryForObject(
            "SELECT execution_id FROM moderation.preservation_export WHERE id=?", UUID.class, id);
    String record =
        jdbc.queryForObject(
            "SELECT record FROM moderation.preservation_export WHERE id=?", String.class, id);
    org.mockito.Mockito.doThrow(new Failure(503, "SYNTHETIC_ARCHIVE_OUTAGE"))
        .when(archive)
        .append(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    for (int n = 0; n < 10; n++) {
      jdbc.update(
          "UPDATE platform.job SET available_at=clock_timestamp() WHERE kind='MODERATION_PRESERVE' AND dedup_key=?",
          old.toString());
      assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(preservation))).isFalse();
    }
    assertThat(scoped.executionStatus("MODERATION_PRESERVE", old)).isEqualTo("DEAD");
    assertThatThrownBy(
            () ->
                cases.maintenance(
                    reporter,
                    com.flyvo.socialcore.moderation.api.CaseOperations.Maintenance.PRESERVATION,
                    true))
        .isInstanceOf(Failure.class)
        .hasMessage("ACCESS_DENIED");
    mvc.perform(
            post("/api/v1/admin/moderation/maintenance/PRESERVATION")
                .header("Authorization", "Bearer " + tokens.issue(admin))
                .header("Origin", "https://social-core.invalid"))
        .andExpect(status().isForbidden());
    var progress =
        cases.maintenance(
            admin,
            com.flyvo.socialcore.moderation.api.CaseOperations.Maintenance.PRESERVATION,
            true);
    assertThat(progress.preservationFailures()).isGreaterThanOrEqualTo(10);
    UUID current =
        jdbc.queryForObject(
            "SELECT execution_id FROM moderation.preservation_export WHERE id=?", UUID.class, id);
    assertThat(current).isNotEqualTo(old);
    org.mockito.Mockito.doCallRealMethod()
        .when(archive)
        .append(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertThat(jobs.consumeExternalJob(UUID.randomUUID(), List.of(preservation))).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT receipt_digest=digest FROM moderation.preservation_export WHERE id=?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT record FROM moderation.preservation_export WHERE id=?", String.class, id))
        .isEqualTo(record);
    assertThat(
            jdbc.queryForObject(
                "SELECT failures FROM moderation.preservation_export WHERE id=?", Long.class, id))
        .isEqualTo(10);
    var staleLease =
        new ExternalJobHandler.Lease(1, 10, Instant.now().plusSeconds(60), old.toString());
    assertThat(tx().<String>execute(s -> preservation.prepare(id.toString(), staleLease)))
        .isEqualTo("STALE");
    tx().executeWithoutResult(s -> preservation.failed(id.toString(), staleLease));
    assertThat(
            jdbc.queryForObject(
                "SELECT failures FROM moderation.preservation_export WHERE id=?", Long.class, id))
        .isEqualTo(10);
    assertThat(scoped.executionStatus("MODERATION_PRESERVE", old)).isEqualTo("DEAD");
  }

  private UUID report(ActorContext reporter, ActorContext subject) {
    return reports
        .submit(
            reporter,
            new com.flyvo.socialcore.moderation.api.Reports.Submit(
                com.flyvo.socialcore.moderation.api.Reports.Target.USER,
                subject.userId(),
                com.flyvo.socialcore.moderation.api.Reports.Category.SPAM,
                "Synthetic case evidence"),
            UUID.randomUUID().toString())
        .id();
  }

  @Test
  void pendingSubjectCaseRemainsReviewableWithEncryptedResolutionAndCurrentProofInvalidation() {
    var subject = actor(false);
    var reporter = actor(false);
    var admin = actor(true);
    UUID id = report(reporter, subject);
    auth.close(subject, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    ownerPass(subject.userId(), AccountErasure.Step.MODERATION, moderationErasure);
    preservationExports();
    ownerPass(subject.userId(), AccountErasure.Step.MODERATION, moderationErasure);
    assertThat(store.step(subject.userId(), AccountErasure.Step.MODERATION, false).view().status())
        .isEqualTo("WAITING_CASE");
    assertThat(reports.evidence(admin, null, id).detail()).isEqualTo("Synthetic case evidence");
    long prior =
        store.step(subject.userId(), AccountErasure.Step.MODERATION, false).view().version();
    var result =
        reports.resolve(
            admin,
            null,
            0,
            new com.flyvo.socialcore.moderation.api.Reports.Resolution(
                id,
                com.flyvo.socialcore.moderation.api.Reports.Action.DISMISS_REPORT,
                "Synthetic legitimate dismissal",
                null,
                null));
    assertThat(result.report().status()).isEqualTo("DISMISSED");
    assertThat(store.step(subject.userId(), AccountErasure.Step.MODERATION, false).view().version())
        .isGreaterThan(prior);
    assertThat(
            jdbc.queryForObject(
                "SELECT ciphertext IS NOT NULL AND key_id IS NOT NULL AND redacted_at IS NULL FROM moderation.action_detail WHERE action_id=?",
                Boolean.class,
                result.actionId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM moderation.action_detail WHERE legacy_payload::text LIKE '%Synthetic legitimate dismissal%'",
                Integer.class))
        .isZero();
    assertThat(cases.action(admin, null, result.actionId()).reason())
        .isEqualTo("Synthetic legitimate dismissal");
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    ownerPass(subject.userId(), AccountErasure.Step.MODERATION, moderationErasure);
    preservationExports();
    ownerPass(subject.userId(), AccountErasure.Step.MODERATION, moderationErasure);
    assertThat(store.step(subject.userId(), AccountErasure.Step.MODERATION, false).view().status())
        .isEqualTo("WAITING_RETENTION");
    assertThat(queries.find(subject.userId()).status()).isEqualTo("DELETION_PENDING");
    assertThatThrownBy(() -> report(actor(false), subject)).isInstanceOf(Failure.class);
  }

  @Test
  void preservationAppendFailureAndAuthorityChangeLeavePriorHoldEffective() {
    var subject = actor(false);
    var reporter = actor(false);
    var admin = actor(true);
    UUID id = report(reporter, subject);
    var first =
        cases.hold(
            admin,
            null,
            com.flyvo.socialcore.moderation.api.CaseOperations.Root.REPORT,
            id,
            0,
            new com.flyvo.socialcore.moderation.api.CaseOperations.Hold(
                Instant.now().plusSeconds(7200), "SYNTHETIC_HOLD"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM moderation.preservation_export WHERE root_id=? AND operation='PRESERVE' AND receipt_digest=digest",
                Integer.class,
                id))
        .isEqualTo(1);
    org.mockito.Mockito.doThrow(new Failure(503, "SYNTHETIC_ARCHIVE_OUTAGE"))
        .when(archive)
        .append(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                cases.hold(
                    admin,
                    null,
                    com.flyvo.socialcore.moderation.api.CaseOperations.Root.REPORT,
                    id,
                    first.version(),
                    new com.flyvo.socialcore.moderation.api.CaseOperations.Hold(
                        Instant.now().plusSeconds(10800), "SYNTHETIC_RENEWAL")))
        .hasMessage("SYNTHETIC_ARCHIVE_OUTAGE");
    assertThat(
            jdbc.queryForObject(
                    "SELECT hold_until FROM moderation.report WHERE id=?", OffsetDateTime.class, id)
                .toInstant())
        .isEqualTo(first.holdUntil());
    org.mockito.Mockito.doAnswer(
            call -> {
              var receipt = call.callRealMethod();
              jdbc.update(
                  "UPDATE account.user_account SET platform_role='USER' WHERE id=?",
                  admin.userId());
              return receipt;
            })
        .when(archive)
        .append(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                cases.hold(
                    admin,
                    null,
                    com.flyvo.socialcore.moderation.api.CaseOperations.Root.REPORT,
                    id,
                    first.version(),
                    new com.flyvo.socialcore.moderation.api.CaseOperations.Hold(
                        Instant.now().plusSeconds(10800), "SYNTHETIC_RENEWAL")))
        .isInstanceOf(Failure.class)
        .hasMessage("ACCESS_DENIED");
    assertThat(
            jdbc.queryForObject(
                    "SELECT hold_until FROM moderation.report WHERE id=?", OffsetDateTime.class, id)
                .toInstant())
        .isEqualTo(first.holdUntil());
  }

  @Test
  void expiredModerationRootExportsOneWayRedactionAndConvergesWithoutDiscardingMetadata() {
    var subject = actor(false);
    var reporter = actor(false);
    UUID id = UUID.randomUUID();
    tx().executeWithoutResult(
            s -> {
              jdbc.update(
                  "INSERT INTO moderation.report(id,reporter_id,target_type,target_id,source_author_id,category,status,resolved_at,created_at,updated_at) VALUES (?,?,'USER',?,?,'SPAM','DISMISSED',now()-interval '366 days',now()-interval '367 days',now()-interval '366 days')",
                  id,
                  reporter.userId(),
                  subject.userId(),
                  subject.userId());
              jdbc.update(
                  "INSERT INTO moderation.report_evidence(report_id,ciphertext,key_id) VALUES (?,?,?)",
                  id,
                  secrets.encrypt(
                      "report:" + id,
                      json.writeValueAsString(
                          new com.flyvo.socialcore.moderation.application.ReportWorkflow.Captured(
                              Instant.now().minusSeconds(367L * 86400),
                              "Synthetic expired evidence",
                              "Synthetic summary"))),
                  secrets.keyId());
            });
    historical(subject);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    for (int n = 0;
        n < 20
            && !"DONE"
                .equals(
                    store
                        .step(subject.userId(), AccountErasure.Step.MODERATION, false)
                        .view()
                        .status());
        n++) {
      ownerPass(subject.userId(), AccountErasure.Step.MODERATION, moderationErasure);
      preservationExports();
    }
    assertThat(store.step(subject.userId(), AccountErasure.Step.MODERATION, false).view().status())
        .isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                "SELECT ciphertext IS NULL AND key_id IS NULL AND redacted_at IS NOT NULL FROM moderation.report_evidence WHERE report_id=?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT retention_state FROM moderation.report WHERE id=?", String.class, id))
        .isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM moderation.preservation_export WHERE root_id=? AND receipt_digest=digest AND record::jsonb->>'retentionState'='DONE'",
                Integer.class,
                id))
        .isGreaterThan(0);
  }

  @Test
  void repeatedCurrentMentionsShareTheHundredItemBudgetWithLastRecipientRepair() {
    var subject = actor(false);
    var other = actor(false);
    UUID post = UUID.randomUUID();
    tx().executeWithoutResult(
            s -> {
              jdbc.update(
                  "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Other synthetic body','CUSTOM','PUBLISHED',1,now(),now(),now())",
                  post,
                  other.userId());
              jdbc.update(
                  "INSERT INTO content.post_recipient(post_id,user_id) VALUES (?,?)",
                  post,
                  subject.userId());
              // A historical skewed SQL fixture also exercises offsets outside current HTTP quotas.
              jdbc.update(
                  "INSERT INTO content.post_mention(post_id,mentioned_user_id,start_offset,end_offset) SELECT ?,?,n,n+1 FROM generate_series(1,10001) n",
                  post,
                  subject.userId());
            });
    auth.close(subject, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    int passes = 0;
    while (jdbc.queryForObject(
            "SELECT count(*) FROM content.post_mention WHERE post_id=?", Integer.class, post)
        > 0) {
      int before =
          jdbc.queryForObject(
              "SELECT (SELECT count(*) FROM content.post_mention WHERE post_id=?)+(SELECT count(*) FROM content.post_recipient WHERE post_id=?)",
              Integer.class,
              post,
              post);
      long examined = store.step(subject.userId(), AccountErasure.Step.CONTENT, false).examined();
      ownerPass(subject.userId(), AccountErasure.Step.CONTENT, contentErasure);
      int after =
          jdbc.queryForObject(
              "SELECT (SELECT count(*) FROM content.post_mention WHERE post_id=?)+(SELECT count(*) FROM content.post_recipient WHERE post_id=?)",
              Integer.class,
              post,
              post);
      assertThat(before - after).isBetween(1, 100);
      assertThat(
              store.step(subject.userId(), AccountErasure.Step.CONTENT, false).examined()
                  - examined)
          .isEqualTo(before - after);
      assertThat(
              jdbc.queryForObject(
                  "SELECT audience='ONLY_ME' AND body='Other synthetic body' FROM content.post WHERE id=?",
                  Boolean.class,
                  post))
          .isTrue();
      assertThat(++passes).isLessThan(103);
    }
    assertThat(passes).isEqualTo(101);
  }

  @Test
  void contentErasureBoundsNestedCollectionsAndPreservesOtherAuthorsReplies() {
    var subject = actor(false);
    var other = actor(false);
    UUID post = UUID.randomUUID(), reply = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,link_url,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Synthetic body','https://example.invalid/synthetic','PUBLIC','PUBLISHED',10001,now(),now(),now())",
        post,
        subject.userId());
    jdbc.update(
        "INSERT INTO content.post_revision(post_id,revision_number,editor_id,body,audience_snapshot,media_ids_snapshot,mentions_snapshot,edited_at) SELECT ?,n,?,'Synthetic revision','{}','[]','[]',now() FROM generate_series(1,10001) n",
        post,
        subject.userId());
    jdbc.update(
        "INSERT INTO content.comment(id,post_id,author_id,body,status,created_at,updated_at) SELECT gen_random_uuid(),?,?,'Synthetic comment','VISIBLE',now(),now() FROM generate_series(1,10001)",
        post,
        subject.userId());
    jdbc.update(
        "INSERT INTO content.comment(id,post_id,author_id,reply_to_user_id,body,status,created_at,updated_at) VALUES (?,?,?,?,'Other synthetic reply','VISIBLE',now(),now())",
        reply,
        post,
        other.userId(),
        subject.userId());
    jdbc.update(
        "INSERT INTO content.reaction(id,actor_id,post_id,type,active,created_at,updated_at) VALUES (?,?,?,'LOVE',true,now(),now())",
        UUID.randomUUID(),
        subject.userId(),
        post);
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=clock_timestamp()-interval '31 days',version=version+1 WHERE id=?",
        subject.userId());
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    int passes = 0;
    while (!"DONE"
        .equals(store.step(subject.userId(), AccountErasure.Step.CONTENT, false).view().status())) {
      long before = store.step(subject.userId(), AccountErasure.Step.CONTENT, false).examined();
      ownerPass(subject.userId(), AccountErasure.Step.CONTENT, contentErasure);
      long after = store.step(subject.userId(), AccountErasure.Step.CONTENT, false).examined();
      assertThat(after - before).isBetween(0L, 100L);
      assertThat(++passes).isLessThan(500);
    }
    assertThat(passes).isGreaterThan(300);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content.post_revision WHERE post_id=?", Integer.class, post))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT body FROM content.post WHERE id=?", String.class, post))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content.comment WHERE author_id=? AND body<>''",
                Integer.class,
                subject.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject("SELECT body FROM content.comment WHERE id=?", String.class, reply))
        .isEqualTo("Other synthetic reply");
    assertThat(
            jdbc.queryForObject(
                "SELECT reply_to_user_id FROM content.comment WHERE id=?", UUID.class, reply))
        .isEqualTo(subject.userId());
    assertThat(
            jdbc.queryForObject(
                "SELECT erased_by_account AND NOT active AND type IS NULL FROM content.reaction WHERE actor_id=?",
                Boolean.class,
                subject.userId()))
        .isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE content.reaction SET type='LIKE',erased_by_account=false WHERE actor_id=?",
                    subject.userId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void contentRetainsAuthoredBytesUntilThirtyDaysAndQueuedParentRetentionIsHarmless() {
    var subject = actor(false);
    var other = actor(false);
    UUID post = UUID.randomUUID(), reply = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Synthetic retained body','PUBLIC','PUBLISHED',1,now(),now(),now())",
        post,
        subject.userId());
    jdbc.update(
        "INSERT INTO content.comment(id,post_id,author_id,body,status,created_at,updated_at) VALUES (?,?,?,'Other retained reply','VISIBLE',now(),now())",
        reply,
        post,
        other.userId());
    auth.close(subject, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    for (int i = 0; i < 6; i++)
      ownerPass(subject.userId(), AccountErasure.Step.CONTENT, contentErasure);
    assertThat(store.step(subject.userId(), AccountErasure.Step.CONTENT, false).view().status())
        .isEqualTo("WAITING_RETENTION");
    assertThat(jdbc.queryForObject("SELECT body FROM content.post WHERE id=?", String.class, post))
        .isEqualTo("Synthetic retained body");
    tx().executeWithoutResult(s -> postRetention.apply(post.toString()));
    assertThat(
            jdbc.queryForObject("SELECT body FROM content.comment WHERE id=?", String.class, reply))
        .isEqualTo("Other retained reply");
  }

  @Test
  void sourceOwnersRemoveSubjectParticipationAndReferencesWithoutWideningOthersContent() {
    var subject = actor(false);
    var owner = actor(false);
    var third = actor(false);
    UUID community = UUID.randomUUID(), archived = UUID.randomUUID();
    UUID invitation = UUID.randomUUID(), request = UUID.randomUUID(), post = UUID.randomUUID();
    tx().executeWithoutResult(
            s -> {
              jdbc.update(
                  "INSERT INTO community.community(id,slug_normalized,name,description,visibility,join_mode,status,owner_user_id,created_at,updated_at) VALUES (?,'synthetic-a-source','Synthetic A','','PRIVATE','INVITE_ONLY','ACTIVE',?,now(),now())",
                  community,
                  owner.userId());
              jdbc.update(
                  "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at) VALUES (?,?,'ADMIN','ACTIVE',now(),now()),(?,?,'MEMBER','ACTIVE',now(),now())",
                  community,
                  owner.userId(),
                  community,
                  subject.userId());
              jdbc.update(
                  "INSERT INTO community.community(id,slug_normalized,name,description,visibility,join_mode,status,owner_user_id,created_at,updated_at) VALUES (?,'synthetic-a-archived','Synthetic archived','','PRIVATE','INVITE_ONLY','ARCHIVED',?,now(),now())",
                  archived,
                  subject.userId());
              jdbc.update(
                  "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at) VALUES (?,?,'ADMIN','ACTIVE',now(),now())",
                  archived,
                  subject.userId());
              jdbc.update(
                  "INSERT INTO community.invitation(id,community_id,invitee_id,inviter_id,status,expires_at,created_at,updated_at) VALUES (?,?,?,?,'PENDING',now()+interval '7 days',now(),now())",
                  invitation,
                  community,
                  third.userId(),
                  subject.userId());
              jdbc.update(
                  "INSERT INTO community.join_request(id,community_id,user_id,status,message,created_at,updated_at) VALUES (?,?,?,'PENDING','synthetic request',now(),now())",
                  request,
                  community,
                  subject.userId());
              jdbc.update(
                  "INSERT INTO graph.follow(follower_id,followed_id,created_at) VALUES (?,?,now()),(?,?,now())",
                  subject.userId(),
                  owner.userId(),
                  owner.userId(),
                  subject.userId());
              jdbc.update(
                  "INSERT INTO graph.block(blocker_id,blocked_id,created_at) VALUES (?,?,now())",
                  owner.userId(),
                  subject.userId());
              jdbc.update(
                  "INSERT INTO graph.mute(muter_id,muted_id,created_at) VALUES (?,?,now())",
                  subject.userId(),
                  owner.userId());
              jdbc.update(
                  "INSERT INTO graph.friend_request(id,requester_id,addressee_id,status,created_at,expires_at) VALUES (?,?,?,'PENDING',now(),now()+interval '7 days')",
                  UUID.randomUUID(),
                  owner.userId(),
                  subject.userId());
              jdbc.update(
                  "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Other author synthetic text','CUSTOM','PUBLISHED',1,now(),now(),now())",
                  post,
                  owner.userId());
              jdbc.update(
                  "INSERT INTO content.post_recipient(post_id,user_id) VALUES (?,?)",
                  post,
                  subject.userId());
              jdbc.update(
                  "INSERT INTO content.post_mention(post_id,mentioned_user_id,start_offset,end_offset) VALUES (?,?,0,5)",
                  post,
                  subject.userId());
              jdbc.update(
                  "INSERT INTO content.post_revision(post_id,revision_number,editor_id,body,audience_snapshot,media_ids_snapshot,mentions_snapshot,edited_at) VALUES (?,1,?,'Other author synthetic history','{}','[]','[]',now())",
                  post,
                  owner.userId());
            });
    auth.close(subject, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(subject.userId()));
    for (int i = 0;
        i < 8
            && !"DONE"
                .equals(
                    store
                        .step(subject.userId(), AccountErasure.Step.COMMUNITY, false)
                        .view()
                        .status());
        i++) ownerPass(subject.userId(), AccountErasure.Step.COMMUNITY, communityErasure);
    for (int i = 0;
        i < 8
            && !"DONE"
                .equals(
                    store.step(subject.userId(), AccountErasure.Step.GRAPH, false).view().status());
        i++) ownerPass(subject.userId(), AccountErasure.Step.GRAPH, graphErasure);
    ownerPass(subject.userId(), AccountErasure.Step.CONTENT, contentErasure);
    assertThat(store.step(subject.userId(), AccountErasure.Step.COMMUNITY, false).view().status())
        .isEqualTo("DONE");
    assertThat(store.step(subject.userId(), AccountErasure.Step.GRAPH, false).view().status())
        .isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM community.membership WHERE community_id=? AND user_id=?",
                String.class,
                community,
                subject.userId()))
        .isEqualTo("LEFT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM community.membership WHERE community_id=? AND user_id=?",
                String.class,
                archived,
                subject.userId()))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM community.invitation WHERE id=?", String.class, invitation))
        .isEqualTo("REVOKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT message FROM community.join_request WHERE id=?", String.class, request))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM graph.follow WHERE follower_id=? OR followed_id=?",
                Integer.class,
                subject.userId(),
                subject.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM graph.block WHERE blocker_id=? OR blocked_id=?",
                Integer.class,
                subject.userId(),
                subject.userId()))
        .isZero();
    assertThat(
            jdbc.queryForObject("SELECT audience FROM content.post WHERE id=?", String.class, post))
        .isEqualTo("ONLY_ME");
    assertThat(jdbc.queryForObject("SELECT body FROM content.post WHERE id=?", String.class, post))
        .isEqualTo("Other author synthetic text");
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM content.post_revision WHERE post_id=? AND revision_number=1",
                String.class,
                post))
        .isEqualTo("Other author synthetic history");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content.post_recipient WHERE post_id=?", Integer.class, post))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content.post_mention WHERE post_id=?", Integer.class, post))
        .isZero();
  }

  @Test
  void boundedRefreshChainsTokenAndRegistrationChunksDrainBeforeClaimsAreReleased() {
    var user = actor(false);
    String email = identity.email(user.userId());
    tx().executeWithoutResult(
            s -> {
              identity.credential(
                  user.userId(),
                  passwords.encode("Synthetic river planet orchard 71"),
                  Instant.now());
              for (int i = 0; i < 205; i++) {
                var id = UUID.randomUUID();
                jdbc.update(
                    "INSERT INTO identity.one_time_token(id,user_id,purpose,token_hash,expires_at,created_at) VALUES (?,?,'RESET_PASSWORD',?,now()+interval '30 minutes',now()-interval '3 hours')",
                    id,
                    user.userId(),
                    secrets.hash(secrets.randomToken()));
                scoped.mail(
                    user.userId(),
                    id.toString(),
                    email,
                    "RESET_PASSWORD",
                    secrets.randomToken(),
                    Instant.now().plusSeconds(1800));
                jdbc.update(
                    "INSERT INTO identity.registration_intent(id,email_normalized,token_hash,flow,browser_binding_hash,expires_at,created_at) VALUES (?,?,?,'LOCAL',?,now()+interval '30 minutes',now()-interval '3 hours')",
                    UUID.randomUUID(),
                    email,
                    secrets.hash(secrets.randomToken()),
                    secrets.hash("browser"));
                var token = UUID.randomUUID();
                identity.refresh(
                    token,
                    user.sessionId(),
                    i,
                    secrets.hash(secrets.randomToken()),
                    Instant.now().plusSeconds(3600),
                    Instant.now());
                if (i < 204)
                  jdbc.update(
                      "UPDATE identity.refresh_token SET consumed_at=now() WHERE id=?", token);
                if (i > 0)
                  jdbc.update(
                      "UPDATE identity.refresh_token SET replaced_by_id=? WHERE session_id=? AND generation=?",
                      token,
                      user.sessionId(),
                      i - 1);
              }
            });
    historical(user);
    for (int i = 0;
        i < 12
            && !store
                .step(user.userId(), AccountErasure.Step.IDENTITY, false)
                .checkpoint()
                .startsWith("TOKEN_EXPORT:");
        i++) tick(user.userId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.one_time_token WHERE user_id=?",
                Integer.class,
                user.userId()))
        .isEqualTo(205);
    assertThat(identity.emailOwner(email)).isEqualTo(user.userId());
    converge(user.userId(), 160);
    assertThat(store.step(user.userId(), AccountErasure.Step.IDENTITY, false).view().status())
        .as(
            "progress=%s",
            jdbc.queryForList(
                "SELECT module,step,status,checkpoint,reason,failures FROM account.erasure_step WHERE user_id=?",
                user.userId()))
        .isEqualTo("DONE");
    for (var table :
        List.of(
            "one_time_token",
            "local_credential",
            "auth_session",
            "login_email",
            "auth_identity",
            "erasure_mailbox"))
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM identity." + table + " WHERE user_id=?",
                  Integer.class,
                  user.userId()))
          .as(table)
          .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.refresh_token WHERE session_id=?",
                Integer.class,
                user.sessionId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.registration_intent WHERE email_normalized=?",
                Integer.class,
                email))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT max(cardinality(resource_ids)) FROM account.erasure_export WHERE intent_id=? AND resource_kind='MAIL_PROOF'",
                Integer.class,
                store.request(user.userId()).id()))
        .isEqualTo(100);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.secret_mail_payload p JOIN platform.work_scope s ON s.job_id=p.job_id WHERE s.scope_id=?",
                Integer.class,
                user.userId()))
        .isZero();
    String proof = secrets.randomToken();
    String browser = secrets.randomToken();
    tx().executeWithoutResult(
            s ->
                identity.intent(
                    UUID.randomUUID(),
                    email,
                    secrets.hash(proof),
                    "LOCAL",
                    null,
                    secrets.hash(browser),
                    Instant.now().plusSeconds(1800),
                    Instant.now()));
    var fresh = auth.complete(proof, browser, "Synthetic river planet orchard 72");
    var newUser = identity.session(fresh.sessionId(), false).userId();
    assertThat(newUser).isNotEqualTo(user.userId());
    assertThat(queries.find(newUser).active()).isTrue();
  }

  @Test
  void manifestReceiptBarrierPreservesLastProofMappingAndStalePassCannotAcknowledge() {
    var user = actor(false);
    auth.close(user, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    var c =
        tx().execute(
                s ->
                    erasure.begin(
                        user.userId(),
                        AccountErasure.Step.IDENTITY,
                        store
                            .step(user.userId(), AccountErasure.Step.IDENTITY, false)
                            .view()
                            .version()));
    UUID chunk =
        tx().execute(
                s -> {
                  var current =
                      erasure.begin(user.userId(), AccountErasure.Step.IDENTITY, c.version());
                  var id =
                      erasure.recordResources(current, "MAIL_PROOF", List.of(UUID.randomUUID()));
                  erasure.advance(
                      current,
                      "RESOURCE_TEST",
                      0,
                      "WAITING_EXTERNAL",
                      "RESOURCE_EXPORT_PENDING",
                      null,
                      null);
                  return id;
                });
    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(
                        s -> erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "FAKE")))
        .hasMessage("ERASURE_STALE_PASS");
    var current =
        tx().execute(
                s -> erasure.begin(user.userId(), AccountErasure.Step.IDENTITY, c.version() + 1));
    assertThatThrownBy(
            () ->
                tx().executeWithoutResult(
                        s -> erasure.advance(current, "VERIFIED", 0, "DONE", null, null, "FAKE")))
        .hasMessage("ERASURE_RESOURCE_RECEIPT_REQUIRED");
    boolean exported =
        Boolean.TRUE.equals(tx().execute(s -> erasure.resourcesExported(current, chunk)));
    assertThat(exported).isFalse();
  }

  @Test
  void scopedCancellationFencesMailButDoesNotPretendRunningSmtpHasDrained() {
    var scope = UUID.randomUUID();
    var other = UUID.randomUUID();
    tx().executeWithoutResult(
            s -> {
              scoped.mail(
                  scope,
                  UUID.randomUUID().toString(),
                  "synthetic@example.test",
                  "TEST",
                  "",
                  Instant.now().plusSeconds(1800));
              scoped.mail(
                  other,
                  UUID.randomUUID().toString(),
                  "other@example.test",
                  "TEST",
                  "",
                  Instant.now().plusSeconds(1800));
            });
    var claim = jobs.claim(UUID.randomUUID());
    var actualScope =
        jdbc.queryForObject(
            "SELECT scope_id FROM platform.work_scope WHERE job_id=?", UUID.class, claim.id());
    var drain = scoped.cancelMail(actualScope, List.of(), null);
    assertThat(drain.inFlight()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT cancellation_code FROM platform.job WHERE id=?", String.class, claim.id()))
        .isEqualTo("SCOPED_MAIL_CANCELLED");
    assertThat(jobs.finish(claim, null)).isFalse();
    assertThat(scoped.cancelMail(actualScope, List.of(), null).inFlight()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.secret_mail_payload p JOIN platform.work_scope s ON s.job_id=p.job_id WHERE s.scope_id<>? AND s.scope_id IN (?,?)",
                Integer.class,
                actualScope,
                scope,
                other))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> tx().executeWithoutResult(s -> scoped.cancelMail(scope, List.of(), null)))
        .hasMessage("CANCELLATION_UNDER_BUSINESS_TRANSACTION");
  }

  @Test
  void profileErasureReservesHandleWithoutFormerUserRedirectAndDatabaseGuardsClaims() {
    var user = actor(false);
    var handle =
        jdbc.queryForObject(
            "SELECT handle_normalized FROM profile.profile WHERE user_id=?",
            String.class,
            user.userId());
    historical(user);
    converge(user.userId(), 40);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM profile.profile WHERE user_id=?",
                Integer.class,
                user.userId()))
        .isZero();
    var row =
        jdbc.queryForMap(
            "SELECT state,user_id,expires_at FROM profile.handle_claim WHERE handle_normalized=?",
            handle);
    assertThat(row.get("state")).isEqualTo("RESERVED");
    assertThat(row.get("user_id")).isNull();
    var claimant = actor(false);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE profile.profile SET handle=?,handle_normalized=? WHERE user_id=?",
                    handle,
                    handle,
                    claimant.userId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    jdbc.update(
        "UPDATE profile.handle_claim SET expires_at=clock_timestamp() WHERE handle_normalized=?",
        handle);
    jdbc.update(
        "UPDATE profile.profile SET handle=?,handle_normalized=? WHERE user_id=?",
        handle,
        handle,
        claimant.userId());
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM profile.handle_claim WHERE handle_normalized=?",
                UUID.class,
                handle))
        .isEqualTo(claimant.userId());
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM profile.handle_claim WHERE handle_normalized=?", handle))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void invalidHistoricalGenerationRemainsVisibleAndDoesNotInventRequest() {
    var user = actor(false);
    jdbc.update(
        "UPDATE account.user_account SET status='DELETION_PENDING',deletion_requested_at=now(),version=0 WHERE id=?",
        user.userId());
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    assertThat(store.request(user.userId())).isNull();
    var p = erasure.progress(actor(true), user.userId());
    assertThat(p.requestId()).isNull();
    assertThat(p.steps().getFirst().reason()).isEqualTo("HISTORICAL_LIFECYCLE_UNPROVEN");
  }

  @Test
  void lostCommittedAckRetriesSameImmutableRecordAndTenFailuresNeedAuditedResume() {
    var user = actor(false);
    auth.close(user, 0);
    var request = store.request(user.userId());
    var committed = store.export(request.id(), "COMMITTED");
    DurableWorkStore target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
    org.mockito.Mockito.doThrow(new IllegalStateException("SYNTHETIC_AUDIT_FAILURE"))
        .when(target)
        .audit(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ERASURE_PASS"),
            org.mockito.ArgumentMatchers.eq("account"),
            org.mockito.ArgumentMatchers.eq(user.userId()),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    try {
      for (int i = 0; i < 12; i++) {
        due(user.userId());
        jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter));
      }
      assertThat(store.export(committed.id()).receipt()).isNull();
      assertThat(Files.exists(ARCHIVE.resolve(committed.id() + ".record"))).isTrue();
      assertThat(scoped.executionStatus("ERASURE_EXPORT", committed.execution())).isEqualTo("DEAD");
      assertThat(
              store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).view().status())
          .isEqualTo("FAILED");
      assertThat(
              store
                  .step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false)
                  .view()
                  .failures())
          .isEqualTo(10);
      tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM platform.job WHERE kind='ERASURE_EXPORT' AND payload->>'data'=?",
                  Integer.class,
                  committed.id().toString()))
          .isEqualTo(1);
    } finally {
      org.mockito.Mockito.reset(jobs);
    }
    var admin = actor(true);
    var current = erasure.progress(admin, user.userId());
    assertThatThrownBy(
            () ->
                erasure.resume(
                    admin,
                    user.userId(),
                    current.version() - 1,
                    AccountErasure.Step.RECOVERY_INTENT))
        .hasMessage("STALE_VERSION");
    erasure.resume(admin, user.userId(), current.version(), AccountErasure.Step.RECOVERY_INTENT);
    exports();
    assertThat(store.export(committed.id()).receipt()).isEqualTo(committed.digest());
    assertThat(
            store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).view().status())
        .isEqualTo("DONE");
    assertThat(
            store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).view().failures())
        .isEqualTo(10);
    assertThat(scoped.executionStatus("ERASURE_EXPORT", committed.execution())).isEqualTo("DEAD");
  }

  @Test
  void changedFenceAfterRealAppendCannotCommitReceiptOrStepProof() {
    var user = actor(false);
    auth.close(user, 0);
    var e = store.export(store.request(user.userId()).id(), "COMMITTED");
    var once = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              var result = call.callRealMethod();
              if (e.id().equals(call.getArgument(0)) && once.compareAndSet(false, true))
                jdbc.update(
                    "UPDATE platform.job SET lease_generation=lease_generation+1,lease_until=now()-interval '1 second' WHERE kind='ERASURE_EXPORT' AND dedup_key=?",
                    e.execution().toString());
              return result;
            })
        .when(archive)
        .append(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    try {
      due(user.userId());
      jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter)); // PREPARED duplicate.
      due(user.userId());
      jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter));
      assertThat(once).isTrue();
      assertThat(store.export(e.id()).receipt()).isNull();
      assertThat(
              store.step(user.userId(), AccountErasure.Step.RECOVERY_INTENT, false).view().status())
          .isNotEqualTo("DONE");
    } finally {
      org.mockito.Mockito.reset(archive);
    }
    due(user.userId());
    jobs.consumeExternalJob(UUID.randomUUID(), List.of(exporter));
    assertThat(store.export(e.id()).receipt()).isEqualTo(e.digest());
  }

  @Test
  void archiveOutageLeavesRetryableConservativeIntentAndActiveAccount() throws Exception {
    var user = actor(false);
    var offline = ARCHIVE.resolveSibling(ARCHIVE.getFileName() + "-offline");
    Files.move(ARCHIVE, offline);
    try {
      assertThatThrownBy(() -> auth.close(user, 0))
          .isInstanceOf(Failure.class)
          .hasMessage("RECOVERY_ARCHIVE_UNAVAILABLE");
      assertThat(queries.find(user.userId()).active()).isTrue();
      assertThat(store.request(user.userId())).isNull();
    } finally {
      Files.move(offline, ARCHIVE);
    }
    var count =
        jdbc.queryForObject(
            "SELECT count(*) FROM account.erasure_intent WHERE user_id=?",
            Integer.class,
            user.userId());
    assertThat(count).isEqualTo(1);
    auth.close(user, 0);
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
  }

  @Test
  void actualCloseRechecksSessionVersionFreshnessAndOwnershipAfterNetwork() {
    for (var change : List.of("SESSION", "VERSION", "FRESHNESS", "OWNERSHIP")) {
      var user = actor(false);
      var once = new java.util.concurrent.atomic.AtomicBoolean();
      org.mockito.Mockito.doAnswer(
              call -> {
                var result = call.callRealMethod();
                if (once.compareAndSet(false, true)) {
                  switch (change) {
                    case "SESSION" ->
                        tx().executeWithoutResult(
                                s ->
                                    identity.revoke(
                                        user.sessionId(), "TEST_REVOKED", Instant.now()));
                    case "VERSION" ->
                        jdbc.update(
                            "UPDATE account.user_account SET version=version+1 WHERE id=?",
                            user.userId());
                    case "FRESHNESS" ->
                        jdbc.update(
                            "UPDATE identity.auth_session SET authenticated_at=now()-interval '10 minutes' WHERE id=?",
                            user.sessionId());
                    case "OWNERSHIP" ->
                        communities.create(
                            user,
                            "synthetic-" + UUID.randomUUID(),
                            "Synthetic",
                            "",
                            "PRIVATE",
                            "INVITE_ONLY",
                            null);
                  }
                }
                return result;
              })
          .when(archive)
          .append(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.any());
      try {
        assertThatThrownBy(() -> auth.close(user, 0)).isInstanceOf(Failure.class);
        assertThat(once).isTrue();
        assertThat(queries.find(user.userId()).active()).isTrue();
        assertThat(store.request(user.userId())).isNull();
      } finally {
        org.mockito.Mockito.reset(archive);
      }
    }
  }

  @Autowired com.flyvo.socialcore.community.api.CommunityCommands communities;
  @Autowired FlowClock flowClock;
  @Autowired SyntheticProvider provider;
  @Autowired com.flyvo.socialcore.identity.application.OAuthAuthentication oauth;
  @Autowired com.flyvo.socialcore.profile.application.ProfileErasure profileHandler;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.flyvo.socialcore.profile.application.port.ProfileErasureStore profileEraseStore;

  @Test
  void reservationCommitSerializesACompetingHandleClaimWithoutLockInversion() throws Exception {
    var user = actor(false);
    var claimant = actor(false);
    String handle =
        jdbc.queryForObject(
            "SELECT handle_normalized FROM profile.profile WHERE user_id=?",
            String.class,
            user.userId());
    auth.close(user, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    var held = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            call -> {
              var result = call.callRealMethod();
              if (user.userId().equals(call.getArgument(0))) {
                held.countDown();
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new IllegalStateException("SYNTHETIC_BARRIER_TIMEOUT");
              }
              return result;
            })
        .when(profileEraseStore)
        .erase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var erasing = pool.submit(() -> jobs.consumeJob(UUID.randomUUID(), List.of(profileHandler)));
      assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var claiming =
          pool.submit(
              () -> {
                try {
                  jdbc.update(
                      "UPDATE profile.profile SET handle=?,handle_normalized=? WHERE user_id=?",
                      handle,
                      handle,
                      claimant.userId());
                  return true;
                } catch (org.springframework.dao.DataAccessException expected) {
                  return false;
                }
              });
      try {
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(5))
            .until(
                () ->
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0",
                            Integer.class)
                        > 0);
        assertThat(claiming.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(erasing.get(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(claiming.get(15, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM profile.handle_claim WHERE handle_normalized=?",
                  String.class,
                  handle))
          .isEqualTo("RESERVED");
    } finally {
      release.countDown();
      org.mockito.Mockito.reset(profileEraseStore);
    }
  }

  @Test
  void concurrentNewHandleClaimsHaveExactlyOneDatabaseWinner() throws Exception {
    var a = actor(false);
    var b = actor(false);
    String handle = "race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var outcomes = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (var user : List.of(a, b))
        outcomes.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    jdbc.update(
                        "UPDATE profile.profile SET handle=?,handle_normalized=? WHERE user_id=?",
                        handle,
                        handle,
                        user.userId());
                    return true;
                  } catch (org.springframework.dao.DataAccessException expected) {
                    return false;
                  }
                }));
      start.countDown();
      int wins = 0;
      for (var outcome : outcomes)
        if (outcome.get(15, java.util.concurrent.TimeUnit.SECONDS)) wins++;
      assertThat(wins).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT p.user_id=c.user_id FROM profile.profile p JOIN profile.handle_claim c ON c.handle_normalized=p.handle_normalized WHERE p.handle_normalized=?",
                  Boolean.class,
                  handle))
          .isTrue();
    }
  }

  @Test
  void freshProfileCreationAgainstReservationAndConcurrentExpiryHasOneUniqueWinner()
      throws Exception {
    var user = actor(false);
    String handle =
        jdbc.queryForObject(
            "SELECT handle_normalized FROM profile.profile WHERE user_id=?",
            String.class,
            user.userId());
    historical(user);
    converge(user.userId(), 40);
    var fresh = List.of(UUID.randomUUID(), UUID.randomUUID());
    for (var id : fresh)
      jdbc.update(
          "INSERT INTO account.user_account(id,status,created_at,updated_at) VALUES (?,'ACTIVE',now(),now())",
          id);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) VALUES (?,?,?,'Synthetic','PRIVATE',now(),now())",
                    fresh.getFirst(),
                    handle,
                    handle))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    jdbc.update(
        "UPDATE profile.handle_claim SET expires_at=clock_timestamp() WHERE handle_normalized=?",
        handle);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
      var outcomes = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (var id : fresh)
        outcomes.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    jdbc.update(
                        "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) VALUES (?,?,?,'Synthetic','PRIVATE',now(),now())",
                        id,
                        handle,
                        handle);
                    return true;
                  } catch (org.springframework.dao.DataAccessException expected) {
                    assertThat(expected.getMostSpecificCause())
                        .isInstanceOf(java.sql.SQLException.class);
                    assertThat(
                            ((java.sql.SQLException) expected.getMostSpecificCause()).getSQLState())
                        .isEqualTo("23505");
                    return false;
                  }
                }));
      var expiry =
          pool.submit(
              () -> {
                start.await();
                return tx().execute(s -> profileEraseStore.expireReservations());
              });
      start.countDown();
      int wins = 0;
      for (var result : outcomes) if (result.get(15, java.util.concurrent.TimeUnit.SECONDS)) wins++;
      expiry.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(wins).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT p.user_id=c.user_id AND c.state='LIVE' FROM profile.profile p JOIN profile.handle_claim c ON c.handle_normalized=p.handle_normalized WHERE p.handle_normalized=?",
                  Boolean.class,
                  handle))
          .isTrue();
    }
  }

  @Test
  void oldProviderResetAndMailboxProofsCannotReturnAfterFreshProviderReregistration() {
    var user = actor(false);
    String email = identity.email(user.userId());
    String subject = UUID.randomUUID().toString();
    provider.proof =
        new com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", subject, email);
    tx().executeWithoutResult(
            s ->
                identity.bind(
                    UUID.randomUUID(),
                    user.userId(),
                    provider.proof,
                    Instant.now().minusSeconds(8000)));
    flowClock.fixed = Instant.now().minusSeconds(7500);
    String browser = secrets.randomToken();
    var old = oauth.start("google", "LOGIN", ActorContext.anonymous(), browser);
    String oldState = old.location().substring(old.location().indexOf("state=") + 6);
    String reset = secrets.randomToken();
    String mailbox = secrets.randomToken();
    String registrationBrowser = secrets.randomToken();
    tx().executeWithoutResult(
            s -> {
              identity.oneTime(
                  UUID.randomUUID(),
                  user.userId(),
                  "RESET_PASSWORD",
                  secrets.hash(reset),
                  null,
                  flowClock.instant());
              identity.intent(
                  UUID.randomUUID(),
                  email,
                  secrets.hash(mailbox),
                  "LOCAL",
                  null,
                  secrets.hash(registrationBrowser),
                  flowClock.instant().plusSeconds(1800),
                  flowClock.instant());
            });
    flowClock.fixed = null;
    historical(user);
    converge(user.userId(), 60);
    assertThat(store.step(user.userId(), AccountErasure.Step.IDENTITY, false).view().status())
        .isEqualTo("DONE");
    assertThat(identity.external("GOOGLE", provider.proof.issuer(), subject)).isNull();
    assertThatThrownBy(() -> oauth.callback("google", oldState, browser, "synthetic", null))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.reset(reset, "Synthetic river planet orchard 73"))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(
            () -> auth.complete(mailbox, registrationBrowser, "Synthetic river planet orchard 74"))
        .isInstanceOf(Failure.class);
    var fresh = oauth.start("google", "LOGIN", ActorContext.anonymous(), browser);
    var result =
        oauth.callback(
            "google",
            fresh.location().substring(fresh.location().indexOf("state=") + 6),
            browser,
            "synthetic",
            null);
    assertThat(result.marker()).isEqualTo("verify_recovery_email");
    oauth.register(email, result.onboarding(), browser, registrationBrowser);
    var intent =
        jdbc.queryForObject(
            "SELECT id FROM identity.registration_intent WHERE email_normalized=? ORDER BY created_at DESC LIMIT 1",
            UUID.class,
            email);
    var mail =
        jdbc.queryForObject(
            "SELECT j.id,p.ciphertext FROM platform.job j JOIN platform.secret_mail_payload p ON p.job_id=j.id WHERE j.kind='MAIL' AND j.dedup_key=?",
            (r, n) ->
                json.readValue(
                    secrets.decrypt("mail:" + r.getObject(1, UUID.class), r.getBytes(2)),
                    DurableWorkStore.Mail.class),
            intent.toString());
    var grant = auth.complete(mail.secret(), registrationBrowser, null);
    var newUser = identity.session(grant.sessionId(), false).userId();
    assertThat(newUser).isNotEqualTo(user.userId());
    assertThat(identity.external("GOOGLE", provider.proof.issuer(), subject).userId())
        .isEqualTo(newUser);
  }

  @Test
  void providerExchangeCompletingAfterOriginalTransactionExpiryIsRejected() {
    org.mockito.Mockito.doAnswer(
            call -> flowClock.fixed == null ? call.callRealMethod() : flowClock.fixed)
        .when(identity)
        .databaseTime();
    var user = actor(false);
    provider.proof =
        new com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", UUID.randomUUID().toString(), null);
    String browser = secrets.randomToken();
    var start = oauth.start("google", "LOGIN", ActorContext.anonymous(), browser);
    provider.afterExchange = () -> flowClock.fixed = Instant.now().plusSeconds(601);
    try {
      assertThatThrownBy(
              () ->
                  oauth.callback(
                      "google",
                      start.location().substring(start.location().indexOf("state=") + 6),
                      browser,
                      "synthetic",
                      null))
          .isInstanceOf(Failure.class)
          .hasMessage("INVALID_OAUTH_TRANSACTION");
    } finally {
      flowClock.fixed = null;
      provider.afterExchange = () -> {};
    }
  }

  @Test
  void avatarReleaseWaitsForIndependentResourceReceiptAndPublishesVersionedAbsence() {
    var user = actor(false);
    var asset = UUID.randomUUID();
    tx().executeWithoutResult(
            s -> {
              jdbc.update(
                  "INSERT INTO media.asset(id,owner_id,purpose,state,quarantine_key,uploaded_version_id,declared_mime,declared_bytes,sha256,width,height,upload_expires_at,orphan_expires_at,ready_at,created_at,updated_at) VALUES (?,?,'AVATAR','READY',?,'synthetic-pinned','image/png',1,?,1,1,now(),now()+interval '1 day',now(),now(),now())",
                  asset,
                  user.userId(),
                  "incoming/" + asset,
                  java.util.Base64.getEncoder().encodeToString(new byte[32]));
              for (var name : List.of("DISPLAY", "THUMBNAIL"))
                jdbc.update(
                    "INSERT INTO media.variant(id,asset_id,name,object_key,object_version_id,mime,bytes,width,height,generation) VALUES (?,?,?,?,'synthetic-pinned','image/png',1,1,1,0)",
                    UUID.randomUUID(),
                    asset,
                    name,
                    "variants/" + asset + "/0/" + name);
              jdbc.update(
                  "INSERT INTO media.binding(asset_id,context_type,context_id,bound_at) VALUES (?,'AVATAR',?,now())",
                  asset,
                  user.userId());
              jdbc.update(
                  "UPDATE profile.profile SET avatar_media_id=? WHERE user_id=?",
                  asset,
                  user.userId());
            });
    auth.close(user, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    jobs.consumeJob(UUID.randomUUID(), List.of(profileHandler));
    assertThat(
            jdbc.queryForObject(
                "SELECT avatar_media_id FROM profile.profile WHERE user_id=?",
                UUID.class,
                user.userId()))
        .isEqualTo(asset);
    var state = store.step(user.userId(), AccountErasure.Step.PROFILE, false);
    assertThat(state.checkpoint()).startsWith("AVATAR_EXPORT:");
    assertThat(store.resource(UUID.fromString(state.checkpoint().substring(14))).exported())
        .isFalse();
    exports();
    due(user.userId());
    jobs.consumeJob(UUID.randomUUID(), List.of(profileHandler));
    assertThat(
            jdbc.queryForObject(
                "SELECT released_at IS NOT NULL FROM media.binding WHERE asset_id=?",
                Boolean.class,
                asset))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM profile.profile WHERE user_id=?",
                Integer.class,
                user.userId()))
        .isZero();
    var source = profileSources.inspect(List.of(user.userId())).getFirst();
    assertThat(source.active()).isFalse();
    assertThat(source.handle()).isEmpty();
    assertThat(source.name()).isEmpty();
    assertThat(source.version()).isEqualTo(1);
    assertThatThrownBy(() -> tx().executeWithoutResult(s -> profiles.provision(user.userId())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(queries.find(user.userId()).status()).isEqualTo("DELETION_PENDING");
  }

  @Autowired com.flyvo.socialcore.profile.api.ProfileSearchSources profileSources;

  @Test
  void expiredDbWorkerAckRollsBackProfileProofAuditEventAndContinuation() {
    var user = actor(false);
    auth.close(user, 0);
    exports();
    tx().executeWithoutResult(s -> internal.reconcile(user.userId()));
    var before = store.step(user.userId(), AccountErasure.Step.PROFILE, false);
    long events =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.outbox_event WHERE aggregate_type='profile' AND aggregate_id=?",
            Long.class,
            user.userId());
    org.mockito.Mockito.doAnswer(
            call -> {
              var result = call.callRealMethod();
              flowClock.fixed = Instant.now().plusSeconds(61);
              return result;
            })
        .when(profileEraseStore)
        .erase(org.mockito.ArgumentMatchers.eq(user.userId()), org.mockito.ArgumentMatchers.any());
    try {
      assertThat(jobs.consumeJob(UUID.randomUUID(), List.of(profileHandler))).isFalse();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM profile.profile WHERE user_id=?",
                  Integer.class,
                  user.userId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM profile.erasure_tombstone WHERE user_id=?",
                  Integer.class,
                  user.userId()))
          .isZero();
      assertThat(store.step(user.userId(), AccountErasure.Step.PROFILE, false).view().version())
          .isEqualTo(before.view().version());
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='ERASURE_PASS' AND reason_code='PROFILE'",
                  Integer.class,
                  user.userId()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM platform.outbox_event WHERE aggregate_type='profile' AND aggregate_id=?",
                  Long.class,
                  user.userId()))
          .isEqualTo(events);
      assertThat(scoped.executionStatus("PROFILE_ERASURE", before.execution()))
          .isEqualTo("RUNNING");
      org.mockito.Mockito.reset(profileEraseStore);
      assertThat(jobs.consumeJob(UUID.randomUUID(), List.of(profileHandler))).isTrue();
      assertThat(store.step(user.userId(), AccountErasure.Step.PROFILE, false).view().status())
          .isEqualTo("DONE");
      assertThat(
              jdbc.queryForObject(
                  "SELECT lease_generation FROM platform.job WHERE kind='PROFILE_ERASURE' AND dedup_key=?",
                  Long.class,
                  before.execution().toString()))
          .isEqualTo(2);
    } finally {
      org.mockito.Mockito.reset(profileEraseStore);
      flowClock.fixed = null;
    }
  }
}
