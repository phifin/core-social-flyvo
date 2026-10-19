package com.flyvo.socialcore.account;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import com.flyvo.socialcore.identity.application.*;
import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof;
import com.flyvo.socialcore.platform.api.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Import(ErasureFoundationIT.FlowFixtures.class)
@Testcontainers
class ErasureProofChainIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
              "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
          .withDatabaseName("p9a_proof_chain");

  static final Path ARCHIVE = ErasureFoundationIT.directory();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
    r.add("app.erasure.enabled", () -> true);
    r.add("app.erasure.policy", () -> AccountErasure.POLICY);
    r.add("app.erasure.lineage", () -> "53e946f1-724e-4620-a272-a6b4d01cffb7");
    r.add("app.erasure.archive-directory", ARCHIVE::toString);
  }

  @Autowired OAuthAuthentication oauth;
  @Autowired Authentication auth;
  @MockitoSpyBean IdentityRepository identity;
  @MockitoSpyBean ErasureStore erasureStore;
  @Autowired Secrets secrets;
  @Autowired JdbcTemplate jdbc;
  @Autowired tools.jackson.databind.ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired ErasureFoundationIT.FlowClock clock;
  @Autowired ErasureFoundationIT.SyntheticProvider provider;
  @Autowired AccountCommands accounts;
  @MockitoSpyBean com.flyvo.socialcore.profile.api.ProfileCommands profiles;
  @Autowired com.flyvo.socialcore.identity.application.port.PasswordHashing passwords;
  @Autowired com.flyvo.socialcore.account.application.Erasures erasures;
  @Autowired com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore jobs;
  @Autowired List<JobHandler> handlers;
  @Autowired List<ExternalJobHandler> externalHandlers;

  TransactionTemplate tx() {
    return new TransactionTemplate(manager);
  }

  @BeforeEach
  void resetClock() {
    clock.fixed = null;
    provider.afterExchange = () -> {};
    // Production ports both query PG clock_timestamp(). Freeze that authority only in this
    // fixture so real row waits cross exact microsecond boundaries without wall-clock sleeps.
    org.mockito.Mockito.doAnswer(call -> clock.fixed == null ? call.callRealMethod() : clock.fixed)
        .when(identity)
        .databaseTime();
    org.mockito.Mockito.doAnswer(call -> clock.fixed == null ? call.callRealMethod() : clock.fixed)
        .when(erasureStore)
        .now();
    jdbc.update(
        "UPDATE platform.job SET available_at=timestamptz '2100-01-01Z' WHERE status IN ('PENDING','RETRY')");
  }

  record Existing(ActorContext actor, String email) {}

  Existing existing(String email) {
    var encoded = passwords.encode("Synthetic orchard island river 84");
    return tx().execute(
            s -> {
              var id = accounts.createVerified();
              profiles.provision(id);
              identity.claimEmail(id, email, clock.instant());
              identity.credential(id, encoded, clock.instant());
              var session = UUID.randomUUID();
              identity.createSession(session, id, clock.instant(), false, 0);
              return new Existing(new ActorContext(id, session), email);
            });
  }

  void converge(UUID user, int passes) {
    tx().executeWithoutResult(s -> erasures.reconcile(user));
    for (int i = 0; i < passes; i++) {
      jdbc.update(
          "UPDATE platform.job SET available_at=LEAST(available_at,?) WHERE status IN ('PENDING','RETRY') AND (payload->>'data' LIKE ? OR payload->>'data' IN (SELECT e.id::text FROM account.erasure_export e JOIN account.erasure_intent i ON i.id=e.intent_id WHERE i.user_id=?))",
          clock.instant().atOffset(ZoneOffset.UTC),
          "%" + user + "%",
          user);
      // A frozen clock plus forced early timing waits would tie every due time. Drive each
      // real handler fairly, rather than let the lowest random job UUID starve an exporter.
      for (var kind : List.of("ERASURE_EXPORT", "ERASURE_TECHNICAL"))
        jobs.consumeExternalJob(
            UUID.randomUUID(),
            externalHandlers.stream().filter(h -> kind.equals(h.kind())).toList());
      for (var kind : List.of("ERASURE_RECONCILE", "IDENTITY_ERASURE", "PROFILE_ERASURE"))
        jobs.consumeJob(
            UUID.randomUUID(), handlers.stream().filter(h -> kind.equals(h.kind())).toList());
    }
  }

  record Capability(UUID id, String secret, String browser, Instant expiry) {}

  Capability onboarding(Instant expiry) {
    var id = UUID.randomUUID();
    var secret = secrets.randomToken();
    var browser = secrets.randomToken();
    var proof =
        new ProviderProof("GOOGLE", "https://synthetic-provider.invalid", id.toString(), null);
    tx().executeWithoutResult(
            s ->
                identity.onboarding(
                    id,
                    secrets.hash(secret),
                    secrets.hash(browser),
                    secrets.encrypt("onboarding", json.writeValueAsString(proof)),
                    expiry));
    return new Capability(id, secret, browser, expiry);
  }

  Object register(Capability cap, String email) {
    try {
      oauth.register(email, cap.secret(), cap.browser(), secrets.randomToken());
      return Boolean.TRUE;
    } catch (Failure failure) {
      return failure;
    }
  }

  void awaitBlocked() {
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0",
                        Integer.class)
                    > 0);
  }

  Object outcome(Callable<?> command) throws Exception {
    try {
      return command.call();
    } catch (Failure failure) {
      return failure;
    }
  }

  Object throughLock(String sql, Object key, Instant afterWait, Callable<?> command)
      throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var holder =
          pool.submit(
              () ->
                  tx().executeWithoutResult(
                          s -> {
                            jdbc.queryForObject(sql, Integer.class, key);
                            locked.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("SYNTHETIC_LOCK_TIMEOUT");
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var attempt = pool.submit(() -> outcome(command));
      try {
        awaitBlocked();
        assertThat(attempt.isDone()).isFalse();
        clock.fixed = afterWait;
      } finally {
        release.countDown();
      }
      holder.get(10, TimeUnit.SECONDS);
      return attempt.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
  }

  String state(OAuthAuthentication.Start start) {
    return start.location().substring(start.location().indexOf("state=") + 6);
  }

  Instant expiry(String table, byte[] hash) {
    // Only fixed fixture callers choose the owned table name, never HTTP input.
    return jdbc.queryForObject(
            "SELECT expires_at FROM identity."
                + table
                + " WHERE "
                + (table.equals("oauth_transaction") ? "state_hash" : "secret_hash")
                + "=?",
            java.time.OffsetDateTime.class,
            hash)
        .toInstant();
  }

  String mailbox(String email) {
    return jdbc.queryForObject(
        "SELECT j.id,p.ciphertext FROM identity.registration_intent i JOIN platform.job j ON j.kind='MAIL' AND j.dedup_key=i.id::text JOIN platform.secret_mail_payload p ON p.job_id=j.id WHERE i.email_normalized=? ORDER BY i.created_at DESC LIMIT 1",
        (r, n) ->
            json.readValue(
                    secrets.decrypt("mail:" + r.getObject(1, UUID.class), r.getBytes(2)),
                    com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore.Mail
                        .class)
                .secret(),
        email);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void boundCallbackAccountGuardWaitCannotGrantAfterOriginalExpiry(long micros) throws Exception {
    clock.fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var user = existing(UUID.randomUUID() + "@example.test");
    provider.proof =
        new ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", UUID.randomUUID().toString(), null);
    tx().executeWithoutResult(
            s ->
                identity.bind(
                    UUID.randomUUID(), user.actor().userId(), provider.proof, clock.instant()));
    var start = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var expiry = expiry("oauth_transaction", secrets.hash(state(start)));
    clock.fixed = expiry.minusNanos(1000);
    var result =
        throughLock(
            "SELECT 1 FROM account.user_account WHERE id=? FOR UPDATE",
            user.actor().userId(),
            expiry.plusNanos(micros * 1000),
            () -> oauth.callback("GOOGLE", state(start), start.browser(), "synthetic", null));
    if (micros < 0) {
      assertThat(result instanceof OAuthAuthentication.Result).isTrue();
      assertThat(((OAuthAuthentication.Result) result).marker()).isEqualTo("signed_in");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM identity.auth_session WHERE user_id=?",
                  Integer.class,
                  user.actor().userId()))
          .isEqualTo(2);
    } else {
      assertThat(result instanceof Failure).isTrue();
      assertThat(((Failure) result).code()).isEqualTo("INVALID_OAUTH_TRANSACTION");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM identity.auth_session WHERE user_id=?",
                  Integer.class,
                  user.actor().userId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='LOGIN_SUCCEEDED'",
                  Integer.class,
                  user.actor().userId()))
          .isZero();
    }
  }

  @Test
  void expiryDuringRealProfileProvisionRollsBackAllRegistrationEffects() {
    com.flyvo.socialcore.profile.api.ProfileCommands target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(profiles);
    clock.fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var email = UUID.randomUUID() + "@example.test";
    var browser = secrets.randomToken();
    auth.register(email, browser);
    var secret = mailbox(email);
    var expiry =
        jdbc.queryForObject(
                "SELECT expires_at FROM identity.registration_intent WHERE token_hash=?",
                java.time.OffsetDateTime.class,
                secrets.hash(secret))
            .toInstant();
    clock.fixed = expiry.minusNanos(1000);
    int before = jdbc.queryForObject("SELECT count(*) FROM account.user_account", Integer.class);
    org.mockito.Mockito.doAnswer(
            call -> {
              var result = call.callRealMethod();
              clock.fixed = expiry;
              return result;
            })
        .when(target)
        .provision(org.mockito.ArgumentMatchers.any());
    try {
      assertThatThrownBy(() -> auth.complete(secret, browser, "Synthetic river orchard planet 94"))
          .hasMessage("INVALID_PROOF");
    } finally {
      org.mockito.Mockito.reset(target);
    }
    assertThat(identity.emailOwner(email)).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM account.user_account", Integer.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM identity.registration_intent WHERE token_hash=?",
                Boolean.class,
                secrets.hash(secret)))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM profile.profile p LEFT JOIN account.user_account a ON a.id=p.user_id WHERE a.id IS NULL",
                Integer.class))
        .isZero();
  }

  @Test
  void realErasureHoldsClaimsUntilFiftyMinutesThenOnlyFreshProofCanCreateNewUuid() {
    var t0 = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    clock.fixed = t0.minusNanos(1000);
    var email = UUID.randomUUID() + "@example.test";
    var localBrowser = secrets.randomToken();
    auth.register(email, localBrowser);
    var oldMailbox = mailbox(email);
    var user = existing(email);
    provider.proof =
        new ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", UUID.randomUUID().toString(), null);
    var anonymous = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var oldOnboarding =
        oauth.callback("GOOGLE", state(anonymous), anonymous.browser(), "synthetic", null);
    var held = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var heldOnboarding = oauth.callback("GOOGLE", state(held), held.browser(), "synthetic", null);
    var delayed = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    // The actual LINK flow binds the subject after the anonymous proofs were captured.
    var link = oauth.start("GOOGLE", "LINK", user.actor(), null);
    assertThat(oauth.callback("GOOGLE", state(link), link.browser(), "synthetic", null).marker())
        .isEqualTo("linked");
    auth.requestReset(email);
    var reset =
        jdbc.queryForObject(
            "SELECT j.id,p.ciphertext FROM identity.one_time_token t JOIN platform.job j ON j.kind='MAIL' AND j.dedup_key=t.id::text JOIN platform.secret_mail_payload p ON p.job_id=j.id WHERE t.user_id=? AND t.purpose='RESET_PASSWORD' ORDER BY t.created_at DESC LIMIT 1",
            (r, n) ->
                json.readValue(
                        secrets.decrypt("mail:" + r.getObject(1, UUID.class), r.getBytes(2)),
                        com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore
                            .Mail.class)
                    .secret(),
            user.actor().userId());
    clock.fixed = t0;
    auth.close(user.actor(), 0);
    var request = erasureStore.request(user.actor().userId());
    assertThat(request.at()).isEqualTo(t0);
    converge(user.actor().userId(), 30);
    // A captured unbound provider proof can still issue an alternate mailbox proof before
    // its own deadline; the held subject claim forbids completing that registration.
    clock.fixed = t0.plusSeconds(540);
    var alternate = UUID.randomUUID() + "@example.test";
    var alternateBrowser = secrets.randomToken();
    oauth.register(alternate, oldOnboarding.onboarding(), anonymous.browser(), alternateBrowser);
    var oldProviderMailbox = mailbox(alternate);
    assertThatThrownBy(() -> auth.complete(oldProviderMailbox, alternateBrowser, null))
        .isInstanceOf(Failure.class);
    clock.fixed = t0.plusSeconds(3000).minusNanos(1000);
    converge(user.actor().userId(), 15);
    assertThat(identity.emailOwner(email)).isEqualTo(user.actor().userId());
    assertThat(
            identity.external("GOOGLE", provider.proof.issuer(), provider.proof.subject()).userId())
        .isEqualTo(user.actor().userId());
    assertThat(
            erasureStore
                .step(user.actor().userId(), AccountErasure.Step.IDENTITY, false)
                .view()
                .reason())
        .isEqualTo("ANONYMOUS_PROOF_DRAIN");
    clock.fixed = t0.plusSeconds(3000);
    converge(user.actor().userId(), 15);
    assertThat(
            erasureStore
                .step(user.actor().userId(), AccountErasure.Step.IDENTITY, false)
                .view()
                .status())
        .isEqualTo("DONE");
    assertThat(identity.emailOwner(email)).isNull();
    assertThat(identity.external("GOOGLE", provider.proof.issuer(), provider.proof.subject()))
        .isNull();
    assertThatThrownBy(
            () -> oauth.callback("GOOGLE", state(delayed), delayed.browser(), "synthetic", null))
        .hasMessage("INVALID_OAUTH_TRANSACTION");
    assertThatThrownBy(
            () ->
                oauth.register(
                    email, heldOnboarding.onboarding(), held.browser(), secrets.randomToken()))
        .hasMessage("INVALID_PROOF");
    assertThatThrownBy(
            () -> auth.complete(oldMailbox, localBrowser, "Synthetic old mailbox cannot return 94"))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.complete(oldProviderMailbox, alternateBrowser, null))
        .hasMessage("INVALID_PROOF");
    assertThatThrownBy(() -> auth.reset(reset, "Synthetic old reset cannot return 95"))
        .isInstanceOf(Failure.class);
    var fresh = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var freshOnboarding =
        oauth.callback("GOOGLE", state(fresh), fresh.browser(), "synthetic", null);
    var browser = secrets.randomToken();
    oauth.register(email, freshOnboarding.onboarding(), fresh.browser(), browser);
    var grant = auth.complete(mailbox(email), browser, null);
    var next = identity.session(grant.sessionId(), false).userId();
    assertThat(next).isNotEqualTo(user.actor().userId());
    assertThat(identity.emailOwner(email)).isEqualTo(next);
    assertThat(
            identity.external("GOOGLE", provider.proof.issuer(), provider.proof.subject()).userId())
        .isEqualTo(next);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM account.user_account WHERE id=?",
                String.class,
                user.actor().userId()))
        .isEqualTo("DELETION_PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.erasure_step WHERE user_id=? AND reason='OWNER_NOT_IMPLEMENTED'",
                Integer.class,
                user.actor().userId()))
        .isEqualTo(8);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account.user_account WHERE status='DELETED'", Integer.class))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void dispatchWindowWaitCannotRenewExpiredOnboarding(long micros) throws Exception {
    var expiry = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    clock.fixed = expiry.minusNanos(1000);
    var cap = onboarding(expiry);
    var email = UUID.randomUUID() + "@example.test";
    var key = secrets.emailKey(email);
    tx().executeWithoutResult(s -> identity.dispatch(key, "VERIFICATION", clock.fixed));
    var result =
        throughLock(
            "SELECT 1 FROM identity.email_dispatch_window WHERE email_key=? FOR UPDATE",
            key,
            expiry.plusNanos(micros * 1000),
            () -> register(cap, email));
    int count =
        jdbc.queryForObject(
            "SELECT count(*) FROM identity.registration_intent WHERE email_normalized=?",
            Integer.class,
            email);
    if (micros < 0) {
      assertThat(result).isEqualTo(Boolean.TRUE);
      assertThat(count).isEqualTo(1);
    } else {
      assertThat(result instanceof Failure).isTrue();
      assertThat(((Failure) result).code()).isEqualTo("INVALID_PROOF");
      assertThat(count).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT consumed_at IS NULL FROM identity.external_onboarding WHERE id=?",
                  Boolean.class,
                  cap.id()))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT issued_count FROM identity.email_dispatch_window WHERE email_key=?",
                  Integer.class,
                  key))
          .isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void oauthStateRowWaitDoesNotConsumeOrExchangeAtExpiry(long micros) throws Exception {
    clock.fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    provider.proof =
        new ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", UUID.randomUUID().toString(), null);
    var start = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var expiry = expiry("oauth_transaction", secrets.hash(state(start)));
    var exchanges = new java.util.concurrent.atomic.AtomicInteger();
    provider.afterExchange = exchanges::incrementAndGet;
    clock.fixed = expiry.minusNanos(1000);
    int before =
        jdbc.queryForObject("SELECT count(*) FROM identity.external_onboarding", Integer.class);
    var result =
        throughLock(
            "SELECT 1 FROM identity.oauth_transaction WHERE state_hash=? FOR UPDATE",
            secrets.hash(state(start)),
            expiry.plusNanos(micros * 1000),
            () -> oauth.callback("GOOGLE", state(start), start.browser(), "synthetic", null));
    if (micros < 0) {
      assertThat(result instanceof OAuthAuthentication.Result).isTrue();
      assertThat(exchanges).hasValue(1);
      assertThat(
              expiry(
                  "external_onboarding",
                  secrets.hash(((OAuthAuthentication.Result) result).onboarding())))
          .isBeforeOrEqualTo(expiry.plusSeconds(600));
    } else {
      assertThat(result instanceof Failure).isTrue();
      assertThat(((Failure) result).code()).isEqualTo("INVALID_OAUTH_TRANSACTION");
      assertThat(exchanges).hasValue(0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT consumed_at IS NULL FROM identity.oauth_transaction WHERE state_hash=?",
                  Boolean.class,
                  secrets.hash(state(start))))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM identity.external_onboarding", Integer.class))
          .isEqualTo(before);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void callbackNetworkCompletionHasOriginalAbsoluteDeadline(long micros) throws Exception {
    clock.fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    provider.proof =
        new ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", UUID.randomUUID().toString(), null);
    var start = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var expiry = expiry("oauth_transaction", secrets.hash(state(start)));
    clock.fixed = expiry.minusNanos(1000);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    provider.afterExchange =
        () -> {
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("SYNTHETIC_NETWORK_TIMEOUT");
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        };
    int before =
        jdbc.queryForObject("SELECT count(*) FROM identity.external_onboarding", Integer.class);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var attempt =
          pool.submit(
              () ->
                  outcome(
                      () ->
                          oauth.callback(
                              "GOOGLE", state(start), start.browser(), "synthetic", null)));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        clock.fixed = expiry.plusNanos(micros * 1000);
      } finally {
        release.countDown();
      }
      var result = attempt.get(10, TimeUnit.SECONDS);
      if (micros < 0) {
        assertThat(result instanceof OAuthAuthentication.Result).isTrue();
        assertThat(
                expiry(
                    "external_onboarding",
                    secrets.hash(((OAuthAuthentication.Result) result).onboarding())))
            .isBeforeOrEqualTo(expiry.plusSeconds(600));
      } else {
        assertThat(result instanceof Failure).isTrue();
        assertThat(((Failure) result).code()).isEqualTo("INVALID_OAUTH_TRANSACTION");
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM identity.external_onboarding", Integer.class))
            .isEqualTo(before);
      }
      assertThat(
              jdbc.queryForObject(
                  "SELECT consumed_at IS NOT NULL FROM identity.oauth_transaction WHERE state_hash=?",
                  Boolean.class,
                  secrets.hash(state(start))))
          .isTrue();
    } finally {
      release.countDown();
      provider.afterExchange = () -> {};
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void mailboxRowWaitCannotCompleteAtOrAfterItsExpiry(long micros) throws Exception {
    clock.fixed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var email = UUID.randomUUID() + "@example.test";
    var browser = secrets.randomToken();
    auth.register(email, browser);
    var secret = mailbox(email);
    var expiry =
        jdbc.queryForObject(
                "SELECT expires_at FROM identity.registration_intent WHERE token_hash=?",
                java.time.OffsetDateTime.class,
                secrets.hash(secret))
            .toInstant();
    clock.fixed = expiry.minusNanos(1000);
    int before = jdbc.queryForObject("SELECT count(*) FROM account.user_account", Integer.class);
    var result =
        throughLock(
            "SELECT 1 FROM identity.registration_intent WHERE token_hash=? FOR UPDATE",
            secrets.hash(secret),
            expiry.plusNanos(micros * 1000),
            () -> auth.complete(secret, browser, "Synthetic river orchard planet 92"));
    if (micros < 0) {
      assertThat(result instanceof Authentication.Grant).isTrue();
      assertThat(identity.emailOwner(email)).isNotNull();
    } else {
      assertThat(result instanceof Failure).isTrue();
      assertThat(((Failure) result).code()).isEqualTo("INVALID_PROOF");
      assertThat(identity.emailOwner(email)).isNull();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM account.user_account", Integer.class))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "SELECT consumed_at IS NULL FROM identity.registration_intent WHERE token_hash=?",
                  Boolean.class,
                  secrets.hash(secret)))
          .isTrue();
    }
  }

  @Test
  void fullAnonymousChainKeepsInheritedFiftyMinuteAbsoluteBoundWithoutRenewal() {
    var t0 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    clock.fixed = t0.minusNanos(1000);
    provider.proof =
        new ProviderProof(
            "GOOGLE", "https://synthetic-provider.invalid", UUID.randomUUID().toString(), null);
    var start = oauth.start("GOOGLE", "LOGIN", ActorContext.anonymous(), null);
    var callbackExpiry = expiry("oauth_transaction", secrets.hash(state(start)));
    clock.fixed = callbackExpiry.minusNanos(1000);
    var result = oauth.callback("GOOGLE", state(start), start.browser(), "synthetic", null);
    var onboardingExpiry = expiry("external_onboarding", secrets.hash(result.onboarding()));
    assertThat(onboardingExpiry).isBeforeOrEqualTo(callbackExpiry.plusSeconds(600));
    clock.fixed = onboardingExpiry.minusNanos(1000);
    var email = UUID.randomUUID() + "@example.test";
    var browser = secrets.randomToken();
    oauth.register(email, result.onboarding(), start.browser(), browser);
    var secret = mailbox(email);
    var mailboxExpiry =
        jdbc.queryForObject(
                "SELECT expires_at FROM identity.registration_intent WHERE token_hash=?",
                java.time.OffsetDateTime.class,
                secrets.hash(secret))
            .toInstant();
    assertThat(mailboxExpiry).isBeforeOrEqualTo(onboardingExpiry.plusSeconds(1800));
    assertThat(mailboxExpiry).isBefore(t0.plusSeconds(3000));
    int before = jdbc.queryForObject("SELECT count(*) FROM account.user_account", Integer.class);
    clock.fixed = t0.plusSeconds(3000);
    assertThatThrownBy(() -> auth.complete(secret, browser, null)).hasMessage("INVALID_PROOF");
    assertThat(identity.emailOwner(email)).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM account.user_account", Integer.class))
        .isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void onboardingExpiryIsRecheckedAfterRealRowLockBeforeAtAndAfterBoundary(long micros)
      throws Exception {
    var expiry = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var cap = onboarding(expiry);
    var email = UUID.randomUUID() + "@example.test";
    clock.fixed = expiry.minusNanos(1000);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    int mailBefore =
        jdbc.queryForObject("SELECT count(*) FROM platform.job WHERE kind='MAIL'", Integer.class);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var holder =
          pool.submit(
              () ->
                  tx().executeWithoutResult(
                          s -> {
                            jdbc.queryForObject(
                                "SELECT id FROM identity.external_onboarding WHERE id=? FOR UPDATE",
                                UUID.class,
                                cap.id());
                            locked.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("SYNTHETIC_LOCK_TIMEOUT");
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var attempt = pool.submit(() -> register(cap, email));
      try {
        awaitBlocked();
        assertThat(attempt.isDone()).isFalse();
        clock.fixed = expiry.plusNanos(micros * 1000);
      } finally {
        release.countDown();
      }
      holder.get(10, TimeUnit.SECONDS);
      var outcome = attempt.get(10, TimeUnit.SECONDS);
      if (micros < 0) {
        assertThat(outcome).isEqualTo(Boolean.TRUE);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM identity.registration_intent WHERE email_normalized=?",
                    Integer.class,
                    email))
            .isEqualTo(1);
      } else {
        assertThat(outcome).isInstanceOf(Failure.class);
        assertThat(((Failure) outcome).code()).isEqualTo("INVALID_PROOF");
        assertThat(
                jdbc.queryForObject(
                    "SELECT consumed_at IS NULL FROM identity.external_onboarding WHERE id=?",
                    Boolean.class,
                    cap.id()))
            .isTrue();
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM identity.registration_intent WHERE email_normalized=?",
                    Integer.class,
                    email))
            .isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM platform.job WHERE kind='MAIL'", Integer.class))
            .isEqualTo(mailBefore);
      }
    } finally {
      release.countDown();
      clock.fixed = null;
    }
  }
}
