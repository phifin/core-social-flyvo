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
  @Autowired Authentication auth;
  @Autowired IdentityRepository identity;
  @Autowired ProfileCommands profiles;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RecoveryArchive archive;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DurableWorkStore jobs;
  @Autowired ErasureExports exporter;
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
    jdbc.update(
        "UPDATE platform.job SET available_at=timestamptz '2100-01-01 00:00:00Z' WHERE kind='MAIL' AND status IN ('PENDING','RETRY')");
    jdbc.update(
        "UPDATE platform.job SET available_at=timestamptz '2100-01-01 00:00:00Z' WHERE kind IN ('ERASURE_EXPORT','ERASURE_RECONCILE','ERASURE_SCAN','ERASURE_TECHNICAL','IDENTITY_ERASURE','PROFILE_ERASURE') AND status IN ('PENDING','RETRY')");
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
  void realWorkerNeverDispatchesUnimplementedOwnersOrCompletesAnEmptyAccount() {
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
                        "ERASURE_RECONCILE",
                        "ERASURE_TECHNICAL")
                    .contains(k));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.erasure_step WHERE user_id=? AND status='WAITING_DEPENDENCY' AND reason='OWNER_NOT_IMPLEMENTED'",
                Integer.class,
                user.userId()))
        .isEqualTo(8);
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
