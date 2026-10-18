package com.flyvo.socialcore.identity.infrastructure.security;

import com.flyvo.socialcore.platform.api.Failure;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      BearerSessionFilter bearer,
      BrowserBoundaryFilter browser,
      org.springframework.core.env.Environment env,
      @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
          org.springframework.web.servlet.HandlerExceptionResolver errors)
      throws Exception {
    var csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    boolean secure = env.getProperty("app.auth.secure-cookies", Boolean.class, true);
    csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(secure).path("/"));
    var cors = new org.springframework.web.cors.CorsConfiguration();
    cors.setAllowedOrigins(java.util.List.of(env.getRequiredProperty("app.auth.frontend-origin")));
    cors.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        java.util.List.of(
            "Authorization",
            "Content-Type",
            "X-XSRF-TOKEN",
            "If-Match",
            "Idempotency-Key",
            "X-Request-ID"));
    cors.addAllowedHeader("Last-Event-ID");
    cors.setExposedHeaders(java.util.List.of("ETag", "X-Request-ID"));
    cors.setAllowCredentials(true);
    var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/**", cors);
    var corsFilter = new org.springframework.web.filter.CorsFilter(source);
    corsFilter.setCorsProcessor(
        (configuration, request, response) ->
            new org.springframework.web.cors.DefaultCorsProcessor() {
              @Override
              protected void rejectRequest(
                  org.springframework.http.server.ServerHttpResponse ignored) {
                errors.resolveException(request, response, null, new Failure(403, "CORS_DENIED"));
              }
            }.processRequest(configuration, request, response));
    return http.addFilter(corsFilter)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokens)
                    .csrfTokenRequestHandler(
                        new org.springframework.security.web.csrf
                            .CsrfTokenRequestAttributeHandler() {
                          @Override
                          public String resolveCsrfTokenValue(
                              jakarta.servlet.http.HttpServletRequest request,
                              org.springframework.security.web.csrf.CsrfToken token) {
                            return request.getHeader(token.getHeaderName());
                          }
                        }))
        .addFilterBefore(bearer, org.springframework.security.web.csrf.CsrfFilter.class)
        .addFilterAfter(browser, org.springframework.security.web.csrf.CsrfFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/communities/*/feed", "/api/v1/users/*/timeline")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/feeds/home")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/search/users",
                        "/api/v1/search/communities",
                        "/api/v1/search/posts",
                        "/api/v1/admin/search/rebuilds/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/search/rebuilds")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/accounts/*/erasure")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*/erasure/resumes")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/notifications",
                        "/api/v1/notifications/unread-count",
                        "/api/v1/notifications/stream",
                        "/api/v1/me/notification-preferences")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/notifications/*/read")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/notifications/read")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/me/notification-preferences")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/communities/*/moderation/reports/*/history",
                        "/api/v1/admin/moderation/reports/*/history",
                        "/api/v1/communities/*/moderation/actions/*",
                        "/api/v1/admin/moderation/actions/*",
                        "/api/v1/admin/moderation/metrics",
                        "/api/v1/admin/moderation/accounts/*",
                        "/api/v1/admin/moderation/maintenance/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/communities/*/moderation/reports/*/assignment",
                        "/api/v1/admin/moderation/reports/*/assignment",
                        "/api/v1/communities/*/moderation/reports/*/hold",
                        "/api/v1/admin/moderation/reports/*/hold",
                        "/api/v1/communities/*/moderation/actions/*/hold",
                        "/api/v1/admin/moderation/actions/*/hold")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/admin/moderation/actions",
                        "/api/v1/admin/moderation/maintenance/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/me/reports",
                        "/api/v1/communities/*/moderation/reports",
                        "/api/v1/communities/*/moderation/reports/*",
                        "/api/v1/admin/moderation/reports",
                        "/api/v1/admin/moderation/reports/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/reports",
                        "/api/v1/communities/*/moderation/reports/*/actions",
                        "/api/v1/admin/moderation/reports/*/actions")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/posts/*/comments",
                        "/api/v1/comments/*",
                        "/api/v1/comments/*/replies",
                        "/api/v1/posts/*/reactions",
                        "/api/v1/comments/*/reactions")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/posts/*/comments", "/api/v1/comments/*/replies")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/comments/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/v1/comments/*",
                        "/api/v1/posts/*/reactions/me",
                        "/api/v1/comments/*/reactions/me")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/posts/*/reactions/me",
                        "/api/v1/comments/*/reactions/me")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/posts/*/media/*/access",
                        "/api/v1/users/*/avatar/access")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/posts/*/revisions", "/api/v1/media/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/posts",
                        "/api/v1/media/uploads",
                        "/api/v1/media/*/finalize",
                        "/api/v1/communities/*/moderation/posts/*/actions")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PUT, "/api/v1/posts/*/audience", "/api/v1/me/avatar")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/posts/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/*", "/api/v1/media/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/oauth/google/authorize",
                        "/api/v1/auth/oauth/facebook/authorize",
                        "/api/v1/auth/oauth/google/callback",
                        "/api/v1/auth/oauth/facebook/callback",
                        "/api/v1/users/*/profile",
                        "/api/v1/users/*/graph/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/me/graph/*", "/api/v1/me/friend-requests")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/me/following/*",
                        "/api/v1/me/blocks/*",
                        "/api/v1/me/mutes/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/v1/me/following/*",
                        "/api/v1/me/blocks/*",
                        "/api/v1/me/mutes/*",
                        "/api/v1/me/friends/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/friend-requests",
                        "/api/v1/friend-requests/*/accept",
                        "/api/v1/friend-requests/*/decline",
                        "/api/v1/friend-requests/*/cancel")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/communities", "/api/v1/communities/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/communities/*/members",
                        "/api/v1/communities/*/join-requests",
                        "/api/v1/me/community-invitations")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/communities",
                        "/api/v1/communities/*/archive",
                        "/api/v1/communities/*/ownership-transfers",
                        "/api/v1/communities/*/invitations",
                        "/api/v1/communities/*/join-requests",
                        "/api/v1/communities/*/moderation/actions",
                        "/api/v1/community-invitations/*/accept",
                        "/api/v1/community-invitations/*/decline",
                        "/api/v1/community-invitations/*/revoke",
                        "/api/v1/join-requests/*/approve",
                        "/api/v1/join-requests/*/reject",
                        "/api/v1/join-requests/*/cancel")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/communities/*/members/me")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/v1/communities/*",
                        "/api/v1/communities/*/members/*/role")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/v1/communities/*/members/me",
                        "/api/v1/communities/*/members/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/registrations",
                        "/api/v1/auth/registrations/complete",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/password-resets",
                        "/api/v1/auth/password-resets/complete")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/auth/sessions",
                        "/api/v1/me/account",
                        "/api/v1/me/identities",
                        "/api/v1/me/profile",
                        "/api/v1/me/preferences")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/logout-all",
                        "/api/v1/auth/reauthenticate",
                        "/api/v1/me/email-changes",
                        "/api/v1/me/email-changes/complete",
                        "/api/v1/me/identities/google/link-intents",
                        "/api/v1/me/identities/facebook/link-intents")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/me/password")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PATCH, "/api/v1/me/profile", "/api/v1/me/preferences")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/v1/auth/sessions/*",
                        "/api/v1/me/identities/*",
                        "/api/v1/me/account")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            config ->
                config
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            errors.resolveException(
                                request, response, null, Failure.unauthorized()))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            errors.resolveException(
                                request, response, null, new Failure(403, "ACCESS_DENIED"))))
        .build();
  }
}
