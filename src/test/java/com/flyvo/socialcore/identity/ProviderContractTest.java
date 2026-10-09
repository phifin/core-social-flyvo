package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.identity.infrastructure.oauth.OAuthProviders;
import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.infrastructure.security.ProtectedSecrets;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class ProviderContractTest {
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

  @Test
  void googleChecksSignatureIssuerAudienceExpiryNonceAzpAndPkce() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    var wrong = generator.generateKeyPair();
    var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID("provider-key").build();
    var token = new AtomicReference<String>();
    var received = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var json = new ObjectMapper();
    server.createContext(
        "/token",
        exchange -> {
          received.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          respond(
              exchange,
              json.writeValueAsString(
                  Map.of(
                      "access_token",
                      "provider-access-fixture",
                      "token_type",
                      "Bearer",
                      "expires_in",
                      300,
                      "id_token",
                      token.get())));
        });
    server.createContext("/jwks", exchange -> respond(exchange, new JWKSet(key).toString()));
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var env = environment("google", base);
      var secrets = new ProtectedSecrets(env);
      var client = new OAuthProviders(env, Clock.fixed(NOW, ZoneOffset.UTC), secrets);
      var claims = claims();
      token.set(sign(claims, (RSAPrivateKey) pair.getPrivate()));
      String authorization =
          client.authorization("GOOGLE", "state-fixture", "nonce-fixture", "pkce-fixture");
      assertThat(authorization).contains("code_challenge_method=S256", "nonce=nonce-fixture");
      var proof =
          client.verify("GOOGLE", "code-fixture", secrets.hash("nonce-fixture"), "pkce-fixture");
      assertThat(proof.subject()).isEqualTo("subject-fixture");
      assertThat(received.get())
          .contains("code_verifier=pkce-fixture", "redirect_uri=", "client_secret=fixture-secret");
      for (String changed :
          List.of(
              "issuer",
              "audience",
              "expiry",
              "nonce",
              "azp",
              "signature",
              "missing_nonce",
              "multi_audience")) {
        var changedClaims = new JWTClaimsSet.Builder(claims);
        switch (changed) {
          case "issuer" -> changedClaims.issuer("https://attacker.example");
          case "audience" -> changedClaims.audience("other-app");
          case "expiry" -> changedClaims.expirationTime(Date.from(NOW.minusSeconds(31)));
          case "nonce" -> changedClaims.claim("nonce", "other");
          case "azp" -> changedClaims.claim("azp", "other-app");
          case "missing_nonce" -> changedClaims.claim("nonce", null);
          case "multi_audience" -> changedClaims.audience(List.of("fixture-client", "other-app"));
          default -> {}
        }
        token.set(
            sign(
                changedClaims.build(),
                (RSAPrivateKey)
                    (changed.equals("signature") ? wrong.getPrivate() : pair.getPrivate())));
        assertThatThrownBy(
                () ->
                    client.verify(
                        "GOOGLE", "code-fixture", secrets.hash("nonce-fixture"), "pkce-fixture"))
            .as(changed)
            .isInstanceOf(Failure.class);
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void facebookChecksAppUserBothExpiriesAndAllowsMissingEmail() throws Exception {
    var debug =
        new AtomicReference<Map<String, Object>>(
            Map.of(
                "is_valid",
                true,
                "app_id",
                "fixture-client",
                "user_id",
                "fb-fixture",
                "expires_at",
                NOW.plusSeconds(300).getEpochSecond(),
                "data_access_expires_at",
                NOW.plusSeconds(1000).getEpochSecond()));
    var user = new AtomicReference<Map<String, Object>>(Map.of("id", "fb-fixture"));
    var tokenCalls = new AtomicReference<String>();
    var json = new ObjectMapper();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenCalls.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          respond(
              exchange,
              "{\"access_token\":\"fb-token-fixture\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        });
    server.createContext(
        "/debug",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer fixture-client|fixture-secret");
          respond(exchange, json.writeValueAsString(Map.of("data", debug.get())));
        });
    server.createContext("/me", exchange -> respond(exchange, json.writeValueAsString(user.get())));
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var env = environment("facebook", base);
      var client =
          new OAuthProviders(env, Clock.fixed(NOW, ZoneOffset.UTC), new ProtectedSecrets(env));
      assertThat(client.verify("FACEBOOK", "code-fixture", null, null).email()).isNull();
      assertThat(tokenCalls.get()).doesNotContain("code_verifier");
      assertThat(client.authorization("FACEBOOK", "state", null, null))
          .doesNotContain("code_challenge");
      var valid = debug.get();
      for (String changed :
          List.of("is_valid", "app_id", "expires_at", "data_access_expires_at", "user_id")) {
        var bad = new HashMap<>(valid);
        bad.put(
            changed,
            switch (changed) {
              case "is_valid" -> false;
              case "app_id", "user_id" -> "other";
              default -> NOW.getEpochSecond();
            });
        debug.set(bad);
        assertThatThrownBy(() -> client.verify("FACEBOOK", "code-fixture", null, null))
            .as(changed)
            .isInstanceOf(Failure.class);
      }
      debug.set(valid);
      user.set(Map.of("id", "wrong"));
      assertThatThrownBy(() -> client.verify("FACEBOOK", "code-fixture", null, null))
          .isInstanceOf(Failure.class);
      env.setProperty("app.oauth.facebook.contract-approved", "false");
      assertThatThrownBy(
              () -> new OAuthProviders(env, Clock.systemUTC(), new ProtectedSecrets(env)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      server.stop(0);
    }
  }

  private MockEnvironment environment(String provider, String base) {
    var env = new MockEnvironment();
    env.setActiveProfiles("test");
    env.withProperty("app.security.ephemeral-keys", "true");
    String p = "app.oauth." + provider + ".";
    env.withProperty(p + "enabled", "true")
        .withProperty(p + "client-id", "fixture-client")
        .withProperty(p + "client-secret", "fixture-secret")
        .withProperty(p + "redirect-uri", base + "/api/v1/auth/oauth/" + provider + "/callback")
        .withProperty(p + "authorization-uri", base + "/authorize")
        .withProperty(p + "token-uri", base + "/token")
        .withProperty(p + "jwks-uri", base + "/jwks")
        .withProperty(p + "debug-uri", base + "/debug")
        .withProperty(p + "user-info-uri", base + "/me")
        .withProperty(p + "graph-version", "v-test")
        .withProperty(p + "contract-approved", "true");
    if (provider.equals("facebook")) env.setProperty(p + "graph-version", "v24.0");
    return env;
  }

  private JWTClaimsSet claims() {
    return new JWTClaimsSet.Builder()
        .issuer("https://accounts.google.com")
        .audience("fixture-client")
        .subject("subject-fixture")
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plusSeconds(300)))
        .claim("nonce", "nonce-fixture")
        .claim("email", "fixture@example.org")
        .build();
  }

  private String sign(JWTClaimsSet claims, RSAPrivateKey key) throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("provider-key").build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
