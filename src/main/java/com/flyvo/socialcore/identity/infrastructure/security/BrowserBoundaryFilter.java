package com.flyvo.socialcore.identity.infrastructure.security;

import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.api.Secrets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class BrowserBoundaryFilter extends OncePerRequestFilter {
  private static final RequestMatcher OAUTH_AUTHORIZE =
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.GET, "/api/v1/auth/oauth/{provider}/authorize");
  private static final RequestMatcher OAUTH_CALLBACK =
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.GET, "/api/v1/auth/oauth/{provider}/callback");
  private static final RequestMatcher NOTIFICATION_STREAM =
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.GET, "/api/v1/notifications/stream");
  private static final RequestMatcher SEARCH =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/search/{kind}");
  private final String origin;
  private final HandlerExceptionResolver errors;
  private final Secrets secrets;
  private final Clock clock;
  private final int oauthAuthorizeLimit;
  private final int oauthCallbackLimit;
  private final Map<String, Bucket> buckets = new LinkedHashMap<>();

  private record Bucket(double tokens, long at) {}

  public BrowserBoundaryFilter(
      Environment env,
      @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
          HandlerExceptionResolver errors,
      Secrets secrets,
      Clock clock) {
    origin = env.getRequiredProperty("app.auth.frontend-origin");
    this.errors = errors;
    this.secrets = secrets;
    this.clock = clock;
    oauthAuthorizeLimit = oauthLimit(env, "oauth-authorize-per-minute", 10);
    oauthCallbackLimit = oauthLimit(env, "oauth-callback-per-minute", 20);
    var uri = java.net.URI.create(origin);
    boolean secure = env.getProperty("app.auth.secure-cookies", Boolean.class, true);
    if (uri.getHost() == null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().isEmpty()
        || (!"https".equals(uri.getScheme())
            && !(env.matchesProfiles("local", "test")
                && "http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
        || !secure && !env.matchesProfiles("local", "test"))
      throw new IllegalStateException("Invalid browser origin/cookie configuration");
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = request.getRequestURI();
    boolean unsafe = !java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
    if (path.startsWith("/api/v1/")) response.setHeader("Cache-Control", "no-store");
    if (SEARCH.matches(request)) {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (authentication != null
          && authentication.getPrincipal()
              instanceof com.flyvo.socialcore.platform.api.ActorContext actor
          && (!take("search-ip:" + request.getRemoteAddr(), 30, 60000)
              || !take("search-user:" + actor.userId(), 30, 60000))) {
        response.setHeader("Retry-After", "60");
        errors.resolveException(request, response, null, new Failure(429, "RATE_LIMITED"));
        return;
      }
    }
    if (NOTIFICATION_STREAM.matches(request)) {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (authentication != null
          && authentication.getPrincipal()
              instanceof com.flyvo.socialcore.platform.api.ActorContext actor
          && (!take("stream-ip:" + request.getRemoteAddr(), 10, 60000)
              || !take("stream-user:" + actor.userId(), 10, 60000))) {
        response.setHeader("Retry-After", "60");
        errors.resolveException(request, response, null, new Failure(429, "RATE_LIMITED"));
        return;
      }
    }
    // Top-level OAuth GETs need abuse protection without unsafe-method Origin/CSRF checks.
    // Share each budget across both providers; reject before insertion/state consumption/network.
    boolean authorize = OAUTH_AUTHORIZE.matches(request);
    if (authorize || OAUTH_CALLBACK.matches(request)) {
      if (!take(
          "ip:" + request.getRemoteAddr() + (authorize ? ":oauth-authorize" : ":oauth-callback"),
          authorize ? oauthAuthorizeLimit : oauthCallbackLimit,
          60000)) {
        response.setHeader("Retry-After", "60");
        errors.resolveException(request, response, null, new Failure(429, "RATE_LIMITED"));
        return;
      }
    }
    if (unsafe && path.startsWith("/api/v1/")) {
      if (!origin.equals(request.getHeader("Origin"))) {
        errors.resolveException(request, response, null, new Failure(403, "UNTRUSTED_ORIGIN"));
        return;
      }
      int limit =
          path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/reauthenticate")
              ? 10
              : path.equals("/api/v1/auth/registrations")
                      || path.equals("/api/v1/auth/password-resets")
                  ? 20
                  : 60;
      long window = limit == 20 ? 3600000 : 60000;
      String key =
          "ip:"
              + request.getRemoteAddr()
              + ":"
              + (limit == 20 ? "email" : limit == 10 ? "login" : "write");
      if (!take(key, limit, window)) {
        response.setHeader("Retry-After", limit == 20 ? "3600" : "60");
        errors.resolveException(request, response, null, new Failure(429, "RATE_LIMITED"));
        return;
      }
      var auth =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (auth != null
          && auth.getPrincipal() instanceof com.flyvo.socialcore.platform.api.ActorContext actor
          && !take("user:" + actor.userId(), 60, 60000)) {
        response.setHeader("Retry-After", "60");
        errors.resolveException(request, response, null, new Failure(429, "RATE_LIMITED"));
        return;
      }
      int bodyLimit =
          path.matches("/api/v1/posts(?:/[^/]+)?")
              ? 262144
              : path.matches("/api/v1/(?:reports|posts/[^/]+/comments|comments/[^/]+(?:/replies)?)")
                  ? 65536
                  : 16384;
      if (request.getContentLengthLong() > bodyLimit) {
        errors.resolveException(request, response, null, new Failure(413, "BODY_TOO_LARGE"));
        return;
      }
      byte[] body = request.getInputStream().readNBytes(bodyLimit + 1);
      if (body.length > bodyLimit) {
        errors.resolveException(request, response, null, new Failure(413, "BODY_TOO_LARGE"));
        return;
      }
      request =
          new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
              var input = new java.io.ByteArrayInputStream(body);
              return new jakarta.servlet.ServletInputStream() {
                public int read() {
                  return input.read();
                }

                public boolean isFinished() {
                  return input.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(jakarta.servlet.ReadListener listener) {
                  throw new UnsupportedOperationException("Async request bodies are not supported");
                }
              };
            }

            @Override
            public java.io.BufferedReader getReader() {
              return new java.io.BufferedReader(
                  new java.io.InputStreamReader(
                      getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          };
    }
    chain.doFilter(request, response);
  }

  public void loginKey(String email) {
    String key = java.util.Base64.getEncoder().encodeToString(secrets.emailKey(email));
    if (!take("login:" + key, 5, 60000)) throw new Failure(429, "RATE_LIMITED");
  }

  private static int oauthLimit(Environment env, String property, int fallback) {
    int value = env.getProperty("app.auth." + property, Integer.class, fallback);
    if (value < 1 || value > 1000)
      throw new IllegalStateException("OAuth rate limits must be between 1 and 1000 per minute");
    return value;
  }

  private synchronized boolean take(String key, int capacity, long window) {
    long now = clock.millis();
    buckets.entrySet().removeIf(e -> now - e.getValue().at() > 3600000);
    var old = buckets.get(key);
    if (old == null && buckets.size() >= 10000) return false;
    double available =
        old == null
            ? capacity
            : Math.min(
                capacity, old.tokens() + Math.max(0, now - old.at()) * (double) capacity / window);
    buckets.put(key, new Bucket(available >= 1 ? available - 1 : available, now));
    return available >= 1;
  }
}
