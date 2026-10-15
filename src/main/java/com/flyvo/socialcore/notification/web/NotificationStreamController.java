package com.flyvo.socialcore.notification.web;

import com.flyvo.socialcore.identity.api.StreamAuthentication;
import com.flyvo.socialcore.notification.infrastructure.realtime.NotificationStreams;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.Failure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationStreamController {
  private final StreamAuthentication identity;
  private final NotificationStreams streams;

  public NotificationStreamController(StreamAuthentication identity, NotificationStreams streams) {
    this.identity = identity;
    this.streams = streams;
  }

  @GetMapping(value = "/api/v1/notifications/stream", produces = "text/event-stream")
  public void stream(
      Authentication auth,
      @RequestHeader("Authorization") String header,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (auth == null
        || !(auth.getPrincipal() instanceof ActorContext actor)
        || !header.startsWith("Bearer ")
        || request.getQueryString() != null) throw Failure.unauthorized();
    var proof = identity.verify(header.substring(7));
    if (!proof.actor().equals(actor)) throw Failure.unauthorized();
    streams.open(proof, request, response);
  }
}
