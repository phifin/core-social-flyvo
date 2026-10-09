package com.flyvo.socialcore.identity.web;

import com.flyvo.socialcore.identity.application.Authentication;
import com.flyvo.socialcore.identity.application.OAuthAuthentication;
import com.flyvo.socialcore.identity.infrastructure.security.BrowserBoundaryFilter;
import com.flyvo.socialcore.platform.api.ActorContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final Authentication auth;
  private final BrowserBoundaryFilter limits;
  private final boolean secure;
  private final OAuthAuthentication oauth;

  public AuthController(
      Authentication auth,
      BrowserBoundaryFilter limits,
      Environment env,
      OAuthAuthentication oauth) {
    this.auth = auth;
    this.limits = limits;
    secure = env.getProperty("app.auth.secure-cookies", Boolean.class, true);
    this.oauth = oauth;
  }

  public record EmailRequest(@NotBlank @Size(max = 320) String email) {}

  public record CompleteRequest(
      @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{43}") String secret,
      @Size(max = 256) String password) {}

  public record LoginRequest(
      @NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 256) String password) {}

  public record PasswordRequest(@NotBlank @Size(max = 256) String password) {}

  public record ProofRequest(
      @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{43}")
          String secret) {}

  public record ReauthRequest(@Size(max = 256) String password, String provider) {}

  public record AccessResponse(
      String accessToken, String tokenType, int expiresIn, UUID sessionId) {}

  public record CsrfResponse(String token, String headerName) {}

  @GetMapping("/auth/csrf")
  public CsrfResponse csrf(CsrfToken token) {
    return new CsrfResponse(token.getToken(), token.getHeaderName());
  }

  @PostMapping("/auth/registrations")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public void register(
      @Valid @RequestBody EmailRequest request,
      @CookieValue(name = "registration_binding", required = false) String browser,
      @CookieValue(name = "onboarding", required = false) String onboarding,
      @CookieValue(name = "oauth_binding", required = false) String oauthBrowser,
      HttpServletResponse response) {
    if (browser == null) {
      browser = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
      cookie(response, "registration_binding", browser, "/api/v1/auth", "Strict", 1800);
    }
    if (onboarding == null) auth.register(request.email(), browser);
    else {
      oauth.register(request.email(), onboarding, oauthBrowser, browser);
      cookie(response, "onboarding", "", "/api/v1/auth", "Strict", 0);
    }
  }

  @PostMapping("/auth/registrations/complete")
  public AccessResponse complete(
      @Valid @RequestBody CompleteRequest request,
      @CookieValue(name = "registration_binding", required = false) String browser,
      HttpServletResponse response) {
    return grant(auth.complete(request.secret(), browser, request.password()), response);
  }

  @PostMapping("/auth/login")
  public AccessResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    limits.loginKey(auth.normalize(request.email()));
    return grant(auth.login(request.email(), request.password()), response);
  }

  @PostMapping("/auth/refresh")
  public AccessResponse refresh(
      @CookieValue(name = "refresh", required = false) String refresh,
      HttpServletResponse response) {
    return grant(auth.refresh(refresh), response);
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void logout(
      @CookieValue(name = "refresh", required = false) String refresh,
      HttpServletResponse response) {
    auth.logout(refresh);
    cookie(response, "refresh", "", "/api/v1/auth", "Strict", 0);
  }

  @PostMapping("/auth/logout-all")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void all(@AuthenticationPrincipal ActorContext actor, HttpServletResponse response) {
    auth.logoutAll(actor);
    cookie(response, "refresh", "", "/api/v1/auth", "Strict", 0);
  }

  @GetMapping("/auth/sessions")
  public java.util.Map<String, Object> sessions(
      @AuthenticationPrincipal ActorContext actor,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "0") int offset) {
    return java.util.Map.of("items", auth.sessions(actor, size, offset));
  }

  @DeleteMapping("/auth/sessions/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    auth.revokeSession(actor, id);
  }

  @PostMapping("/auth/password-resets")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public void requestReset(@Valid @RequestBody EmailRequest request) {
    auth.requestReset(request.email());
  }

  @PostMapping("/auth/password-resets/complete")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void reset(@Valid @RequestBody CompleteRequest request) {
    auth.reset(request.secret(), request.password());
  }

  @PostMapping("/auth/reauthenticate")
  public org.springframework.http.ResponseEntity<?> reauth(
      @AuthenticationPrincipal ActorContext actor,
      @Valid @RequestBody ReauthRequest request,
      @CookieValue(name = "oauth_binding", required = false) String browser,
      HttpServletResponse response) {
    if ((request.password() == null) == (request.provider() == null))
      throw com.flyvo.socialcore.platform.api.Failure.invalid();
    if (request.provider() != null) {
      if (request.password() != null) throw com.flyvo.socialcore.platform.api.Failure.invalid();
      var start = oauth.start(request.provider(), "REAUTH", actor, browser);
      cookie(response, "oauth_binding", start.browser(), "/api/v1/auth", "Lax", 600);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("authorizationUrl", start.location()));
    } else {
      auth.reauthenticate(actor, request.password());
      return org.springframework.http.ResponseEntity.noContent().build();
    }
  }

  @GetMapping("/me/account")
  public org.springframework.http.ResponseEntity<Authentication.AccountInfo> account(
      @AuthenticationPrincipal ActorContext actor) {
    var value = auth.account(actor);
    return org.springframework.http.ResponseEntity.ok()
        .eTag("\"" + value.version() + "\"")
        .body(value);
  }

  @PutMapping("/me/password")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void password(
      @AuthenticationPrincipal ActorContext actor, @Valid @RequestBody PasswordRequest request) {
    auth.password(actor, request.password());
  }

  @PostMapping("/me/email-changes")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public void email(
      @AuthenticationPrincipal ActorContext actor, @Valid @RequestBody EmailRequest request) {
    auth.requestEmail(actor, request.email());
  }

  @PostMapping("/me/email-changes/complete")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void emailComplete(
      @AuthenticationPrincipal ActorContext actor, @Valid @RequestBody ProofRequest request) {
    auth.completeEmail(actor, request.secret());
  }

  @GetMapping("/me/identities")
  public java.util.Map<String, Object> identities(@AuthenticationPrincipal ActorContext actor) {
    return java.util.Map.of("items", auth.identities(actor));
  }

  @DeleteMapping("/me/identities/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void unlink(@AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    auth.unlink(actor, id);
  }

  @DeleteMapping("/me/account")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void close(
      @AuthenticationPrincipal ActorContext actor,
      @RequestHeader(name = "If-Match", required = false) String match,
      HttpServletResponse response) {
    auth.close(actor, com.flyvo.socialcore.platform.api.ResourceVersions.require(match));
    cookie(response, "refresh", "", "/api/v1/auth", "Strict", 0);
  }

  private AccessResponse grant(Authentication.Grant value, HttpServletResponse response) {
    cookie(response, "refresh", value.refreshSecret(), "/api/v1/auth", "Strict", 7 * 86400);
    return new AccessResponse(value.accessToken(), "Bearer", 300, value.sessionId());
  }

  void cookie(
      HttpServletResponse response,
      String name,
      String value,
      String path,
      String sameSite,
      long seconds) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite(sameSite)
            .path(path)
            .maxAge(Duration.ofSeconds(seconds))
            .build()
            .toString());
  }
}
