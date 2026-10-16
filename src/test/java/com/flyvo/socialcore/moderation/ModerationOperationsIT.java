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
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.moderation.api.*;
import com.flyvo.socialcore.moderation.application.*;
import com.flyvo.socialcore.moderation.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.application.Profiles;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ModerationOperationsIT {
  @org.springframework.boot.test.context.TestConfiguration
  static class Metrics {
    @org.springframework.context.annotation.Bean
    io.micrometer.prometheusmetrics.PrometheusMeterRegistry prometheusMeterRegistry() {
      return new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
          io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
    }
  }

  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  static final UUID LEGACY_USER = UUID.randomUUID(), LEGACY_CASE = UUID.randomUUID();
  static final int LEGACY_COUNT = 205;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    // An actual V29 database with private values, then normal application Flyway upgrade.
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .target("0029")
        .load()
        .migrate();
    var sql =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    sql.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at) VALUES (?,'ACTIVE',now(),now())",
        LEGACY_USER);
    sql.update(
        "INSERT INTO moderation.report(id,reporter_id,target_type,target_id,category,evidence_encrypted,evidence_key_id,status,created_at,updated_at) VALUES (?,?,'USER',?,'SPAM',?,'legacy-test-key','OPEN',now(),now())",
        LEGACY_CASE,
        LEGACY_USER,
        LEGACY_USER,
        new byte[] {1, 2, 3});
    for (int i = 0; i < LEGACY_COUNT; i++)
      sql.update(
          "INSERT INTO moderation.action(id,report_id,actor_id,target_type,target_id,action,reason,before_state,after_state,request_id,created_at) VALUES (?,?,?,'REPORT',?,'REVIEW_REPORT',?,?::jsonb,?::jsonb,'synthetic-legacy',now())",
          UUID.randomUUID(),
          LEGACY_CASE,
          LEGACY_USER,
          LEGACY_CASE,
          "Lý do riêng tư " + i,
          "{\"status\":\"OPEN\",\"unicode\":\"Tiếng Việt\",\"nested\":{\"value\":null}}",
          "{\"status\":\"IN_REVIEW\",\"array\":[1,true,null]}");
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
  }

  @Autowired CaseOperations operations;
  @Autowired Reports reports;
  @Autowired CaseStore store;
  @Autowired ModerationActions actions;
  @Autowired ModerationEncryption encryption;
  @Autowired ModerationRetention retention;
  @Autowired AccountCommands accounts;
  @Autowired AccountQueries queries;
  @Autowired Profiles profiles;
  @Autowired CommunityCommands communities;
  @Autowired MembershipCommands members;
  @Autowired MembershipQueries memberships;
  @Autowired ContentCommands posts;
  @Autowired AccessTokens tokens;
  @Autowired DurableWorkStore worker;
  @Autowired DurableWork work;
  @Autowired ObjectMapper json;
  @Autowired Clock clock;
  @Autowired MockMvc mvc;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
  @MockitoSpyBean Secrets secrets;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    tx = new TransactionTemplate(manager);
  }

  ActorContext user() {
    var id =
        tx.execute(
            s -> {
              var value = accounts.createVerified();
              profiles.provision(value);
              return value;
            });
    return session(id, 0);
  }

  ActorContext session(UUID user, long epoch) {
    var id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at,authentication_epoch) VALUES (?,?,now(),now(),now(),now()+interval '7 days',now()+interval '30 days',now(),?)",
        id,
        user,
        epoch);
    return new ActorContext(user, id);
  }

  ActorContext admin() {
    var a = user();
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", a.userId());
    return a;
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  void denied(Runnable call, int status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(status));
  }

  CaseStore.Root root(UUID id) {
    return new CaseStore.Root(CaseOperations.Root.REPORT, id);
  }

  UUID agedCase(ActorContext a, boolean terminal, int actionCount) {
    return caseAt(a, terminal, actionCount, Instant.parse("2000-01-01T00:00:00Z"));
  }

  UUID caseAt(ActorContext a, boolean terminal, int actionCount, Instant old) {
    var id = UUID.randomUUID();
    tx.executeWithoutResult(
        s -> {
          var encrypted =
              secrets.encrypt(
                  "report:" + id,
                  json.writeValueAsString(
                      new ReportWorkflow.Captured(
                          old, "Synthetic retained private evidence", "Tiếng Việt")));
          jdbc.update(
              "INSERT INTO moderation.report(id,reporter_id,target_type,target_id,category,status,resolved_at,created_at,updated_at) VALUES (?,?,'USER',?,'SPAM',?,?,?,?)",
              id,
              a.userId(),
              a.userId(),
              terminal ? "RESOLVED" : "OPEN",
              terminal ? old.atOffset(ZoneOffset.UTC) : null,
              old.atOffset(ZoneOffset.UTC),
              old.atOffset(ZoneOffset.UTC));
          jdbc.update(
              "INSERT INTO moderation.report_evidence(report_id,ciphertext,key_id) VALUES (?,?,?)",
              id,
              encrypted,
              secrets.keyId());
          for (int i = 0; i < actionCount; i++)
            actions.appendCase(
                UUID.randomUUID(),
                id,
                a.userId(),
                null,
                "REPORT",
                id,
                "REVIEW_REPORT",
                "Synthetic private reason " + i,
                "{\"status\":\"OPEN\"}",
                "{\"status\":\"IN_REVIEW\"}",
                "synthetic-history",
                old);
        });
    return id;
  }

  Reports.View freshReport(ActorContext reporter, ActorContext target) {
    return reports.submit(
        reporter,
        new Reports.Submit(
            Reports.Target.USER, target.userId(), Reports.Category.SPAM, "Private fixture"),
        null);
  }

  UUID job(String kind) {
    String key = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        s -> work.job(kind, key, "{}", clock.instant().minus(Duration.ofDays(1))));
    return jdbc.queryForObject(
        "SELECT id FROM platform.job WHERE kind=? AND dedup_key=?", UUID.class, kind, key);
  }

  void retry(UUID id) {
    jdbc.update("UPDATE platform.job SET available_at=now()-interval '1 day' WHERE id=?", id);
  }

  void consume(JobHandler handler) {
    assertThat(worker.consumeJob(UUID.randomUUID(), List.of(handler))).isTrue();
  }

  CaseOperations.Hold future() {
    return new CaseOperations.Hold(Instant.now().plus(Duration.ofDays(30)), "ENGINEERING_REVIEW");
  }

  void waitLock(Future<?> task) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(
                      count(
                          "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND datname=current_database() AND pid<>pg_backend_pid()"))
                  .isPositive();
              assertThat(task.isDone()).isFalse();
            });
  }

  void latch(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @Test
  void legacyEncryptionVerifiesCompleteValueRetriesAndConvergesInBoundedPasses() {
    var a = admin();
    var before = tx.execute(s -> store.history(LEGACY_CASE, 1, 0)).getFirst();
    assertThat(before.version()).isNull();
    assertThat(operations.action(a, null, before.actionId()).reason()).startsWith("Lý do riêng tư");
    var original =
        jdbc.queryForList(
            "SELECT action_id,legacy_payload::text AS legacy FROM moderation.action_detail WHERE legacy_payload IS NOT NULL");
    UUID job = job(encryption.kind());
    var encryptCalls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            invocation -> {
              if (encryptCalls.incrementAndGet() == 2)
                throw new IllegalStateException("KEY_UNAVAILABLE");
              return invocation.callRealMethod();
            })
        .when(secrets)
        .encrypt(startsWith("moderation-action:"), anyString());
    try {
      assertThat(worker.consumeJob(UUID.randomUUID(), List.of(encryption))).isFalse();
      assertThat(
              count(
                  "SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL"))
          .isEqualTo(LEGACY_COUNT);
      assertThat(
              jdbc.queryForObject("SELECT status FROM platform.job WHERE id=?", String.class, job))
          .isEqualTo("RETRY");
    } finally {
      reset(secrets);
    }
    doReturn("{\"reason\":\"CORRUPT\",\"before\":null,\"after\":null}")
        .when(secrets)
        .decrypt(startsWith("moderation-action:"), any(byte[].class));
    try {
      retry(job);
      assertThat(worker.consumeJob(UUID.randomUUID(), List.of(encryption))).isFalse();
      assertThat(
              count(
                  "SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL"))
          .isEqualTo(LEGACY_COUNT);
    } finally {
      reset(secrets);
    }
    String constraint = "fixture_crypto_audit_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE platform.audit_entry ADD CONSTRAINT "
            + constraint
            + " CHECK (target_id IS DISTINCT FROM '"
            + LEGACY_CASE
            + "'::uuid) NOT VALID");
    try {
      retry(job);
      assertThat(worker.consumeJob(UUID.randomUUID(), List.of(encryption))).isFalse();
      assertThat(
              count(
                  "SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL"))
          .isEqualTo(LEGACY_COUNT);
    } finally {
      jdbc.execute("ALTER TABLE platform.audit_entry DROP CONSTRAINT " + constraint);
    }
    retry(job);
    consume(encryption);
    assertThat(
            count("SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL"))
        .isEqualTo(105);
    consume(encryption);
    assertThat(
            count("SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL"))
        .isEqualTo(5);
    consume(encryption);
    assertThat(
            count("SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL"))
        .isZero();
    for (var row : original) {
      var id = (UUID) row.get("action_id");
      var ciphertext =
          jdbc.queryForObject(
              "SELECT ciphertext FROM moderation.action_detail WHERE action_id=?",
              byte[].class,
              id);
      assertThat(json.readTree(secrets.decrypt("moderation-action:" + id, ciphertext)))
          .isEqualTo(json.readTree((String) row.get("legacy")));
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE moderation.action_detail SET ciphertext=? WHERE action_id=?",
                      new byte[] {8},
                      id))
          .hasMessageContaining("replacement/restoration forbidden");
    }
    assertThat(
            operations.maintenance(a, CaseOperations.Maintenance.ENCRYPT, false).legacyPayloads())
        .isZero();
    assertThat(metrics.get("moderation.payloads").tag("operation", "encrypted").counter().count())
        .isEqualTo(205);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='MODERATION_LEGACY_ENCRYPTED'",
                LEGACY_CASE))
        .isEqualTo(3);
    consume(encryption); // no due job or duplicate effects
    assertThat(
            count(
                "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND key_id='legacy-test-key'",
                LEGACY_CASE))
        .isEqualTo(1);
  }

  @Test
  void assignmentFilteringHistoryAndRevokedAssigneeNeverGrantAuthority() {
    var owner = user();
    var mod = user();
    var other = user();
    var reporter = user();
    var c =
        communities.create(
            owner, "scope-" + UUID.randomUUID(), "Synthetic scope", "", "PRIVATE", "OPEN", null);
    var joined = members.join(mod, c.id());
    members.role(owner, c.id(), mod.userId(), joined.version(), "MODERATOR");
    var post =
        posts.publish(
            owner,
            new ContentCommands.Publish(
                "Synthetic report",
                null,
                c.id(),
                new ContentCommands.Audience("COMMUNITY", List.of()),
                List.of(),
                List.of()),
            null);
    members.join(reporter, c.id());
    var r =
        reports.submit(
            reporter,
            new Reports.Submit(Reports.Target.POST, post.id(), Reports.Category.SPAM, "Private"),
            null);
    denied(() -> operations.assign(owner, c.id(), r.id(), 0, other.userId()), 403);
    var assigned = operations.assign(owner, c.id(), r.id(), 0, mod.userId());
    assertThat(assigned.assignedTo()).isEqualTo(mod.userId());
    assertThat(
            operations
                .queue(mod, c.id(), new CaseOperations.Filter("OPEN", mod.userId(), false), 20, 0)
                .items())
        .extracting(CaseOperations.CaseView::id)
        .containsExactly(r.id());
    assertThat(
            operations
                .queue(owner, c.id(), new CaseOperations.Filter(null, null, true), 20, 0)
                .items())
        .isEmpty();
    denied(() -> operations.assign(owner, c.id(), r.id(), 0, null), 412);
    denied(
        () ->
            operations.queue(
                owner, c.id(), new CaseOperations.Filter("invalid", null, false), 20, 0),
        400);
    denied(
        () ->
            operations.queue(
                owner, c.id(), new CaseOperations.Filter(null, mod.userId(), true), 20, 0),
        400);
    var history = operations.history(mod, c.id(), r.id(), 100, 0).items();
    assertThat(history)
        .extracting(CaseOperations.HistoryEntry::caseVersion)
        .containsExactly(0L, 1L);
    var current = memberships.facts(mod.userId(), List.of(c.id())).getFirst();
    members.role(owner, c.id(), mod.userId(), current.version(), "MEMBER");
    denied(() -> operations.history(mod, c.id(), r.id(), 20, 0), 403);
    denied(
        () -> operations.queue(mod, c.id(), new CaseOperations.Filter(null, null, false), 20, 0),
        403);
    var b =
        communities.create(
            other, "other-" + UUID.randomUUID(), "Other scope", "", "PRIVATE", "OPEN", null);
    denied(() -> operations.history(other, b.id(), r.id(), 20, 0), 404);
    denied(() -> operations.assign(other, b.id(), r.id(), 1, other.userId()), 404);
    denied(() -> operations.history(admin(), null, r.id(), 20, 0), 404);
    var resolved =
        reports.resolve(
            owner,
            c.id(),
            1,
            new Reports.Resolution(
                r.id(), Reports.Action.RESOLVE_REPORT, "Private scoped reason", null, null));
    assertThat(operations.history(owner, c.id(), r.id(), 100, 0).items().getLast().reason())
        .isEqualTo("Private scoped reason");
    denied(() -> operations.action(other, b.id(), resolved.actionId()), 404);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='CASE_HISTORY_READ'",
                r.id()))
        .isEqualTo(2);
  }

  @Test
  void conflictingAssignmentAndResolutionHaveOneVersionWinnerAndAtomicHistory() throws Exception {
    var a = admin();
    var assignee = admin();
    var r = freshReport(user(), user());
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var assignment =
          pool.submit(
              () -> {
                latch(start);
                try {
                  return (Object) operations.assign(a, null, r.id(), 0, assignee.userId());
                } catch (Failure f) {
                  return f;
                }
              });
      var resolution =
          pool.submit(
              () -> {
                latch(start);
                try {
                  return (Object)
                      reports.resolve(
                          a,
                          null,
                          0,
                          new Reports.Resolution(
                              r.id(),
                              Reports.Action.RESOLVE_REPORT,
                              "Private resolution",
                              null,
                              null));
                } catch (Failure f) {
                  return f;
                }
              });
      start.countDown();
      var results =
          List.of(assignment.get(10, TimeUnit.SECONDS), resolution.get(10, TimeUnit.SECONDS));
      assertThat(results.stream().filter(Failure.class::isInstance).count()).isEqualTo(1);
      assertThat(count("SELECT version FROM moderation.report WHERE id=?", r.id())).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM moderation.case_history WHERE report_id=?", r.id()))
          .isEqualTo(2);
      assertThat(
              count(
                  "SELECT count(*) FROM moderation.action a LEFT JOIN moderation.action_detail d ON d.action_id=a.id WHERE a.report_id=? AND d.action_id IS NULL",
                  r.id()))
          .isZero();
    }
  }

  @Test
  void holdsExcludeUnresolvedAndHeldEvidenceThenExpiredHoldAllowsBoundedMultiPassRedaction() {
    var a = admin();
    var owner = user();
    var unresolved = agedCase(owner, false, 0);
    var r = agedCase(owner, true, 205);
    var hold = operations.hold(a, null, CaseOperations.Root.REPORT, r, 0, future());
    assertThat(tx.<Integer>execute(s -> store.redact(root(r), 100))).isZero();
    assertThat(tx.<Integer>execute(s -> store.redact(root(unresolved), 100))).isZero();
    assertThat(reports.evidence(a, null, r).detail()).contains("retained private");
    assertThat(
            count(
                "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND key_id=?",
                r,
                secrets.keyId()))
        .isEqualTo(1);
    operations.hold(
        a,
        null,
        CaseOperations.Root.REPORT,
        r,
        hold.version(),
        new CaseOperations.Hold(null, null));
    assertThat(tx.<Integer>execute(s -> store.redact(root(r), 100))).isEqualTo(100);
    assertThat(
            jdbc.queryForObject(
                "SELECT retention_state FROM moderation.report WHERE id=?", String.class, r))
        .isEqualTo("STARTED");
    long version = count("SELECT version FROM moderation.report WHERE id=?", r);
    denied(() -> operations.hold(a, null, CaseOperations.Root.REPORT, r, version, future()), 409);
    assertThat(
            count(
                "SELECT count(*) FROM moderation.action a JOIN moderation.action_detail d ON d.action_id=a.id WHERE a.report_id=? AND d.redacted_at IS NULL",
                r))
        .isEqualTo(106);
    assertThat(tx.<Integer>execute(s -> store.redact(root(r), 100))).isEqualTo(100);
    assertThat(tx.<Integer>execute(s -> store.redact(root(r), 100))).isEqualTo(6);
    assertThat(tx.<Integer>execute(s -> store.redact(root(r), 100))).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT retention_state FROM moderation.report WHERE id=?", String.class, r))
        .isEqualTo("DONE");
    var evidence = reports.evidence(a, null, r);
    assertThat(evidence.redacted()).isTrue();
    assertThat(evidence.detail()).isNull();
    assertThat(evidence.summary()).isNull();
    assertThat(count("SELECT count(*) FROM moderation.action WHERE report_id=?", r)).isEqualTo(205);
    assertThat(
            count(
                "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND ciphertext IS NOT NULL",
                unresolved))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report_evidence SET ciphertext=?,key_id=?,redacted_at=NULL WHERE report_id=?",
                    new byte[] {4},
                    secrets.keyId(),
                    r))
        .hasMessageContaining("replacement/restoration forbidden");
  }

  @Test
  void holdWinsRootRaceAndCleanupRechecksAfterWaiting() throws Exception {
    var a = admin();
    var r = agedCase(user(), true, 0);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var holder =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        operations.hold(a, null, CaseOperations.Root.REPORT, r, 0, future());
                        held.countDown();
                        latch(release);
                        return true;
                      }));
      try {
        latch(held);
        var cleanup = pool.submit(() -> tx.execute(s -> store.redact(root(r), 100)));
        waitLock(cleanup);
        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(cleanup.get(10, TimeUnit.SECONDS)).isZero();
      } finally {
        release.countDown();
      }
    }
    assertThat(
            count(
                "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND ciphertext IS NOT NULL",
                r))
        .isEqualTo(1);
  }

  @Test
  void cleanupWinsRootRaceAndLaterHoldCannotClaimPreservation() throws Exception {
    var a = admin();
    var r = agedCase(user(), true, 0);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var cleanup =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        int changed = store.redact(root(r), 100);
                        held.countDown();
                        latch(release);
                        return changed;
                      }));
      try {
        latch(held);
        var holder =
            pool.submit(
                () -> {
                  try {
                    return (Object)
                        operations.hold(a, null, CaseOperations.Root.REPORT, r, 0, future());
                  } catch (Failure f) {
                    return f;
                  }
                });
        waitLock(holder);
        release.countDown();
        assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(holder.get(10, TimeUnit.SECONDS)).isInstanceOf(Failure.class);
      } finally {
        release.countDown();
      }
    }
    denied(
        () ->
            operations.hold(
                a,
                null,
                CaseOperations.Root.REPORT,
                r,
                count("SELECT version FROM moderation.report WHERE id=?", r),
                future()),
        409);
  }

  @Test
  void rawSqlRejectsSubstitutionReassignmentDeleteMissingPayloadAndPrematureCompletion() {
    var a = admin();
    var r = agedCase(user(), true, 1);
    var action =
        jdbc.queryForObject("SELECT id FROM moderation.action WHERE report_id=?", UUID.class, r);
    var payload =
        jdbc.queryForObject(
            "SELECT ciphertext FROM moderation.action_detail WHERE action_id=?",
            byte[].class,
            action);
    assertThat(payload).isNotEmpty();
    for (String sql :
        List.of(
            "UPDATE moderation.action SET actor_id=actor_id WHERE id=?",
            "DELETE FROM moderation.action WHERE id=?"))
      assertThatThrownBy(() -> jdbc.update(sql, action)).hasMessageContaining("append only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.action_detail SET ciphertext=? WHERE action_id=?",
                    new byte[] {1, 2},
                    action))
        .hasMessageContaining("replacement/restoration forbidden");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.action_detail SET legacy_payload='{}'::jsonb,ciphertext=NULL,key_id=NULL WHERE action_id=?",
                    action))
        .hasMessageContaining("replacement/restoration forbidden");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.action_detail SET action_id=? WHERE action_id=?",
                    UUID.randomUUID(),
                    action))
        .hasMessageContaining("identity immutable");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM moderation.report_evidence WHERE report_id=?", r))
        .hasMessageContaining("tombstone retained");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report_evidence SET report_id=? WHERE report_id=?",
                    UUID.randomUUID(),
                    r))
        .hasMessageContaining("identity immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET target_id=?,version=version+1 WHERE id=?",
                    UUID.randomUUID(),
                    r))
        .hasMessageContaining("identity is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET retention_state='DONE',version=version+1 WHERE id=?",
                    r))
        .hasMessageContaining("one way");
    var unresolved = agedCase(user(), false, 0);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET retention_state='STARTED',version=version+1 WHERE id=?",
                    unresolved))
        .hasMessageContaining("not eligible");
    var fresh = freshReport(user(), user());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report_evidence SET ciphertext=NULL,key_id=NULL,redacted_at=now() WHERE report_id=?",
                    fresh.id()))
        .hasMessageContaining("replacement/restoration forbidden");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s ->
                        jdbc.update(
                            "INSERT INTO moderation.report(id,reporter_id,target_type,target_id,category,status,created_at,updated_at) VALUES (?,?,'USER',?,'SPAM','OPEN',now(),now())",
                            UUID.randomUUID(),
                            a.userId(),
                            a.userId())))
        .hasStackTraceContaining("report evidence/tombstone required");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      var id = UUID.randomUUID();
                      jdbc.update(
                          "INSERT INTO moderation.action(id,report_id,actor_id,target_type,target_id,action,request_id,created_at) VALUES (?,?,?,'REPORT',?,'REVIEW_REPORT','synthetic',now())",
                          id,
                          fresh.id(),
                          a.userId(),
                          fresh.id());
                      jdbc.update(
                          "INSERT INTO moderation.action_detail(action_id,legacy_payload) VALUES (?,'{}'::jsonb)",
                          id);
                    }))
        .hasMessageContaining("new payload must be encrypted");
    denied(() -> operations.hold(a, null, CaseOperations.Root.ACTION, action, 0, future()), 409);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO moderation.action_retention(action_id) VALUES (?)", action))
        .hasMessageContaining("case-linked actions use case root");
    tx.executeWithoutResult(
        s ->
            jdbc.update(
                "UPDATE moderation.report SET retention_state='STARTED',version=version+1 WHERE id=?",
                r));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET retention_state='DONE',version=version+1 WHERE id=?",
                    r))
        .hasMessageContaining("still has private payload");
    assertThat(tx.<Integer>execute(s -> store.redact(root(r), 100))).isEqualTo(2);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM moderation.case_history WHERE report_id=?", r))
        .hasMessageContaining("append only");
    tx.executeWithoutResult(
        s -> work.audit(a.userId(), "SYNTHETIC_RETENTION_TEST", "moderation", r, "SUCCESS", null));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE platform.audit_entry SET action='changed' WHERE target_id=?", r))
        .hasMessageContaining("append only");
  }

  @Test
  void databaseTimeExpiryAndStandaloneRootHaveSameIrreversibleRetentionBoundary() {
    var a = admin();
    var target = user();
    var action = UUID.randomUUID();
    tx.executeWithoutResult(
        s ->
            actions.appendCase(
                action,
                null,
                a.userId(),
                null,
                "ACCOUNT",
                target.userId(),
                "SUSPEND_ACCOUNT",
                "Synthetic standalone reason",
                "{\"status\":\"ACTIVE\"}",
                "{\"status\":\"SUSPENDED\"}",
                "synthetic-standalone",
                Instant.parse("2000-01-01T00:00:00Z")));
    var root = new CaseStore.Root(CaseOperations.Root.ACTION, action);
    operations.hold(
        a,
        null,
        CaseOperations.Root.ACTION,
        action,
        0,
        new CaseOperations.Hold(Instant.now().plusSeconds(2), "ENGINEERING_REVIEW"));
    assertThat(tx.<Integer>execute(s -> store.redact(root, 100))).isZero();
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM moderation.action_retention WHERE action_id=?", action))
        .hasMessageContaining("root is retained");
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                Boolean.TRUE.equals(
                    jdbc.queryForObject(
                        "SELECT hold_until<=clock_timestamp() FROM moderation.action_retention WHERE action_id=?",
                        Boolean.class,
                        action)));
    assertThat(tx.<Integer>execute(s -> store.redact(root, 100))).isEqualTo(1);
    assertThat(operations.action(a, null, action).redacted()).isTrue();
    var almost = caseAt(target, true, 0, Instant.now().minus(Duration.ofDays(365)).plusSeconds(30));
    assertThat(tx.<Integer>execute(s -> store.redact(root(almost), 100))).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.report SET retention_state='STARTED',version=version+1 WHERE id=?",
                    almost))
        .hasMessageContaining("not eligible");
  }

  @Test
  void retentionJobAuditFailureAndExpiredFenceRollBackRootPayloadHistoryAndContinuation() {
    var a = admin();
    var r = caseAt(user(), true, 101, Instant.parse("1990-01-01T00:00:00Z"));
    assertThat(tx.<CaseStore.Root>execute(s -> store.retentionRoot())).isEqualTo(root(r));
    var job = job(retention.kind());
    double committedBefore =
        metrics.get("moderation.payloads").tag("operation", "redacted").counter().count();
    String constraint = "fixture_audit_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE platform.audit_entry ADD CONSTRAINT "
            + constraint
            + " CHECK (target_id IS DISTINCT FROM '"
            + r
            + "'::uuid) NOT VALID");
    try {
      assertThat(worker.consumeJob(UUID.randomUUID(), List.of(retention))).isFalse();
      assertThat(
              count(
                  "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND ciphertext IS NOT NULL",
                  r))
          .isEqualTo(1);
      assertThat(count("SELECT count(*) FROM moderation.case_history WHERE report_id=?", r))
          .isZero();
    } finally {
      jdbc.execute("ALTER TABLE platform.audit_entry DROP CONSTRAINT " + constraint);
    }
    retry(job);
    assertThat(metrics.get("moderation.payloads").tag("operation", "redacted").counter().count())
        .isEqualTo(committedBefore);
    String continuation = "fixture_continuation_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE platform.job ADD CONSTRAINT "
            + continuation
            + " CHECK (kind<>'MODERATION_RETENTION' OR dedup_key NOT LIKE 'continuation:%') NOT VALID");
    try {
      assertThat(worker.consumeJob(UUID.randomUUID(), List.of(retention))).isFalse();
      assertThat(count("SELECT count(*) FROM moderation.case_history WHERE report_id=?", r))
          .isZero();
      assertThat(
              count(
                  "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND ciphertext IS NOT NULL",
                  r))
          .isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE platform.job DROP CONSTRAINT " + continuation);
    }
    retry(job);
    JobHandler expired =
        new JobHandler() {
          public String kind() {
            return retention.kind();
          }

          public void apply(String payload) {
            retention.apply(payload);
            jdbc.update(
                "UPDATE platform.job SET lease_until=now()-interval '1 second' WHERE id=?", job);
          }
        };
    assertThat(worker.consumeJob(UUID.randomUUID(), List.of(expired))).isFalse();
    assertThat(
            count(
                "SELECT count(*) FROM moderation.report_evidence WHERE report_id=? AND ciphertext IS NOT NULL",
                r))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT retention_state FROM moderation.report WHERE id=?", String.class, r))
        .isEqualTo("NONE");
    assertThat(count("SELECT count(*) FROM moderation.case_history WHERE report_id=?", r)).isZero();
    assertThat(metrics.get("moderation.payloads").tag("operation", "redacted").counter().count())
        .isEqualTo(committedBefore);
    retry(job);
    consume(retention);
    for (int i = 0;
        i < 10
            && !"DONE"
                .equals(
                    jdbc.queryForObject(
                        "SELECT retention_state FROM moderation.report WHERE id=?",
                        String.class,
                        r));
        i++) consume(retention);
    assertThat(
            jdbc.queryForObject(
                "SELECT retention_state FROM moderation.report WHERE id=?", String.class, r))
        .isEqualTo("DONE");
    assertThat(reports.evidence(a, null, r).redacted()).isTrue();
    assertThat(count("SELECT lease_generation FROM platform.job WHERE id=?", job)).isEqualTo(4);
    assertThat(metrics.get("moderation.payloads").tag("operation", "redacted").counter().count())
        .isEqualTo(committedBefore + 102);
  }

  @Test
  void httpAccountModerationUsesStrongVersionAndDoesNotRestoreContentOwnershipOrSessions()
      throws Exception {
    var a = admin();
    var target = user();
    var peer = admin();
    mvc.perform(
            get("/api/v1/admin/moderation/accounts/" + target.userId())
                .header("Authorization", "Bearer " + tokens.issue(a)))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"0\""))
        .andExpect(jsonPath("$.platformRole").value("USER"))
        .andExpect(jsonPath("$.authenticationEpoch").doesNotExist());
    denied(() -> operations.accountState(target, a.userId()), 403);
    denied(() -> operations.accountState(a, UUID.randomUUID()), 404);
    var c =
        communities.create(
            target, "owned-" + UUID.randomUUID(), "Owned scope", "", "PRIVATE", "OPEN", null);
    var post =
        posts.publish(
            target,
            new ContentCommands.Publish(
                "Deleted private fixture",
                null,
                null,
                new ContentCommands.Audience("PUBLIC", List.of()),
                List.of(),
                List.of()),
            null);
    posts.delete(target, post.id(), post.version());
    var request =
        new CaseOperations.AccountRequest(
            target.userId(), CaseOperations.AccountAction.SUSPEND_ACCOUNT, "SECURITY_REVIEW");
    mvc.perform(
            post("/api/v1/admin/moderation/actions")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", "Bearer " + tokens.issue(a))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isPreconditionRequired());
    var result =
        mvc.perform(
                post("/api/v1/admin/moderation/actions")
                    .with(csrf().asHeader())
                    .header("Origin", "https://social-core.invalid")
                    .header("Authorization", "Bearer " + tokens.issue(a))
                    .header("If-Match", "\"0\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andReturn();
    var action =
        UUID.fromString(
            json.readTree(result.getResponse().getContentAsString()).get("actionId").asText());
    assertThat(operations.action(a, null, action).reason()).isEqualTo("SECURITY_REVIEW");
    denied(
        () ->
            operations.account(
                a,
                0,
                new CaseOperations.AccountRequest(
                    peer.userId(),
                    CaseOperations.AccountAction.SUSPEND_ACCOUNT,
                    "SECURITY_REVIEW")),
        403);
    denied(
        () ->
            operations.account(
                a,
                0,
                new CaseOperations.AccountRequest(
                    a.userId(), CaseOperations.AccountAction.SUSPEND_ACCOUNT, "SECURITY_REVIEW")),
        403);
    denied(
        () ->
            operations.account(
                a,
                0,
                new CaseOperations.AccountRequest(
                    target.userId(),
                    CaseOperations.AccountAction.REINSTATE_ACCOUNT,
                    "REVIEW_COMPLETE")),
        412);
    operations.account(
        a,
        1,
        new CaseOperations.AccountRequest(
            target.userId(), CaseOperations.AccountAction.REINSTATE_ACCOUNT, "REVIEW_COMPLETE"));
    mvc.perform(get("/api/v1/me/account").header("Authorization", "Bearer " + tokens.issue(target)))
        .andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM content.post WHERE id=?", String.class, post.id()))
        .isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_user_id FROM community.community WHERE id=? AND status='ACTIVE'",
                UUID.class,
                c.id()))
        .isEqualTo(target.userId());
    assertThat(queries.find(target.userId()).platformRole()).isEqualTo("USER");
    mvc.perform(
            get("/api/v1/admin/moderation/actions/" + action)
                .header("Authorization", "Bearer " + tokens.issue(peer)))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/admin/moderation/actions/" + action)
                .header("Authorization", "Bearer " + tokens.issue(session(target.userId(), 1))))
        .andExpect(status().isForbidden());
  }

  @Test
  void accountActionPayloadFailureRollsBackOwnerStatusEpochAuditAndOutbox() {
    var a = admin();
    var target = user();
    String constraint = "fixture_payload_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE moderation.action_detail ADD CONSTRAINT "
            + constraint
            + " CHECK(false) NOT VALID");
    try {
      assertThatThrownBy(
              () ->
                  operations.account(
                      a,
                      0,
                      new CaseOperations.AccountRequest(
                          target.userId(),
                          CaseOperations.AccountAction.SUSPEND_ACCOUNT,
                          "SECURITY_REVIEW")))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("ALTER TABLE moderation.action_detail DROP CONSTRAINT " + constraint);
    }
    assertThat(queries.find(target.userId()).active()).isTrue();
    assertThat(queries.find(target.userId()).authenticationEpoch()).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM moderation.action WHERE target_type='ACCOUNT' AND target_id=?",
                target.userId()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE action='ACCOUNT_SUSPENDED' AND target_id=?",
                target.userId()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE event_type='AccountStatusChanged' AND aggregate_id=?",
                target.userId()))
        .isZero();
  }

  @Test
  void holdAssignmentAndPrivateReadsFailClosedOnAuditAndOutboxFailureWithoutReleasingHold() {
    var a = admin();
    var r = freshReport(user(), user());
    operations.assign(a, null, r.id(), 0, a.userId());
    var hold = operations.hold(a, null, CaseOperations.Root.REPORT, r.id(), 1, future());
    String constraint = "fixture_case_audit_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE platform.audit_entry ADD CONSTRAINT "
            + constraint
            + " CHECK (actor_id IS DISTINCT FROM '"
            + a.userId()
            + "'::uuid) NOT VALID");
    try {
      assertThatThrownBy(() -> operations.assign(a, null, r.id(), 2, null))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(
              () ->
                  operations.hold(
                      a,
                      null,
                      CaseOperations.Root.REPORT,
                      r.id(),
                      2,
                      new CaseOperations.Hold(null, null)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(() -> operations.history(a, null, r.id(), 20, 0))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(
              () -> operations.queue(a, null, new CaseOperations.Filter(null, null, false), 20, 0))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(() -> reports.evidence(a, null, r.id()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThatThrownBy(() -> operations.authorizeMetrics(a))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbc.execute("ALTER TABLE platform.audit_entry DROP CONSTRAINT " + constraint);
    }
    assertThat(count("SELECT version FROM moderation.report WHERE id=?", r.id())).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM moderation.case_history WHERE report_id=?", r.id()))
        .isEqualTo(3);
    String outbox = "fixture_case_outbox_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "ALTER TABLE platform.outbox_event ADD CONSTRAINT "
            + outbox
            + " CHECK (aggregate_id IS DISTINCT FROM '"
            + r.id()
            + "'::uuid) NOT VALID");
    try {
      assertThatThrownBy(() -> operations.assign(a, null, r.id(), 2, null))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbc.execute("ALTER TABLE platform.outbox_event DROP CONSTRAINT " + outbox);
    }
    assertThat(count("SELECT version FROM moderation.report WHERE id=?", r.id())).isEqualTo(2);
    var unassigned = operations.assign(a, null, r.id(), 2, null);
    assertThat(unassigned.assignedTo()).isNull();
    assertThat(unassigned.holdUntil()).isEqualTo(hold.holdUntil());
  }

  @Test
  void httpAssignmentHoldHistoryAndMaintenanceAreAuthenticatedVersionedAndAudited()
      throws Exception {
    var a = admin();
    var r = freshReport(user(), user());
    String bearer = "Bearer " + tokens.issue(a);
    String path = "/api/v1/admin/moderation/reports/" + r.id();
    mvc.perform(
            put(path + "/assignment")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignedTo\":null}"))
        .andExpect(status().isPreconditionRequired());
    mvc.perform(
            put(path + "/assignment")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("assignedTo", a.userId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedTo").value(a.userId().toString()));
    mvc.perform(
            get("/api/v1/admin/moderation/reports")
                .param("assignedTo", a.userId().toString())
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(r.id().toString()));
    mvc.perform(
            put(path + "/hold")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(future())))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"2\""));
    mvc.perform(get(path + "/history").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3));
    mvc.perform(get(path + "/history")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/moderation/metrics")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/admin/moderation/metrics")
                .header("Authorization", "Bearer " + tokens.issue(user())))
        .andExpect(status().isForbidden());
    var snapshot =
        mvc.perform(get("/api/v1/admin/moderation/metrics").header("Authorization", bearer))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(snapshot)
        .contains("moderation_legacy_payloads", "moderation_payloads_total")
        .doesNotContain(r.id().toString(), "Private fixture", a.userId().toString());
    mvc.perform(
            post("/api/v1/admin/moderation/maintenance/RETENTION")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", bearer))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/moderation/maintenance/ENCRYPT").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.legacyPayloads").isNumber());
    denied(() -> operations.maintenance(user(), CaseOperations.Maintenance.ENCRYPT, true), 403);
    denied(
        () ->
            operations.hold(
                a,
                null,
                CaseOperations.Root.REPORT,
                r.id(),
                2,
                new CaseOperations.Hold(
                    Instant.now().plus(Duration.ofDays(366)), "ENGINEERING_REVIEW")),
        400);
  }
}
