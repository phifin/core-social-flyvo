package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.flyvo.socialcore.identity.application.OAuthAuthentication;
import com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof;
import com.flyvo.socialcore.platform.api.Secrets;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "app.auth.oauth-authorize-per-minute=2",
      "app.auth.oauth-callback-per-minute=3",
      "app.oauth.google.enabled=true",
      "app.oauth.google.client-id=synthetic-client",
      "app.oauth.google.client-secret=synthetic-not-a-secret",
      "app.oauth.google.redirect-uri=https://social-core.invalid/api/v1/auth/oauth/google/callback"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class OAuthAbuseIT {
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

  @TestConfiguration
  static class Fixtures {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    AuthenticationIT.FakeProvider fakeProvider() {
      return new AuthenticationIT.FakeProvider();
    }
  }

  static class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    public Instant instant() {
      return now.get();
    }

    void advance(Duration elapsed) {
      now.updateAndGet(t -> t.plus(elapsed));
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired OAuthAuthentication oauth;
  @Autowired AuthenticationIT.FakeProvider provider;
  @Autowired MutableClock clock;
  @Autowired Secrets secrets;

  private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
    return request.with(
        r -> {
          r.setRemoteAddr(ip);
          return r;
        });
  }

  private MockHttpServletRequestBuilder authorize(String ip) {
    return from(get("/api/v1/auth/oauth/google/authorize"), ip);
  }

  private long transactions() {
    return jdbc.queryForObject("SELECT count(*) FROM identity.oauth_transaction", Long.class);
  }

  @Test
  void authorizeGetIsBoundedBeforeInsertRefillsAndDoesNotTrustForwardedIp() throws Exception {
    String ip = "192.0.2.201";
    long before = transactions();
    mvc.perform(authorize(ip)).andExpect(status().isSeeOther());
    mvc.perform(authorize(ip)).andExpect(status().isSeeOther());
    mvc.perform(authorize(ip).header("X-Forwarded-For", "192.0.2.202"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    assertThat(transactions()).isEqualTo(before + 2);
    mvc.perform(authorize("192.0.2.202")).andExpect(status().isSeeOther());
    // Denials must retain a fractional refill, rather than consuming it.
    clock.advance(Duration.ofSeconds(15));
    mvc.perform(authorize(ip)).andExpect(status().isTooManyRequests());
    clock.advance(Duration.ofSeconds(15));
    mvc.perform(authorize(ip)).andExpect(status().isSeeOther());
    mvc.perform(authorize(ip)).andExpect(status().isTooManyRequests());
    assertThat(transactions()).isEqualTo(before + 4);
    mvc.perform(from(get("/actuator/health/liveness"), ip)).andExpect(status().isOk());
    mvc.perform(from(get("/actuator/health/readiness"), ip)).andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder callback(String ip, OAuthAuthentication.Start start) {
    var code = UUID.randomUUID().toString();
    provider.codes.put(
        code,
        new ProviderProof(
            "GOOGLE", "https://accounts.google.com", "budget-" + UUID.randomUUID(), null));
    return from(
        get("/api/v1/auth/oauth/google/callback")
            .param("state", start.location().substring(start.location().indexOf("state=") + 6))
            .param("code", code)
            .cookie(new Cookie("oauth_binding", start.browser())),
        ip);
  }

  @Test
  void callbackGetIsBoundedBeforeConsumptionAndExchangeWithIndependentIpAndRefill()
      throws Exception {
    String ip = "192.0.2.203";
    int before = provider.exchanges.get();
    // Persist legitimate states through the application boundary to isolate callback's budget
    // from authorize's smaller HTTP budget. No Origin/CSRF is sent on top-level callback GETs.
    for (int i = 0; i < 3; i++)
      mvc.perform(callback(ip, oauth.start("GOOGLE", "LOGIN", null, null)))
          .andExpect(status().isSeeOther());
    var waiting = oauth.start("GOOGLE", "LOGIN", null, null);
    var blocked = callback(ip, waiting);
    mvc.perform(blocked)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    assertThat(provider.exchanges.get()).isEqualTo(before + 3);
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM identity.oauth_transaction WHERE state_hash=?",
                Boolean.class,
                secrets.hash(
                    waiting.location().substring(waiting.location().indexOf("state=") + 6))))
        .isTrue();
    mvc.perform(from(blocked, "192.0.2.204")).andExpect(status().isSeeOther());
    assertThat(provider.exchanges.get()).isEqualTo(before + 4);
    clock.advance(Duration.ofSeconds(20));
    mvc.perform(callback(ip, oauth.start("GOOGLE", "LOGIN", null, null)))
        .andExpect(status().isSeeOther());
    mvc.perform(callback(ip, oauth.start("GOOGLE", "LOGIN", null, null)))
        .andExpect(status().isTooManyRequests());
    assertThat(provider.exchanges.get()).isEqualTo(before + 5);
    // Callback exhaustion does not spend the independent initiation bucket.
    mvc.perform(authorize(ip)).andExpect(status().isSeeOther());
  }

  @Test
  void encodedProviderPathCannotBypassInitiationBudget() throws Exception {
    String ip = "192.0.2.206";
    long before = transactions();
    mvc.perform(authorize(ip)).andExpect(status().isSeeOther());
    mvc.perform(authorize(ip)).andExpect(status().isSeeOther());
    mvc.perform(from(get(java.net.URI.create("/api/v1/auth/oauth/%67oogle/authorize")), ip))
        .andExpect(status().isTooManyRequests());
    assertThat(transactions()).isEqualTo(before + 2);
  }

  @Test
  void invalidCallbacksSpendTheSameBudgetWithoutProviderCalls() throws Exception {
    String ip = "192.0.2.205";
    int before = provider.exchanges.get();
    for (int i = 0; i < 3; i++)
      mvc.perform(from(get("/api/v1/auth/oauth/google/callback").param("state", "invalid"), ip))
          .andExpect(status().isUnauthorized());
    mvc.perform(callback(ip, oauth.start("GOOGLE", "LOGIN", null, null)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));
    assertThat(provider.exchanges.get()).isEqualTo(before);
  }
}
