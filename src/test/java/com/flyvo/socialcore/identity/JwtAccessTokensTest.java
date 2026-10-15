package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.identity.infrastructure.security.JwtAccessTokens;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.Failure;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class JwtAccessTokensTest {
  @TempDir Path directory;

  @Test
  void validatesExactAlgorithmIssuerAudienceKidLifetimeAndSignatureWithRotation() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    var old = generator.generateKeyPair();
    var wrong = generator.generateKeyPair();
    Path privateFile = directory.resolve("private.pem"),
        publicFile = directory.resolve("public.pem"),
        oldFile = directory.resolve("old.pem");
    Files.writeString(
        privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    Files.writeString(
        publicFile, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    Files.writeString(oldFile, Base64.getEncoder().encodeToString(old.getPublic().getEncoded()));
    var now = Instant.parse("2026-10-09T00:00:00Z");
    var env =
        new MockEnvironment()
            .withProperty("app.auth.issuer", "https://issuer.example")
            .withProperty("app.auth.audience", "browser")
            .withProperty("app.auth.signing-key-id", "new")
            .withProperty("app.auth.private-key-file", privateFile.toString())
            .withProperty("app.auth.public-key-file", publicFile.toString())
            .withProperty("app.auth.retained-public-keys", "old=" + oldFile);
    var tokens = new JwtAccessTokens(env, Clock.fixed(now, ZoneOffset.UTC));
    var actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    var issued = tokens.issue(actor);
    assertThat(tokens.validate(issued)).isEqualTo(actor);
    assertThat(tokens.verify(issued).actor()).isEqualTo(actor);
    assertThat(tokens.verify(issued).expiresAt()).isEqualTo(now.plusSeconds(300));
    var claims = SignedJWT.parse(issued).getJWTClaimsSet();
    assertThat(claims.getClaims()).doesNotContainKeys("email", "roles", "memberships");
    assertThat(tokens.validate(sign(claims, old, "old", JWSAlgorithm.RS256))).isEqualTo(actor);
    var atExpiry =
        new JWTClaimsSet.Builder(claims)
            .issueTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now))
            .build();
    var skewToken = sign(atExpiry, pair, "new", JWSAlgorithm.RS256);
    assertThat(tokens.validate(skewToken)).isEqualTo(actor);
    assertThatThrownBy(() -> tokens.verify(skewToken)).isInstanceOf(Failure.class);
    for (String change :
        List.of(
            "issuer",
            "audience",
            "expired",
            "future",
            "lifetime",
            "sid",
            "subject",
            "kid",
            "algorithm",
            "signature")) {
      var builder = new JWTClaimsSet.Builder(claims);
      switch (change) {
        case "issuer" -> builder.issuer("https://attacker.example");
        case "audience" -> builder.audience("other");
        case "expired" -> {
          builder.issueTime(Date.from(now.minusSeconds(400)));
          builder.expirationTime(Date.from(now.minusSeconds(31)));
        }
        case "future" -> builder.issueTime(Date.from(now.plusSeconds(31)));
        case "lifetime" -> builder.expirationTime(Date.from(now.plusSeconds(301)));
        case "sid" -> builder.claim("sid", "invalid");
        case "subject" -> builder.subject("invalid");
        default -> {}
      }
      String invalid =
          sign(
              builder.build(),
              change.equals("signature") ? wrong : pair,
              change.equals("kid") ? "unknown" : "new",
              change.equals("algorithm") ? JWSAlgorithm.RS512 : JWSAlgorithm.RS256);
      assertThatThrownBy(() -> tokens.validate(invalid)).as(change).isInstanceOf(Failure.class);
    }
  }

  private String sign(JWTClaimsSet claims, KeyPair key, String kid, JWSAlgorithm algorithm)
      throws Exception {
    var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).build(), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
    return jwt.serialize();
  }
}
