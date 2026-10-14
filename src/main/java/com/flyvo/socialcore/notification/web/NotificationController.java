package com.flyvo.socialcore.notification.web;

import com.flyvo.socialcore.notification.api.NotificationCommands;
import com.flyvo.socialcore.notification.api.NotificationQueries;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.Failure;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final NotificationQueries queries;
  private final NotificationCommands commands;

  public NotificationController(NotificationQueries queries, NotificationCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  private ActorContext actor(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof ActorContext a))
      throw Failure.unauthorized();
    return a;
  }

  record Read(long activityVersion) {}

  record Unread(long unreadCount) {}

  record Preferences(Map<NotificationQueries.Kind, Boolean> inApp) {}

  @GetMapping("/notifications")
  public NotificationQueries.Page inbox(
      Authentication auth,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String after) {
    return queries.inbox(actor(auth), size, after);
  }

  @GetMapping("/notifications/unread-count")
  public Unread unread(Authentication auth) {
    return new Unread(queries.unread(actor(auth)));
  }

  @PutMapping("/notifications/{id}/read")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void read(Authentication auth, @PathVariable UUID id, @RequestBody Read request) {
    commands.read(actor(auth), id, request.activityVersion());
  }

  @PostMapping("/notifications/read")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void readAll(Authentication auth) {
    commands.readAll(actor(auth));
  }

  @GetMapping("/me/notification-preferences")
  public Preferences preferences(Authentication auth) {
    return new Preferences(queries.preferences(actor(auth)));
  }

  @PatchMapping("/me/notification-preferences")
  public Preferences preferences(Authentication auth, @RequestBody Preferences changes) {
    return new Preferences(commands.preferences(actor(auth), changes.inApp()));
  }
}
