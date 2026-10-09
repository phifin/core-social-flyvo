package com.flyvo.socialcore.identity.infrastructure.security;

import com.flyvo.socialcore.identity.api.SessionQueries;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.platform.api.Failure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class BearerSessionFilter extends OncePerRequestFilter {
  private final AccessTokens tokens;
  private final SessionQueries sessions;
  private final HandlerExceptionResolver errors;

  public BearerSessionFilter(
      AccessTokens tokens,
      SessionQueries sessions,
      @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
          HandlerExceptionResolver errors) {
    this.tokens = tokens;
    this.sessions = sessions;
    this.errors = errors;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      try {
        if (!header.startsWith("Bearer ")
            || java.util.Collections.list(request.getHeaders("Authorization")).size() != 1)
          throw Failure.unauthorized();
        var actor = tokens.validate(header.substring(7));
        sessions.requireCurrent(actor);
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
      } catch (Failure
          | org.springframework.dao.DataAccessException
          | org.springframework.transaction.TransactionException failure) {
        errors.resolveException(
            request,
            response,
            null,
            failure instanceof Failure
                ? (Failure) failure
                : new Failure(503, "DEPENDENCY_UNAVAILABLE"));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
