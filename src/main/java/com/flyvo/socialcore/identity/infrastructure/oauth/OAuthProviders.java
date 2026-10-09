package com.flyvo.socialcore.identity.infrastructure.oauth;

import com.flyvo.socialcore.identity.application.port.ProviderClient;
import com.flyvo.socialcore.identity.domain.IdentityState.ProviderProof;
import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.api.Secrets;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuthProviders implements ProviderClient {
  private record Configuration(
      ClientRegistration client, String issuer, String jwks, String debug, String userInfo) {}

  private final Map<String, Configuration> providers = new java.util.HashMap<>();
  private final RestClient http;
  private final RestClientAuthorizationCodeTokenResponseClient exchange;
  private final Clock clock;
  private final Secrets secrets;

  public OAuthProviders(Environment env, Clock clock, Secrets secrets) {
    this.clock = clock;
    this.secrets = secrets;
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(5));
    http = RestClient.builder().requestFactory(factory).build();
    exchange = new RestClientAuthorizationCodeTokenResponseClient();
    exchange.setRestClient(
        RestClient.builder()
            .requestFactory(factory)
            .configureMessageConverters(
                builder ->
                    builder
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(
                new org.springframework.security.oauth2.client.http
                    .OAuth2ErrorResponseErrorHandler())
            .build());
    for (String name : java.util.List.of("google", "facebook")) {
      String prefix = "app.oauth." + name + ".";
      if (!env.getProperty(prefix + "enabled", Boolean.class, false)) continue;
      boolean google = name.equals("google");
      if (!google && !env.getProperty(prefix + "contract-approved", Boolean.class, false))
        throw new IllegalStateException("Facebook requires recorded official-contract approval");
      String id = env.getRequiredProperty(prefix + "client-id"),
          secret = env.getRequiredProperty(prefix + "client-secret"),
          redirect = env.getRequiredProperty(prefix + "redirect-uri");
      String version = google ? "" : env.getRequiredProperty(prefix + "graph-version");
      if (!google && !version.matches("v[0-9]+\\.[0-9]+"))
        throw new IllegalStateException("Pin Facebook Graph version");
      String authorization =
          env.getProperty(
              prefix + "authorization-uri",
              google
                  ? "https://accounts.google.com/o/oauth2/v2/auth"
                  : "https://www.facebook.com/" + version + "/dialog/oauth");
      String token =
          env.getProperty(
              prefix + "token-uri",
              google
                  ? "https://oauth2.googleapis.com/token"
                  : "https://graph.facebook.com/" + version + "/oauth/access_token");
      String issuer = google ? "https://accounts.google.com" : "https://www.facebook.com/" + id;
      String jwks =
          env.getProperty(prefix + "jwks-uri", "https://www.googleapis.com/oauth2/v3/certs");
      String debug =
          env.getProperty(
              prefix + "debug-uri", "https://graph.facebook.com/" + version + "/debug_token");
      String user =
          env.getProperty(
              prefix + "user-info-uri", "https://graph.facebook.com/" + version + "/me");
      for (String uri : java.util.List.of(authorization, token, redirect, jwks, debug, user)) {
        var parsed = URI.create(uri);
        if (parsed.getHost() == null
            || parsed.getUserInfo() != null
            || parsed.getFragment() != null
            || (!"https".equals(parsed.getScheme())
                && !(env.matchesProfiles("test")
                    && "http".equals(parsed.getScheme())
                    && java.util.Set.of("localhost", "127.0.0.1").contains(parsed.getHost()))))
          throw new IllegalStateException("Provider URLs must use trusted TLS");
      }
      if (!redirect.endsWith("/api/v1/auth/oauth/" + name + "/callback"))
        throw new IllegalStateException("Invalid callback URI");
      var registration =
          ClientRegistration.withRegistrationId(name)
              .clientId(id)
              .clientSecret(secret)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
              .redirectUri(redirect)
              .authorizationUri(authorization)
              .tokenUri(token)
              .scope(
                  google
                      ? new String[] {"openid", "email"}
                      : new String[] {"public_profile", "email"})
              .build();
      providers.put(
          name.toUpperCase(java.util.Locale.ROOT),
          new Configuration(registration, issuer, jwks, debug, user));
    }
  }

  private Configuration get(String provider) {
    var value = providers.get(provider);
    if (value == null) throw new Failure(404, "PROVIDER_DISABLED");
    return value;
  }

  public String authorization(String provider, String state, String nonce, String verifier) {
    var p = get(provider);
    var builder =
        UriComponentsBuilder.fromUriString(p.client().getProviderDetails().getAuthorizationUri())
            .queryParam("response_type", "code")
            .queryParam("client_id", p.client().getClientId())
            .queryParam("redirect_uri", p.client().getRedirectUri())
            .queryParam("scope", String.join(" ", p.client().getScopes()))
            .queryParam("state", state);
    if ("GOOGLE".equals(provider)) {
      builder
          .queryParam("nonce", nonce)
          .queryParam(
              "code_challenge",
              Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.hash(verifier)))
          .queryParam("code_challenge_method", "S256");
    }
    return builder.build().encode().toUriString();
  }

  public ProviderProof verify(String provider, String code, byte[] expectedNonce, String verifier) {
    var p = get(provider);
    if (code == null || code.length() > 4096) throw new Failure(401, "PROVIDER_PROOF_INVALID");
    try {
      var request =
          OAuth2AuthorizationRequest.authorizationCode()
              .authorizationUri(p.client().getProviderDetails().getAuthorizationUri())
              .clientId(p.client().getClientId())
              .redirectUri(p.client().getRedirectUri())
              .scopes(p.client().getScopes())
              .state("consumed-server-state")
              .attributes(
                  attributes -> {
                    if (verifier != null)
                      attributes.put(PkceParameterNames.CODE_VERIFIER, verifier);
                  })
              .build();
      var response =
          OAuth2AuthorizationResponse.success(code)
              .redirectUri(p.client().getRedirectUri())
              .state("consumed-server-state")
              .build();
      var result =
          exchange.getTokenResponse(
              new OAuth2AuthorizationCodeGrantRequest(
                  p.client(), new OAuth2AuthorizationExchange(request, response)));
      if ("GOOGLE".equals(provider)) {
        String raw = (String) result.getAdditionalParameters().get("id_token");
        if (raw == null) throw new Failure(401, "PROVIDER_PROOF_INVALID");
        var decoder =
            NimbusJwtDecoder.withJwkSetUri(p.jwks())
                .restOperations(
                    new org.springframework.web.client.RestTemplate(
                        new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                          {
                            setReadTimeout(Duration.ofSeconds(5));
                          }
                        }))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        var timestamp = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamp.setClock(clock);
        decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                timestamp,
                new JwtIssuerValidator(p.issuer()),
                jwt -> {
                  String azp = jwt.getClaimAsString("azp");
                  String nonce = jwt.getClaimAsString("nonce");
                  boolean valid =
                      jwt.getAudience().contains(p.client().getClientId())
                          && (jwt.getAudience().size() == 1 || azp != null)
                          && (azp == null || azp.equals(p.client().getClientId()))
                          && jwt.getExpiresAt() != null
                          && jwt.getIssuedAt() != null
                          && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                          && jwt.getSubject() != null
                          && nonce != null
                          && expectedNonce != null
                          && MessageDigest.isEqual(expectedNonce, secrets.hash(nonce));
                  return valid
                      ? OAuth2TokenValidatorResult.success()
                      : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                }));
        var jwt = decoder.decode(raw);
        return new ProviderProof(
            provider, p.issuer(), jwt.getSubject(), jwt.getClaimAsString("email"));
      }
      String token = result.getAccessToken().getTokenValue();
      var debug =
          http.get()
              .uri(
                  builder ->
                      UriComponentsBuilder.fromUriString(p.debug())
                          .queryParam("input_token", token)
                          .build()
                          .toUri())
              .header(
                  "Authorization",
                  "Bearer " + p.client().getClientId() + "|" + p.client().getClientSecret())
              .retrieve()
              .body(Map.class);
      if (debug == null
          || !(debug.get("data") instanceof Map<?, ?> data)
          || !Boolean.TRUE.equals(data.get("is_valid"))
          || !p.client().getClientId().equals(String.valueOf(data.get("app_id")))
          || data.get("user_id") == null
          || !(data.get("expires_at") instanceof Number expiry)
          || expiry.longValue() <= clock.instant().getEpochSecond()
          || !(data.get("data_access_expires_at") instanceof Number accessExpiry)
          || accessExpiry.longValue() <= clock.instant().getEpochSecond())
        throw new Failure(401, "PROVIDER_PROOF_INVALID");
      var user =
          http.get()
              .uri(p.userInfo() + "?fields=id,email")
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(Map.class);
      if (user == null || !String.valueOf(data.get("user_id")).equals(user.get("id")))
        throw new Failure(401, "PROVIDER_PROOF_INVALID");
      return new ProviderProof(
          provider, p.issuer(), (String) user.get("id"), (String) user.get("email"));
    } catch (Failure e) {
      throw e;
    } catch (JwtException | OAuth2AuthorizationException e) {
      Throwable cause = e;
      for (int i = 0; i < 10 && cause != null; i++, cause = cause.getCause())
        if (cause instanceof java.io.IOException
            || cause instanceof org.springframework.web.client.ResourceAccessException
            || cause instanceof org.springframework.web.client.RestClientResponseException response
                && response.getStatusCode().is5xxServerError())
          throw new Failure(503, "PROVIDER_UNAVAILABLE");
      throw new Failure(401, "PROVIDER_PROOF_INVALID");
    } catch (org.springframework.web.client.RestClientException e) {
      throw new Failure(503, "PROVIDER_UNAVAILABLE");
    } catch (IllegalArgumentException | ClassCastException e) {
      throw new Failure(401, "PROVIDER_PROOF_INVALID");
    }
  }
}
