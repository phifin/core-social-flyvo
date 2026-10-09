package com.flyvo.socialcore.identity.web;

import com.flyvo.socialcore.identity.application.OAuthAuthentication;
import com.flyvo.socialcore.platform.api.ActorContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class OAuthController {
  private final OAuthAuthentication oauth;
  private final AuthController cookies;
  private final String frontend;

  OAuthController(OAuthAuthentication oauth, AuthController cookies, Environment env) {
    this.oauth = oauth;
    this.cookies = cookies;
    frontend = env.getRequiredProperty("app.auth.frontend-origin");
  }

  @GetMapping("/auth/oauth/{provider}/authorize")
  void authorize(
      @PathVariable String provider,
      @CookieValue(name = "oauth_binding", required = false) String browser,
      HttpServletResponse response) {
    start(provider, "LOGIN", null, browser, response);
  }

  @PostMapping("/me/identities/{provider}/link-intents")
  java.util.Map<String, String> link(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable String provider,
      @CookieValue(name = "oauth_binding", required = false) String browser,
      HttpServletResponse response) {
    var value = oauth.start(provider, "LINK", actor, browser);
    cookies.cookie(response, "oauth_binding", value.browser(), "/api/v1/auth", "Lax", 600);
    return java.util.Map.of("authorizationUrl", value.location());
  }

  void start(
      String provider,
      String intent,
      ActorContext actor,
      String browser,
      HttpServletResponse response) {
    var value = oauth.start(provider, intent, actor, browser);
    cookies.cookie(response, "oauth_binding", value.browser(), "/api/v1/auth", "Lax", 600);
    response.setStatus(303);
    response.setHeader("Location", value.location());
  }

  @GetMapping("/auth/oauth/{provider}/callback")
  void callback(
      @PathVariable String provider,
      @RequestParam String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      @CookieValue(name = "oauth_binding", required = false) String browser,
      HttpServletResponse response) {
    var result = oauth.callback(provider, state, browser, code, error);
    if (result.refresh() != null)
      cookies.cookie(response, "refresh", result.refresh(), "/api/v1/auth", "Strict", 7 * 86400);
    if (result.onboarding() != null)
      cookies.cookie(response, "onboarding", result.onboarding(), "/api/v1/auth", "Strict", 600);
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setStatus(303);
    response.setHeader("Location", frontend + "/auth/result#status=" + result.marker());
  }
}
