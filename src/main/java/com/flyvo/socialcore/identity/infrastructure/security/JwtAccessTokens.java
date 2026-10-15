package com.flyvo.socialcore.identity.infrastructure.security;

import com.flyvo.socialcore.identity.api.StreamAuthentication;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.Failure;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class JwtAccessTokens implements AccessTokens, StreamAuthentication {
  private final NimbusJwtEncoder encoder;
  private final List<NimbusJwtDecoder> decoders = new ArrayList<>();
  private final Clock clock;
  private final String issuer;
  private final String audience;
  private final String kid;

  public JwtAccessTokens(Environment env, Clock clock) throws Exception {
    this(
        env,
        clock,
        org.springframework.boot.context.properties.bind.Binder.get(env)
            .bind(
                "app.auth",
                org.springframework.boot.context.properties.bind.Bindable.of(AuthProperties.class))
            .orElseThrow(() -> new IllegalStateException("Authentication configuration missing")));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public JwtAccessTokens(Environment env, Clock clock, AuthProperties properties) throws Exception {
    this.clock = clock;
    issuer = properties.issuer();
    audience = properties.audience();
    kid = properties.signingKeyId();
    RSAPrivateKey privateKey;
    RSAPublicKey publicKey;
    if (env.getProperty("app.security.ephemeral-keys", Boolean.class, false)) {
      if (!env.matchesProfiles("local", "test"))
        throw new IllegalStateException("Ephemeral keys require local/test");
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(3072);
      var pair = generator.generateKeyPair();
      privateKey = (RSAPrivateKey) pair.getPrivate();
      publicKey = (RSAPublicKey) pair.getPublic();
    } else {
      var factory = KeyFactory.getInstance("RSA");
      privateKey =
          (RSAPrivateKey)
              factory.generatePrivate(new PKCS8EncodedKeySpec(pem(properties.privateKeyFile())));
      publicKey =
          (RSAPublicKey)
              factory.generatePublic(new X509EncodedKeySpec(pem(properties.publicKeyFile())));
    }
    if (publicKey.getModulus().bitLength() < 2048
        || !privateKey.getModulus().equals(publicKey.getModulus()))
      throw new IllegalStateException("Invalid RSA key pair");
    encoder =
        new NimbusJwtEncoder(
            new ImmutableJWKSet<>(
                new JWKSet(
                    new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid).build())));
    addDecoder(publicKey, kid);
    String old = properties.retainedPublicKeys();
    if (!old.isBlank())
      for (var entry : old.split(",")) {
        var parts = entry.split("=", 2);
        addDecoder(
            (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(parts[1]))),
            parts[0]);
      }
  }

  private static byte[] pem(String path) throws Exception {
    return Base64.getMimeDecoder()
        .decode(Files.readString(Path.of(path)).replaceAll("-----[^-]+-----", ""));
  }

  private void addDecoder(RSAPublicKey key, String expectedKid) {
    var decoder =
        NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
    var timestamp = new JwtTimestampValidator(java.time.Duration.ofSeconds(30));
    timestamp.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamp,
            new JwtIssuerValidator(issuer),
            jwt -> {
              boolean valid =
                  jwt.getAudience().equals(List.of(audience))
                      && expectedKid.equals(jwt.getHeaders().get("kid"))
                      && jwt.getIssuedAt() != null
                      && jwt.getExpiresAt() != null
                      && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                      && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                      && java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())
                              .toMillis()
                          <= 300000
                      && jwt.getId() != null
                      && jwt.getClaimAsString("sid") != null;
              return valid
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }));
    decoders.add(decoder);
  }

  public String issue(ActorContext actor) {
    var now = clock.instant();
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(actor.userId().toString())
            .claim("sid", actor.sessionId().toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .id(UUID.randomUUID().toString())
            .build();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build(), claims))
        .getTokenValue();
  }

  public ActorContext validate(String token) {
    return verified(token).actor();
  }

  public Proof verify(String token) {
    var proof = verified(token);
    if (!clock.instant().isBefore(proof.expiresAt())) throw Failure.unauthorized();
    return proof;
  }

  private Proof verified(String token) {
    if (token == null || token.length() > 8192) throw Failure.unauthorized();
    for (var decoder : decoders)
      try {
        var jwt = decoder.decode(token);
        return new Proof(
            new ActorContext(
                UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid"))),
            jwt.getExpiresAt());
      } catch (JwtException | IllegalArgumentException ignored) {
      }
    throw Failure.unauthorized();
  }
}
