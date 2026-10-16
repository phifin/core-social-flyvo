package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.account.api.AccountCommands;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.identity.application.Authentication;
import com.flyvo.socialcore.identity.application.OAuthAuthentication;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import com.flyvo.socialcore.identity.application.port.ProviderClient;
import com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.application.port.MailTransport;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.application.Profiles;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthenticationIT {
  @Autowired org.springframework.core.env.Environment environment;

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @Container
  static final GenericContainer<?> MAIL =
      new GenericContainer<>(
              "axllent/mailpit@sha256:b68349e3a014b90c5610bfb26b2ae36f3892d7b8cf25ee140c6c71c98d2fcf48")
          .withExposedPorts(1025, 8025);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", POSTGRES::getJdbcUrl);
    r.add("app.database.username", POSTGRES::getUsername);
    r.add("app.database.password", POSTGRES::getPassword);
    r.add("spring.mail.host", MAIL::getHost);
    r.add("spring.mail.port", () -> MAIL.getMappedPort(1025));
    r.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
    r.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
  }

  @TestConfiguration
  static class Fixtures {
    @Bean
    @Primary
    BlockingPasswords blockingPasswords(
        com.flyvo.socialcore.identity.infrastructure.security.ArgonPasswords passwords) {
      return new BlockingPasswords(passwords);
    }

    @Bean
    @Primary
    FakeProvider fakeProvider() {
      return new FakeProvider();
    }

    @Bean
    EventConsumer consumer(JdbcTemplate jdbc) {
      return new EventConsumer() {
        public String name() {
          return "phase1-contract";
        }

        public boolean accepts(String type) {
          return "ContractFact".equals(type);
        }

        public void apply(EventEnvelope event) {
          UUID id = event.eventId();
          String payload = event.payload();
          jdbc.update(
              "INSERT INTO platform.job(id,kind,dedup_key,payload,status,available_at,created_at) VALUES (?,'CONTRACT',?,'{}','COMPLETED',now(),now())",
              UUID.randomUUID(),
              id.toString());
          if (payload.contains("forceFailure")) throw new IllegalStateException("CONSUMER_FAILED");
          if (payload.contains("expireLease"))
            jdbc.update(
                "UPDATE platform.outbox_delivery SET lease_until=now()-interval '1 second' WHERE event_id=?",
                id);
        }
      };
    }
  }

  static class BlockingPasswords
      implements com.flyvo.socialcore.identity.application.port.PasswordHashing {
    private final com.flyvo.socialcore.identity.application.port.PasswordHashing delegate;
    volatile CountDownLatch entered;
    volatile CountDownLatch resume;

    BlockingPasswords(com.flyvo.socialcore.identity.application.port.PasswordHashing delegate) {
      this.delegate = delegate;
    }

    public String encode(String password) {
      return delegate.encode(password);
    }

    public boolean needsUpgrade(String encoded) {
      return delegate.needsUpgrade(encoded);
    }

    public boolean matches(String password, String encoded) {
      boolean valid = delegate.matches(password, encoded);
      if (entered != null && Thread.currentThread().getName().startsWith("obsolete-password")) {
        entered.countDown();
        try {
          if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return valid;
    }
  }

  static class FakeProvider implements ProviderClient {
    final Map<String, ProviderProof> codes = new ConcurrentHashMap<>();
    final AtomicInteger exchanges = new AtomicInteger();

    public String authorization(String provider, String state, String nonce, String verifier) {
      return "https://provider.example/authorize?state=" + state;
    }

    public ProviderProof verify(String provider, String code, byte[] nonce, String verifier) {
      exchanges.incrementAndGet();
      var value = codes.get(code);
      if (value == null) throw new Failure(401, "PROVIDER_PROOF_INVALID");
      return value;
    }
  }

  @Autowired Authentication auth;
  @Autowired BlockingPasswords blockingPasswords;
  @Autowired OAuthAuthentication oauth;
  @Autowired FakeProvider provider;
  @Autowired JdbcTemplate jdbc;
  @Autowired Secrets secrets;
  @Autowired DurableWork work;
  @Autowired DurableWorkStore jobs;
  @Autowired MailTransport mail;
  @Autowired AccessTokens tokens;
  @Autowired IdentityRepository identities;
  @Autowired AccountCommands accounts;
  @MockitoSpyBean AccountQueries accountQueries;
  @Autowired Profiles profiles;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  private static final String PASSWORD = "A mailbox owner chooses this password";

  private String address() {
    return "fixture-" + UUID.randomUUID() + "@example.org";
  }

  private ActorContext actor(Authentication.Grant grant) {
    return tokens.validate(grant.accessToken());
  }

  private String mailSecret(String email, String template) {
    var rows =
        jdbc.query(
            "SELECT p.job_id,p.ciphertext FROM platform.secret_mail_payload p JOIN platform.job j ON j.id=p.job_id WHERE j.payload->>'template'=? ORDER BY j.created_at DESC",
            (rs, n) ->
                json.readValue(
                    secrets.decrypt("mail:" + rs.getObject(1, UUID.class), rs.getBytes(2)),
                    DurableWorkStore.Mail.class),
            template);
    return rows.stream().filter(m -> m.address().equals(email)).findFirst().orElseThrow().secret();
  }

  private Authentication.Grant user(String email) {
    String browser = secrets.randomToken();
    auth.register(email, browser);
    return auth.complete(mailSecret(email, "VERIFY_EMAIL"), browser, PASSWORD);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private String state(OAuthAuthentication.Start start) {
    return start.location().substring(start.location().indexOf("state=") + 6);
  }

  private OAuthAuthentication.Result callback(
      ProviderProof proof, String intent, ActorContext actor) {
    var start = oauth.start(proof.provider(), intent, actor, null);
    String code = UUID.randomUUID().toString();
    provider.codes.put(code, proof);
    return oauth.callback(proof.provider(), state(start), start.browser(), code, null);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
      String route, Object body) {
    return post(route)
        .with(csrf().asHeader())
        .header("Origin", "https://social-core.invalid")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))
        .with(
            request -> {
              request.setRemoteAddr("192.0.2." + (1 + ThreadLocalRandom.current().nextInt(200)));
              return request;
            });
  }

  private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(
              executor.submit(() -> run(barrier, first)),
              executor.submit(() -> run(barrier, second)));
      return List.of(
          futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
    }
  }

  private Object run(CyclicBarrier barrier, Callable<?> action) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
      Object value = action.call();
      return value == null ? Boolean.TRUE : value;
    } catch (Exception failure) {
      return failure;
    }
  }

  // Pause only the tested thread immediately before the real account guard. All reads/writes,
  // locks and commits still run against PostgreSQL; the competing command commits before resume.
  private <T> T afterCompetingCommit(UUID user, Callable<T> action, Runnable competing)
      throws Exception {
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    AccountQueries spy =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(accountQueries);
    doAnswer(
            invocation -> {
              if (Thread.currentThread().getName().equals("review-guard-race")) {
                entered.countDown();
                if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
              }
              return invocation.callRealMethod();
            })
        .when(spy)
        .guard(eq(user), anyBoolean());
    try (var executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "review-guard-race"))) {
      var result = executor.submit(action);
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        competing.run();
      } finally {
        resume.countDown();
      }
      return result.get(20, TimeUnit.SECONDS);
    } finally {
      reset(spy);
    }
  }

  @Test
  void resetIssuanceAfterRecoveryEmailChangeCannotSendOldMailboxTakeoverProof() throws Exception {
    String oldEmail = address(), newEmail = address();
    var owner = actor(user(oldEmail));
    auth.requestReset(oldEmail);
    var oldProof = mailSecret(oldEmail, "RESET_PASSWORD");
    auth.requestEmail(owner, newEmail);
    var changeProof = mailSecret(newEmail, "CHANGE_EMAIL");
    long resetJobs =
        count("SELECT count(*) FROM platform.job WHERE payload->>'template'='RESET_PASSWORD'");
    afterCompetingCommit(
        owner.userId(),
        () -> {
          auth.requestReset(oldEmail);
          return true;
        },
        () -> auth.completeEmail(owner, changeProof));
    assertThat(
            count("SELECT count(*) FROM platform.job WHERE payload->>'template'='RESET_PASSWORD'"))
        .isEqualTo(resetJobs);
    assertThat(
            count(
                "SELECT count(*) FROM identity.one_time_token WHERE user_id=? AND purpose='RESET_PASSWORD' AND consumed_at IS NULL",
                owner.userId()))
        .isZero();
    assertThatThrownBy(() -> auth.reset(oldProof, "An old mailbox must never take over"))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.login(oldEmail, PASSWORD)).isInstanceOf(Failure.class);
    assertThat(auth.login(newEmail, PASSWORD)).isNotNull();
    auth.requestReset(newEmail);
    auth.reset(mailSecret(newEmail, "RESET_PASSWORD"), "A new mailbox chooses this password");
    assertThat(auth.login(newEmail, "A new mailbox chooses this password")).isNotNull();
  }

  @Test
  void oauthLoginCannotUseBindingUnlinkedBeforeItsAccountGuard() throws Exception {
    oauthUnlinkBeforeGuard("LOGIN");
  }

  @Test
  void oauthReauthCannotUseBindingUnlinkedBeforeItsAccountGuard() throws Exception {
    oauthUnlinkBeforeGuard("REAUTH");
  }

  @Test
  void oauthSameAccountLinkCannotReportBindingUnlinkedBeforeItsAccountGuard() throws Exception {
    oauthUnlinkBeforeGuard("LINK");
  }

  @Test
  void oauthLoginCannotFollowReassignedOwnerOrReplacementBinding() throws Exception {
    for (boolean sameOwner : List.of(false, true)) {
      var owner = actor(user(address()));
      var replacement = sameOwner ? owner : actor(user(address()));
      var proof =
          new ProviderProof(
              "GOOGLE", "https://accounts.google.com", "reassigned-" + UUID.randomUUID(), null);
      callback(proof, "LINK", owner);
      var binding = auth.identities(owner).getFirst().id();
      long sessions =
          count(
              "SELECT count(*) FROM identity.auth_session WHERE user_id IN (?,?)",
              owner.userId(),
              replacement.userId());
      var result =
          afterCompetingCommit(
              owner.userId(),
              () -> callback(proof, "LOGIN", null),
              () -> {
                auth.unlink(owner, binding);
                assertThat(callback(proof, "LINK", replacement).marker()).isEqualTo("linked");
              });
      assertThat(result.marker()).isEqualTo("identity_conflict");
      assertThat(result.refresh()).isNull();
      var current = identities.external(proof.provider(), proof.issuer(), proof.subject());
      assertThat(current.userId()).isEqualTo(replacement.userId());
      assertThat(current.id()).isNotEqualTo(binding);
      assertThat(
              count(
                  "SELECT count(*) FROM identity.auth_session WHERE user_id IN (?,?)",
                  owner.userId(),
                  replacement.userId()))
          .isEqualTo(sessions);
      // A new flow observes the committed owner and can log in normally.
      assertThat(callback(proof, "LOGIN", null).marker()).isEqualTo("signed_in");
    }
  }

  private void oauthUnlinkBeforeGuard(String intent) throws Exception {
    var owner = actor(user(address()));
    var proof =
        new ProviderProof(
            "GOOGLE", "https://accounts.google.com", "unlink-race-" + UUID.randomUUID(), null);
    assertThat(callback(proof, "LINK", owner).marker()).isEqualTo("linked");
    var binding = auth.identities(owner).getFirst().id();
    var start = oauth.start("GOOGLE", intent, owner, null);
    var code = UUID.randomUUID().toString();
    provider.codes.put(code, proof);
    long sessions =
        count("SELECT count(*) FROM identity.auth_session WHERE user_id=?", owner.userId());
    long authenticated =
        count(
            "SELECT count(*) FROM platform.audit_entry WHERE actor_id=? AND action IN ('LOGIN_SUCCEEDED','REAUTHENTICATED')",
            owner.userId());
    var result =
        afterCompetingCommit(
            owner.userId(),
            () -> oauth.callback("GOOGLE", state(start), start.browser(), code, null),
            () -> auth.unlink(owner, binding));
    assertThat(result.marker()).isEqualTo("identity_conflict");
    assertThat(result.refresh()).isNull();
    assertThat(auth.identities(owner)).isEmpty();
    assertThat(count("SELECT count(*) FROM identity.auth_session WHERE user_id=?", owner.userId()))
        .isEqualTo(sessions);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE actor_id=? AND action IN ('LOGIN_SUCCEEDED','REAUTHENTICATED')",
                owner.userId()))
        .isEqualTo(authenticated);
    assertThat(identities.session(owner.sessionId(), false).reauthenticatedAt()).isNull();
    if (intent.equals("LINK"))
      assertThat(
              count(
                  "SELECT count(*) FROM platform.audit_entry WHERE actor_id=? AND action='IDENTITY_LINK_REJECTED' AND reason_code='BINDING_CHANGED'",
                  owner.userId()))
          .isEqualTo(1);
  }

  @Test
  void registrationHasNoPreinstalledCredentialAndRequiresBrowserAndMailboxProof() throws Exception {
    var email = address();
    String browser = secrets.randomToken();
    auth.register(email, browser);
    String proof = mailSecret(email, "VERIFY_EMAIL");
    assertThat(count("SELECT count(*) FROM identity.login_email WHERE email_normalized=?", email))
        .isZero();
    assertThatThrownBy(() -> auth.complete(proof, "attacker-browser", PASSWORD))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.complete(proof, browser, null)).isInstanceOf(Failure.class);
    var grant = auth.complete(proof, browser, PASSWORD);
    assertThat(count("SELECT count(*) FROM profile.profile WHERE user_id=?", actor(grant).userId()))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM profile.preference WHERE user_id=?", actor(grant).userId()))
        .isEqualTo(1);
    assertThatThrownBy(() -> auth.complete(proof, browser, PASSWORD)).isInstanceOf(Failure.class);
    mvc.perform(
            postJson(
                "/api/v1/auth/registrations",
                Map.of("email", address(), "password", PASSWORD, "platformRole", "ADMIN")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void passwordResetDuringVerificationCannotIssueObsoleteSession() throws Exception {
    String email = address();
    var original = user(email);
    auth.requestReset(email);
    String proof = mailSecret(email, "RESET_PASSWORD");
    blockingPasswords.entered = new CountDownLatch(1);
    blockingPasswords.resume = new CountDownLatch(1);
    try (var executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "obsolete-password-login"))) {
      var pending =
          executor.submit(
              () -> {
                try {
                  return (Object) auth.login(email, PASSWORD);
                } catch (Failure failure) {
                  return failure;
                }
              });
      assertThat(blockingPasswords.entered.await(10, TimeUnit.SECONDS)).isTrue();
      auth.reset(proof, "A new password selected during verification");
      blockingPasswords.resume.countDown();
      assertThat(pending.get(15, TimeUnit.SECONDS)).isInstanceOf(Failure.class);
      assertThat(
              count(
                  "SELECT count(*) FROM identity.auth_session WHERE user_id=? AND revoked_at IS NULL",
                  actor(original).userId()))
          .isZero();
    } finally {
      blockingPasswords.resume.countDown();
      blockingPasswords.entered = null;
      blockingPasswords.resume = null;
    }
  }

  @Test
  void providerClaimAcrossTwoRegistrationsIsAtomicAndUnverifiedIntentNeverLinks() throws Exception {
    var proof =
        new ProviderProof(
            "GOOGLE", "https://accounts.google.com", "racing-" + UUID.randomUUID(), null);
    String firstEmail = address(), secondEmail = address();
    String browser1 = secrets.randomToken(), browser2 = secrets.randomToken();
    var start1 = oauth.start("GOOGLE", "LOGIN", null, null);
    var start2 = oauth.start("GOOGLE", "LOGIN", null, null);
    String code = UUID.randomUUID().toString();
    provider.codes.put(code, proof);
    var first = oauth.callback("GOOGLE", state(start1), start1.browser(), code, null);
    var second = oauth.callback("GOOGLE", state(start2), start2.browser(), code, null);
    oauth.register(firstEmail, first.onboarding(), start1.browser(), browser1);
    oauth.register(secondEmail, second.onboarding(), start2.browser(), browser2);
    String secret1 = mailSecret(firstEmail, "VERIFY_EMAIL"),
        secret2 = mailSecret(secondEmail, "VERIFY_EMAIL");
    var result =
        race(
            () -> auth.complete(secret1, browser1, null),
            () -> auth.complete(secret2, browser2, null));
    assertThat(result.stream().filter(Authentication.Grant.class::isInstance).count()).isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM identity.auth_identity WHERE subject=?", proof.subject()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM identity.login_email WHERE email_normalized IN (?,?)",
                firstEmail,
                secondEmail))
        .isEqualTo(1);
  }

  @Test
  void apiRateLimitsHashEmailKeysAndBoundChunkedBodies() throws Exception {
    String email = address();
    for (int i = 0; i < 5; i++)
      mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)))
          .andExpect(status().isUnauthorized());
    mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)))
        .andExpect(status().isTooManyRequests());
    mvc.perform(
            postJson("/api/v1/auth/registrations", Map.of("email", address()))
                .content("x".repeat(17000))
                .with(
                    request -> {
                      request.removeHeader("Content-Length");
                      return request;
                    }))
        .andExpect(status().isPayloadTooLarge());
    String dispatchEmail = address();
    for (int i = 0; i < 4; i++) auth.register(dispatchEmail, secrets.randomToken());
    assertThat(
            count(
                "SELECT count(*) FROM identity.registration_intent WHERE email_normalized=?",
                dispatchEmail))
        .isEqualTo(3);
  }

  @Test
  void mailRetryExhaustionAndTwoWorkersUseLeaseFencing() throws Exception {
    var tx = new TransactionTemplate(manager);
    String dedup = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        status ->
            work.mail(
                dedup, address(), "TEST_MAIL", "fixture-proof", Instant.now().plusSeconds(1800)));
    var id =
        jdbc.queryForObject("SELECT id FROM platform.job WHERE dedup_key=?", UUID.class, dedup);
    jdbc.update(
        "UPDATE platform.job SET attempts=9,available_at=now()-interval '2 days' WHERE id=?", id);
    var finalAttempt = jobs.claim(UUID.randomUUID());
    assertThat(finalAttempt.id()).isEqualTo(id);
    assertThat(jobs.finish(finalAttempt, "SMTP_UNAVAILABLE")).isTrue();
    assertThat(count("SELECT count(*) FROM platform.job WHERE id=? AND status='DEAD'", id))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM platform.secret_mail_payload WHERE job_id=?", id))
        .isZero();
    var claims = race(() -> jobs.claim(UUID.randomUUID()), () -> jobs.claim(UUID.randomUUID()));
    var actual =
        claims.stream()
            .filter(DurableWorkStore.Claim.class::isInstance)
            .map(DurableWorkStore.Claim.class::cast)
            .toList();
    assertThat(actual).hasSize(2);
    assertThat(actual.get(0).id()).isNotEqualTo(actual.get(1).id());
    for (var item : actual) {
      assertThat(jobs.finish(item, null)).isTrue();
      assertThat(
              count("SELECT count(*) FROM platform.secret_mail_payload WHERE job_id=?", item.id()))
          .isZero();
    }
    String abandoned = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        status ->
            work.mail(
                abandoned,
                address(),
                "TEST_MAIL",
                "fixture-proof",
                Instant.now().plusSeconds(1800)));
    var abandonedId =
        jdbc.queryForObject("SELECT id FROM platform.job WHERE dedup_key=?", UUID.class, abandoned);
    jdbc.update(
        "UPDATE platform.job SET status='RUNNING',attempts=10,lease_owner=?,lease_generation=1,lease_until=now()-interval '1 second' WHERE id=?",
        UUID.randomUUID(),
        abandonedId);
    jobs.prune();
    assertThat(count("SELECT count(*) FROM platform.job WHERE id=? AND status='DEAD'", abandonedId))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM platform.secret_mail_payload WHERE job_id=?", abandonedId))
        .isZero();
  }

  @Test
  void mandatoryAuditFailureRollsBackPasswordSessionsAndMail() {
    String email = address();
    var grant = user(email);
    var second = auth.login(email, PASSWORD);
    long jobsBefore = count("SELECT count(*) FROM platform.job");
    jdbc.execute(
        "CREATE FUNCTION platform.test_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='PASSWORD_CHANGED' THEN RAISE EXCEPTION 'test audit unavailable'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_audit_fault BEFORE INSERT ON platform.audit_entry FOR EACH ROW EXECUTE FUNCTION platform.test_audit_fault()");
    try {
      assertThatThrownBy(
              () -> auth.password(actor(grant), "A changed password that must roll back"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(count("SELECT count(*) FROM platform.job")).isEqualTo(jobsBefore);
      auth.requireCurrent(actor(second));
      assertThat(auth.login(email, PASSWORD)).isNotNull();
    } finally {
      jdbc.execute("DROP TRIGGER test_audit_fault ON platform.audit_entry");
      jdbc.execute("DROP FUNCTION platform.test_audit_fault()");
    }
  }

  @Test
  void authenticationQueriesUseIndexesWithManySessions() {
    var grant = user(address());
    var user = actor(grant).userId();
    jdbc.update(
        "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at) SELECT md5(? || n::text)::uuid,?,now(),now(),now(),now()+interval '7 days',now()+interval '30 days',now() FROM generate_series(1,10000) n",
        user.toString(),
        user);
    jdbc.execute("ANALYZE identity.auth_session");
    var plan =
        jdbc.queryForList(
            "EXPLAIN (ANALYZE,BUFFERS,FORMAT TEXT) SELECT * FROM identity.auth_session WHERE id=?",
            String.class,
            grant.sessionId());
    assertThat(String.join("\n", plan)).contains("Index Scan", "auth_session_pkey");
    jdbc.update(
        "DELETE FROM identity.auth_session WHERE user_id=? AND id<>?", user, grant.sessionId());
  }

  @Test
  void embeddedTomcatStartsAndValidatesRealHttpBearerRequests() throws Exception {
    var grant = user(address());
    var base = "http://localhost:" + environment.getRequiredProperty("local.server.port");
    var client =
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var protectedRequest =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/me/profile"))
            .header("Authorization", "Bearer " + grant.accessToken())
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    var response =
        client.send(protectedRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).get("userId").asText())
        .isEqualTo(actor(grant).userId().toString());
    auth.logout(grant.refreshSecret());
    assertThat(
            client
                .send(protectedRequest, java.net.http.HttpResponse.BodyHandlers.discarding())
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void strictRefreshRaceAndLostResponseRevokeCommittedFamily() throws Exception {
    var grant = user(address());
    var result =
        race(() -> auth.refresh(grant.refreshSecret()), () -> auth.refresh(grant.refreshSecret()));
    assertThat(result.stream().filter(Authentication.Grant.class::isInstance).count()).isEqualTo(1);
    assertThat(result.stream().filter(Failure.class::isInstance).count()).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM identity.auth_session WHERE id=? AND revoke_reason='REFRESH_REPLAY'",
                grant.sessionId()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='REFRESH_REPLAY'",
                grant.sessionId()))
        .isEqualTo(1);
    var successor =
        (Authentication.Grant)
            result.stream()
                .filter(Authentication.Grant.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThatThrownBy(() -> auth.refresh(successor.refreshSecret())).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.requireCurrent(actor(successor))).isInstanceOf(Failure.class);
  }

  @Test
  void duplicateEmailCompletionHasOneAtomicAccountAndProfile() throws Exception {
    String email = address(), browser = secrets.randomToken();
    auth.register(email, browser);
    String proof = mailSecret(email, "VERIFY_EMAIL");
    var result =
        race(
            () -> auth.complete(proof, browser, PASSWORD),
            () -> auth.complete(proof, browser, PASSWORD));
    assertThat(result.stream().filter(Authentication.Grant.class::isInstance).count()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM identity.login_email WHERE email_normalized=?", email))
        .isEqualTo(1);
    String b1 = secrets.randomToken(), b2 = secrets.randomToken(), another = address();
    auth.register(another, b1);
    String p1 = mailSecret(another, "VERIFY_EMAIL");
    auth.register(another, b2);
    String p2 = mailSecret(another, "VERIFY_EMAIL");
    var other = race(() -> auth.complete(p1, b1, PASSWORD), () -> auth.complete(p2, b2, PASSWORD));
    assertThat(other.stream().filter(Authentication.Grant.class::isInstance).count()).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM account.user_account a LEFT JOIN profile.profile p ON a.id=p.user_id WHERE p.user_id IS NULL"))
        .isZero();
  }

  @Test
  void twoBrowsersAreIndependentAndLogoutImmediatelyDeniesBearer() throws Exception {
    String email = address();
    var first = user(email);
    var second = auth.login(email, PASSWORD);
    var outsider = user(address());
    auth.logout(first.refreshSecret());
    mvc.perform(get("/api/v1/me/account").header("Authorization", "Bearer " + first.accessToken()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer " + first.accessToken()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/users/" + actor(second).userId() + "/profile")
                .header("Authorization", "Bearer " + first.accessToken()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me/account").header("Authorization", "Bearer " + second.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
    assertThatThrownBy(() -> auth.revokeSession(actor(outsider), second.sessionId()))
        .isInstanceOf(Failure.class);
    auth.logoutAll(actor(second));
    assertThatThrownBy(() -> auth.requireCurrent(actor(second))).isInstanceOf(Failure.class);
  }

  @Test
  void resetIsSingleUseRevokesAllAndNeverAddsOAuthOnlyPassword() {
    var email = address();
    var grant = user(email);
    var second = auth.login(email, PASSWORD);
    auth.requestReset(email);
    var proof = mailSecret(email, "RESET_PASSWORD");
    auth.reset(proof, "A different mailbox owner password");
    assertThatThrownBy(() -> auth.requireCurrent(actor(grant))).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.requireCurrent(actor(second))).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.reset(proof, PASSWORD)).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.login(email, PASSWORD)).isInstanceOf(Failure.class);
    assertThat(auth.login(email, "A different mailbox owner password")).isNotNull();
  }

  @Test
  void emailChangeAndPasswordChangeUseFreshAuthAndRevokeOtherSessions() {
    String email = address();
    var grant = user(email);
    var actor = actor(grant);
    var second = auth.login(email, PASSWORD);
    String next = address();
    auth.requestReset(email);
    String obsoleteReset = mailSecret(email, "RESET_PASSWORD");
    auth.requestEmail(actor, next);
    auth.completeEmail(actor, mailSecret(next, "CHANGE_EMAIL"));
    assertThatThrownBy(() -> auth.reset(obsoleteReset, PASSWORD)).isInstanceOf(Failure.class);
    auth.requireCurrent(actor);
    assertThatThrownBy(() -> auth.requireCurrent(actor(second))).isInstanceOf(Failure.class);
    assertThat(auth.account(actor).email()).isEqualTo(next);
    jdbc.update(
        "UPDATE identity.auth_session SET authenticated_at=now()-interval '6 minutes',reauthenticated_at=NULL WHERE id=?",
        actor.sessionId());
    assertThatThrownBy(() -> auth.password(actor, PASSWORD)).isInstanceOf(Failure.class);
    auth.reauthenticate(actor, PASSWORD);
    auth.password(actor, "Another sufficiently lengthy password");
    assertThat(auth.login(next, "Another sufficiently lengthy password")).isNotNull();
  }

  @Test
  void expiryDoesNotDependOnCleanupAndRefreshDoesNotMakeReauthFresh() {
    var grant = user(address());
    jdbc.update(
        "UPDATE identity.auth_session SET authenticated_at=now()-interval '6 minutes' WHERE id=?",
        grant.sessionId());
    var rotated = auth.refresh(grant.refreshSecret());
    assertThatThrownBy(() -> auth.password(actor(rotated), PASSWORD)).isInstanceOf(Failure.class);
    jdbc.update(
        "UPDATE identity.auth_session SET idle_expires_at=now() WHERE id=?", grant.sessionId());
    assertThatThrownBy(() -> auth.requireCurrent(actor(rotated))).isInstanceOf(Failure.class);
    String email = address(), browser = secrets.randomToken();
    auth.register(email, browser);
    String proof = mailSecret(email, "VERIFY_EMAIL");
    jdbc.update(
        "UPDATE identity.registration_intent SET expires_at=now() WHERE token_hash=?",
        secrets.hash(proof));
    assertThatThrownBy(() -> auth.complete(proof, browser, PASSWORD)).isInstanceOf(Failure.class);
  }

  @Test
  void reinstatementNeverRevivesOldAccessRefreshOrRecentProof() throws Exception {
    var admin = user(address());
    var email = address();
    var target = user(email);
    var a = actor(admin);
    var t = actor(target);
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", a.userId());
    var old = accountQueries.find(t.userId());
    var suspended = accounts.changeStatus(a, t.userId(), old.version(), true, "SECURITY_REVIEW");
    assertThat(suspended.before()).isEqualTo("ACTIVE");
    assertThat(suspended.after()).isEqualTo("SUSPENDED");
    assertThatThrownBy(() -> auth.login(email, PASSWORD)).isInstanceOf(Failure.class);
    var restored =
        accounts.changeStatus(a, t.userId(), suspended.version(), false, "REVIEW_COMPLETE");
    assertThat(restored.after()).isEqualTo("ACTIVE");
    assertThat(accountQueries.find(t.userId()).authenticationEpoch()).isEqualTo(1);
    assertThatThrownBy(() -> auth.requireCurrent(t)).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.refresh(target.refreshSecret())).isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.reauthenticate(t, PASSWORD)).isInstanceOf(Failure.class);
    mvc.perform(get("/api/v1/me/account").header("Authorization", "Bearer " + target.accessToken()))
        .andExpect(status().isUnauthorized());
    var fresh = auth.login(email, PASSWORD);
    auth.requireCurrent(actor(fresh));
    assertThat(identities.session(fresh.sessionId(), false).authenticationEpoch()).isEqualTo(1);
    assertThat(auth.refresh(fresh.refreshSecret())).isNotNull();
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action IN ('ACCOUNT_SUSPENDED','ACCOUNT_REINSTATED')",
                t.userId()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='AccountStatusChanged'",
                t.userId()))
        .isEqualTo(2);
  }

  @Test
  void versionedLifecycleDeniesSelfPeerAdminRevokedStaffAndInvalidTransitions() {
    var a = actor(user(address()));
    var peer = actor(user(address()));
    var target = actor(user(address()));
    jdbc.update(
        "UPDATE account.user_account SET platform_role='ADMIN' WHERE id IN (?,?)",
        a.userId(),
        peer.userId());
    assertThatThrownBy(() -> accounts.changeStatus(a, a.userId(), 0, true, "SECURITY_REVIEW"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(403));
    assertThatThrownBy(() -> accounts.changeStatus(a, peer.userId(), 0, true, "SECURITY_REVIEW"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(403));
    assertThatThrownBy(() -> accounts.suspend(a, peer.userId(), "SECURITY_REVIEW"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(403));
    assertThatThrownBy(() -> accounts.changeStatus(a, target.userId(), 1, true, "SECURITY_REVIEW"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(412));
    assertThatThrownBy(() -> accounts.changeStatus(a, target.userId(), 0, false, "REVIEW_COMPLETE"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(409));
    assertThatThrownBy(
            () -> accounts.changeStatus(a, target.userId(), 0, true, "private free text"))
        .isInstanceOf(Failure.class);
    jdbc.update("UPDATE account.user_account SET platform_role='USER' WHERE id=?", a.userId());
    assertThatThrownBy(() -> accounts.changeStatus(a, target.userId(), 0, true, "SECURITY_REVIEW"))
        .isInstanceOfSatisfying(Failure.class, f -> assertThat(f.status()).isEqualTo(403));
    assertThat(accountQueries.find(target.userId()).authenticationEpoch()).isZero();
  }

  @Test
  void lifecycleAuditAndOutboxFailuresRollBackStatusAndEpoch() {
    var a = actor(user(address()));
    var target = actor(user(address()));
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", a.userId());
    for (String table : List.of("audit_entry", "outbox_event")) {
      String constraint = "lifecycle_failure_" + UUID.randomUUID().toString().replace("-", "");
      String column = table.equals("audit_entry") ? "target_id" : "aggregate_id";
      jdbc.execute(
          "ALTER TABLE platform."
              + table
              + " ADD CONSTRAINT "
              + constraint
              + " CHECK ("
              + column
              + " IS DISTINCT FROM '"
              + target.userId()
              + "'::uuid) NOT VALID");
      try {
        assertThatThrownBy(
                () -> accounts.changeStatus(a, target.userId(), 0, true, "SECURITY_REVIEW"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var account = accountQueries.find(target.userId());
        assertThat(account.active()).isTrue();
        assertThat(account.version()).isZero();
        assertThat(account.authenticationEpoch()).isZero();
        auth.requireCurrent(target);
        assertThat(
                count(
                    "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='ACCOUNT_SUSPENDED'",
                    target.userId()))
            .isZero();
        assertThat(
                count(
                    "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id=? AND event_type='AccountStatusChanged'",
                    target.userId()))
            .isZero();
      } finally {
        jdbc.execute("ALTER TABLE platform." + table + " DROP CONSTRAINT " + constraint);
      }
    }
  }

  @Test
  void suspensionWinsAgainstPasswordVerifiedBeforeLifecycleGuard() throws Exception {
    var a = actor(user(address()));
    String email = address();
    var target = actor(user(email));
    jdbc.update("UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", a.userId());
    long sessions =
        count("SELECT count(*) FROM identity.auth_session WHERE user_id=?", target.userId());
    blockingPasswords.entered = new CountDownLatch(1);
    blockingPasswords.resume = new CountDownLatch(1);
    try (var executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "obsolete-password-suspension"))) {
      var login =
          executor.submit(
              () -> {
                try {
                  return (Object) auth.login(email, PASSWORD);
                } catch (Failure failure) {
                  return failure;
                }
              });
      try {
        assertThat(blockingPasswords.entered.await(10, TimeUnit.SECONDS)).isTrue();
        accounts.changeStatus(a, target.userId(), 0, true, "SECURITY_REVIEW");
        blockingPasswords.resume.countDown();
        assertThat(login.get(10, TimeUnit.SECONDS)).isInstanceOf(Failure.class);
        assertThat(
                count(
                    "SELECT count(*) FROM identity.auth_session WHERE user_id=?", target.userId()))
            .isEqualTo(sessions);
      } finally {
        // Release the deliberately blocked login even when the command/assertion fails,
        // before ExecutorService.close waits for that thread.
        blockingPasswords.resume.countDown();
      }
    } finally {
      blockingPasswords.resume.countDown();
      blockingPasswords.entered = null;
      blockingPasswords.resume = null;
    }
  }

  @Test
  void suspensionAndDeletionDenyUnexpiredJwtWithoutCache() throws Exception {
    var admin = user(address());
    var target = user(address());
    var successor = auth.refresh(target.refreshSecret());
    jdbc.update(
        "UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", actor(admin).userId());
    accounts.suspend(actor(admin), actor(target).userId(), "SECURITY_REVIEW");
    assertThatThrownBy(() -> auth.refresh(target.refreshSecret())).isInstanceOf(Failure.class);
    assertThat(
            count(
                "SELECT count(*) FROM identity.auth_session WHERE id=? AND revoke_reason='REFRESH_REPLAY'",
                target.sessionId()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE target_id=? AND action='REFRESH_REPLAY'",
                target.sessionId()))
        .isEqualTo(1);
    assertThatThrownBy(() -> auth.refresh(successor.refreshSecret())).isInstanceOf(Failure.class);
    mvc.perform(get("/api/v1/me/account").header("Authorization", "Bearer " + target.accessToken()))
        .andExpect(status().isUnauthorized());
    var deletion = user(address());
    auth.close(actor(deletion), 0);
    assertThatThrownBy(() -> auth.requireCurrent(actor(deletion))).isInstanceOf(Failure.class);
    assertThat(
            count(
                "SELECT count(*) FROM account.user_account WHERE id=? AND status='DELETION_PENDING'",
                actor(deletion).userId()))
        .isEqualTo(1);
  }

  @Test
  void realCsrfCookieOriginCorsAndSecretFreeResponse() throws Exception {
    var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
    var csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
    String csrfToken =
        json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    assertThat(csrfCookie.isHttpOnly()).isFalse();
    assertThat(csrfCookie.getSecure()).isTrue();
    var email = address();
    var body = json.writeValueAsString(Map.of("email", email));
    mvc.perform(
            post("/api/v1/auth/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Origin", "https://social-core.invalid"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/registrations")
                .cookie(csrfCookie)
                .param("_csrf", csrfToken)
                .header("Origin", "https://social-core.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/registrations")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfToken)
                .header("Origin", "https://attacker.example")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    var created =
        mvc.perform(
                post("/api/v1/auth/registrations")
                    .cookie(csrfCookie)
                    .header("X-XSRF-TOKEN", csrfToken)
                    .header("Origin", "https://social-core.invalid")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isAccepted())
            .andReturn();
    var binding = created.getResponse().getCookie("registration_binding");
    var complete =
        mvc.perform(
                postJson(
                        "/api/v1/auth/registrations/complete",
                        Map.of("secret", mailSecret(email, "VERIFY_EMAIL"), "password", PASSWORD))
                    .cookie(binding))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn();
    var refresh = complete.getResponse().getCookie("refresh");
    assertThat(refresh.getSecure()).isTrue();
    assertThat(refresh.isHttpOnly()).isTrue();
    assertThat(refresh.getDomain()).isNull();
    assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
    assertThat(refresh.getAttribute("SameSite")).isEqualTo("Strict");
    mvc.perform(
            get("/api/v1/users/" + UUID.randomUUID() + "/profile")
                .header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            options("/api/v1/auth/login")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void profilePrivacyVersionAndPreferencesAreEnforced() throws Exception {
    var grant = user(address());
    var actor = actor(grant);
    var other = user(address());
    mvc.perform(get("/api/v1/users/" + actor.userId() + "/profile"))
        .andExpect(status().isNotFound());
    var own = profiles.visible(actor, actor.userId());
    profiles.change(actor, own.version(), own.handle(), "Fixture Name", "", "PRIVATE");
    assertThatThrownBy(() -> profiles.visible(actor(other), actor.userId()))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(
            () -> profiles.change(actor, own.version(), own.handle(), "Other", "", "PUBLIC"))
        .isInstanceOf(Failure.class);
    var privateProfile = profiles.visible(actor, actor.userId());
    profiles.change(actor, privateProfile.version(), own.handle(), "Fixture Name", "", "PUBLIC");
    mvc.perform(get("/api/v1/users/" + actor.userId() + "/profile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").doesNotExist());
    assertThat(profiles.change(actor, 2, own.handle(), "Fixture Name", "", "FRIENDS").visibility())
        .isEqualTo("FRIENDS");
    mvc.perform(
            patch("/api/v1/me/profile")
                .with(csrf().asHeader())
                .header("Origin", "https://social-core.invalid")
                .header("Authorization", "Bearer " + grant.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isPreconditionRequired());
    var pref = profiles.preferences(actor);
    assertThatThrownBy(() -> profiles.change(actor, 3, own.handle(), "Fixture Name", "", null))
        .isInstanceOf(Failure.class)
        .extracting("status")
        .isEqualTo(400);
    assertThatThrownBy(() -> profiles.preferences(actor, pref.version(), "en", "UTC", null))
        .isInstanceOf(Failure.class)
        .extracting("status")
        .isEqualTo(400);
    assertThatThrownBy(
            () -> profiles.preferences(actor, pref.version(), "en", "Not/AZone", "FRIENDS"))
        .isInstanceOf(Failure.class);
  }

  @Test
  void providerMatchingEmailNeverAutoLinksAndChangedEmailSelectsSubject() {
    String email = address();
    var local = user(email);
    var result =
        callback(
            new ProviderProof(
                "GOOGLE", "https://accounts.google.com", "new-" + UUID.randomUUID(), email),
            "LOGIN",
            null);
    assertThat(result.marker()).isEqualTo("sign_in_to_connect");
    assertThat(result.refresh()).isNull();
    assertThat(auth.identities(actor(local))).isEmpty();
    String subject = "linked-" + UUID.randomUUID();
    assertThat(
            callback(
                    new ProviderProof("GOOGLE", "https://accounts.google.com", subject, email),
                    "LINK",
                    actor(local))
                .marker())
        .isEqualTo("linked");
    var changed =
        callback(
            new ProviderProof("GOOGLE", "https://accounts.google.com", subject, address()),
            "LOGIN",
            null);
    assertThat(changed.marker()).isEqualTo("signed_in");
    assertThat(actor(auth.refresh(changed.refresh())).userId()).isEqualTo(actor(local).userId());
  }

  @Test
  void oauthMissingEmailOnboardingRequiresOwnEmailAndResetCannotAddPassword() {
    var start = oauth.start("FACEBOOK", "LOGIN", null, null);
    String code = UUID.randomUUID().toString();
    provider.codes.put(
        code,
        new ProviderProof(
            "FACEBOOK",
            "https://www.facebook.com/fixture-app",
            "missing-" + UUID.randomUUID(),
            null));
    var result = oauth.callback("FACEBOOK", state(start), start.browser(), code, null);
    assertThat(result.marker()).isEqualTo("verify_recovery_email");
    String email = address(), browser = secrets.randomToken();
    oauth.register(email, result.onboarding(), start.browser(), browser);
    var grant = auth.complete(mailSecret(email, "VERIFY_EMAIL"), browser, null);
    assertThat(auth.account(actor(grant)).hasPassword()).isFalse();
    assertThatThrownBy(() -> oauth.start("GOOGLE", "LINK", actor(grant), null))
        .isInstanceOf(Failure.class)
        .extracting("code")
        .isEqualTo("REAUTHENTICATION_REQUIRED");
    var rotated = auth.refresh(grant.refreshSecret());
    assertThatThrownBy(() -> auth.requestEmail(actor(rotated), address()))
        .isInstanceOf(Failure.class);
    auth.requestReset(email);
    assertThatThrownBy(() -> mailSecret(email, "RESET_PASSWORD"))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(
            () -> auth.unlink(actor(grant), auth.identities(actor(grant)).getFirst().id()))
        .isInstanceOf(Failure.class);
    assertThatThrownBy(() -> auth.password(actor(grant), PASSWORD)).isInstanceOf(Failure.class);
    var proof = provider.codes.get(code);
    assertThat(callback(proof, "REAUTH", actor(grant)).marker()).isEqualTo("reauthenticated");
    assertThat(oauth.start("GOOGLE", "LINK", actor(grant), null)).isNotNull();
    auth.password(actor(grant), PASSWORD);
    assertThat(auth.login(email, PASSWORD)).isNotNull();
  }

  @Test
  void callbackReplayBrowserMismatchExpiryAndServerIntentAreEnforced() throws Exception {
    var start = oauth.start("GOOGLE", "LOGIN", null, null);
    String code = UUID.randomUUID().toString();
    provider.codes.put(
        code,
        new ProviderProof(
            "GOOGLE", "https://accounts.google.com", "new-" + UUID.randomUUID(), null));
    int before = provider.exchanges.get();
    assertThatThrownBy(() -> oauth.callback("GOOGLE", state(start), "wrong", code, null))
        .isInstanceOf(Failure.class);
    assertThat(provider.exchanges.get()).isEqualTo(before);
    var result =
        race(
            () -> oauth.callback("GOOGLE", state(start), start.browser(), code, null),
            () -> oauth.callback("GOOGLE", state(start), start.browser(), code, null));
    assertThat(result.stream().filter(OAuthAuthentication.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(provider.exchanges.get()).isEqualTo(before + 1);
    var expired = oauth.start("GOOGLE", "LOGIN", null, null);
    jdbc.update(
        "UPDATE identity.oauth_transaction SET expires_at=now() WHERE state_hash=?",
        secrets.hash(state(expired)));
    assertThatThrownBy(
            () -> oauth.callback("GOOGLE", state(expired), expired.browser(), code, null))
        .isInstanceOf(Failure.class);
  }

  @Test
  void foreignIdentityLinkIsAuditedAndTwoUnlinksCannotRemoveAllMethods() throws Exception {
    var alice = user(address());
    var bob = user(address());
    String subject = "owned-" + UUID.randomUUID();
    var google = new ProviderProof("GOOGLE", "https://accounts.google.com", subject, null);
    callback(google, "LINK", actor(alice));
    assertThat(callback(google, "LINK", actor(bob)).marker()).isEqualTo("identity_conflict");
    assertThat(auth.identities(actor(bob))).isEmpty();
    callback(
        new ProviderProof(
            "FACEBOOK",
            "https://www.facebook.com/fixture-app",
            "second-" + UUID.randomUUID(),
            null),
        "LINK",
        actor(alice));
    jdbc.update("DELETE FROM identity.local_credential WHERE user_id=?", actor(alice).userId());
    var all = auth.identities(actor(alice));
    var result =
        race(
            () -> {
              auth.unlink(actor(alice), all.get(0).id());
              return true;
            },
            () -> {
              auth.unlink(actor(alice), all.get(1).id());
              return true;
            });
    assertThat(result.stream().filter(Failure.class::isInstance).count()).isEqualTo(1);
    assertThat(auth.identities(actor(alice))).hasSize(1);
  }

  @Test
  void simultaneousCrossAccountLinkClaimsKeepOneOwnerAndAuditTheRejectedActor() throws Exception {
    var first = user(address());
    var second = user(address());
    var proof =
        new ProviderProof(
            "GOOGLE", "https://accounts.google.com", "shared-" + UUID.randomUUID(), address());
    var results =
        race(
            () -> callback(proof, "LINK", actor(first)),
            () -> callback(proof, "LINK", actor(second)));
    assertThat(results).allMatch(OAuthAuthentication.Result.class::isInstance);
    assertThat(
            results.stream()
                .map(OAuthAuthentication.Result.class::cast)
                .map(OAuthAuthentication.Result::marker))
        .containsExactlyInAnyOrder("linked", "identity_conflict");
    assertThat(
            count(
                "SELECT count(*) FROM identity.auth_identity WHERE provider='GOOGLE' AND subject=?",
                proof.subject()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_entry WHERE action='IDENTITY_LINK_REJECTED' AND actor_id IN (?,?)",
                actor(first).userId(),
                actor(second).userId()))
        .isEqualTo(1);
  }

  @Test
  void durableMailOutboxRollbackLeaseRecoveryAndReceiptDedup() throws Exception {
    var tx = new TransactionTemplate(manager);
    long before = count("SELECT count(*) FROM platform.outbox_event");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      work.event("ContractFact", "contract", UUID.randomUUID(), 0, 0, "{}");
                      work.mail(
                          UUID.randomUUID().toString(),
                          address(),
                          "TEST",
                          "test-secret",
                          Instant.now().plusSeconds(1800));
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("SELECT count(*) FROM platform.outbox_event")).isEqualTo(before);
    var aggregate = UUID.randomUUID();
    tx.executeWithoutResult(
        status -> work.event("ContractFact", "contract", aggregate, 0, 0, "{}"));
    var event =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE aggregate_id=?", UUID.class, aggregate);
    // Isolate the contract delivery from other registered consumers' fixture
    // backlog; keep the real claim/receipt/effect path and exact dedup assertions.
    jdbc.update(
        "UPDATE platform.outbox_delivery SET available_at=now()-interval '1 day' WHERE event_id=?",
        event);
    jobs.consumeOne(UUID.randomUUID());
    assertThat(count("SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?", event))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE platform.outbox_delivery SET status='RETRY',available_at=now()-interval '1 day' WHERE event_id=?",
        event);
    jobs.consumeOne(UUID.randomUUID());
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE kind='CONTRACT' AND dedup_key=?",
                event.toString()))
        .isEqualTo(1);
    var stale = jobs.claim(UUID.randomUUID());
    assertThat(stale).isNotNull();
    jdbc.update(
        "UPDATE platform.job SET lease_until=now()-interval '1 second',available_at=now()-interval '1 day' WHERE id=?",
        stale.id());
    var fresh = jobs.claim(UUID.randomUUID());
    assertThat(fresh.id()).isEqualTo(stale.id());
    assertThat(fresh.generation()).isEqualTo(stale.generation() + 1);
    assertThat(jobs.finish(stale, null)).isFalse();
    assertThat(jobs.finish(fresh, "SMTP_UNAVAILABLE")).isTrue();
    assertThat(
            count("SELECT count(*) FROM platform.secret_mail_payload WHERE job_id=?", fresh.id()))
        .isEqualTo(1);
  }

  @Test
  void failedConsumerOrExpiredLeaseRollsBackEffectAndReceiptAndExhaustsBoundedRetries() {
    var tx = new TransactionTemplate(manager);
    for (String mode : List.of("forceFailure", "expireLease")) {
      var aggregate = UUID.randomUUID();
      tx.executeWithoutResult(
          status ->
              work.event("ContractFact", "contract", aggregate, 0, 0, "{\"" + mode + "\":true}"));
      var event =
          jdbc.queryForObject(
              "SELECT id FROM platform.outbox_event WHERE aggregate_id=?", UUID.class, aggregate);
      jdbc.update(
          "UPDATE platform.outbox_delivery SET available_at=now()-interval '1 day' WHERE event_id=?",
          event);
      jobs.consumeOne(UUID.randomUUID());
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM platform.outbox_delivery WHERE event_id=?",
                  String.class,
                  event))
          .isEqualTo("RETRY");
      assertThat(count("SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?", event))
          .isZero();
      assertThat(
              count(
                  "SELECT count(*) FROM platform.job WHERE kind='CONTRACT' AND dedup_key=?",
                  event.toString()))
          .isZero();
      jdbc.update(
          "UPDATE platform.outbox_delivery SET attempts=9,available_at=now()-interval '1 day' WHERE event_id=?",
          event);
      jobs.consumeOne(UUID.randomUUID());
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM platform.outbox_delivery WHERE event_id=?",
                  String.class,
                  event))
          .isEqualTo("DEAD");
      assertThat(count("SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?", event))
          .isZero();
    }
  }

  @Test
  void realSmtpCapturesVerificationAndResetAndPayloadIsDeletedAfterAck() throws Exception {
    String email = address(), browser = secrets.randomToken();
    auth.register(email, browser);
    var rows =
        jdbc.query(
            "SELECT p.job_id,p.ciphertext FROM platform.secret_mail_payload p JOIN platform.job j ON j.id=p.job_id WHERE j.payload->>'template'='VERIFY_EMAIL'",
            (rs, n) ->
                Map.entry(
                    rs.getObject(1, UUID.class),
                    json.readValue(
                        secrets.decrypt("mail:" + rs.getObject(1, UUID.class), rs.getBytes(2)),
                        DurableWorkStore.Mail.class)));
    var item =
        rows.stream().filter(e -> e.getValue().address().equals(email)).findFirst().orElseThrow();
    var payload = item.getValue();
    jdbc.update(
        "UPDATE platform.job SET available_at=now()-interval '3 days' WHERE id=?", item.getKey());
    var worker =
        new com.flyvo.socialcore.platform.infrastructure.jobs.DurableWorker(
            jobs,
            mail,
            Clock.systemUTC(),
            List.of(),
            List.of(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    worker.runOnce();
    assertThat(
            count(
                "SELECT count(*) FROM platform.secret_mail_payload WHERE job_id=?", item.getKey()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE id=? AND status='COMPLETED'",
                item.getKey()))
        .isEqualTo(1);
    String captured = capturedProof(email);
    assertThat(captured).isEqualTo(payload.secret());
    var grant = auth.complete(captured, browser, PASSWORD);
    assertThat(grant.accessToken()).isNotBlank();
    assertThat(
            count(
                "SELECT count(*) FROM platform.job WHERE payload::text LIKE ?",
                "%" + captured + "%"))
        .isZero();
    auth.requestReset(email);
    String reset = mailSecret(email, "RESET_PASSWORD");
    var resetJob =
        jdbc.queryForObject(
            "SELECT job_id FROM platform.secret_mail_payload WHERE job_id IN (SELECT id FROM platform.job WHERE dedup_key IN (SELECT id::text FROM identity.one_time_token WHERE token_hash=?))",
            UUID.class,
            secrets.hash(reset));
    jdbc.update(
        "UPDATE platform.job SET available_at=now()-interval '3 days' WHERE id=?", resetJob);
    worker.runOnce();
    String capturedReset = capturedProof(email);
    assertThat(capturedReset).isEqualTo(reset);
    auth.reset(capturedReset, "A freshly recovered mailbox password");
    assertThatThrownBy(() -> auth.requireCurrent(actor(grant))).isInstanceOf(Failure.class);
    assertThat(auth.login(email, "A freshly recovered mailbox password")).isNotNull();
    assertThat(count("SELECT count(*) FROM platform.secret_mail_payload WHERE job_id=?", resetJob))
        .isZero();
  }

  private String capturedProof(String email) {
    String list =
        org.springframework.web.client.RestClient.create()
            .get()
            .uri("http://" + MAIL.getHost() + ":" + MAIL.getMappedPort(8025) + "/api/v1/messages")
            .retrieve()
            .body(String.class);
    assertThat(list).contains(email);
    var root = json.readTree(list);
    String messageId = root.get("messages").get(0).get("ID").asText();
    String message =
        org.springframework.web.client.RestClient.create()
            .get()
            .uri(
                "http://"
                    + MAIL.getHost()
                    + ":"
                    + MAIL.getMappedPort(8025)
                    + "/api/v1/message/"
                    + messageId)
            .retrieve()
            .body(String.class);
    var proof = java.util.regex.Pattern.compile("#secret=([A-Za-z0-9_-]{43})").matcher(message);
    assertThat(proof.find()).isTrue();
    return proof.group(1);
  }
}
