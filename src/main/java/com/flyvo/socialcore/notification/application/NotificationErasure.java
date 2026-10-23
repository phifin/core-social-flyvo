package com.flyvo.socialcore.notification.application;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.content.api.ContentNotificationSources;
import com.flyvo.socialcore.notification.application.port.*;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class NotificationErasure implements JobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final NotificationErasureStore store;
  private final InboxRepository inbox;
  private final ContentNotificationSources content;
  private final ObjectMapper json;

  public NotificationErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      NotificationErasureStore store,
      InboxRepository inbox,
      ContentNotificationSources content,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.store = store;
    this.inbox = inbox;
    this.content = content;
    this.json = json;
  }

  public String kind() {
    return "NOTIFICATION_ERASURE";
  }

  public void apply(String payload) {
    var p = json.readValue(payload, Pass.class);
    if (p.step() != Step.NOTIFICATION) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    String stage = erasure.checkpoint(p);
    if (stage == null) return;
    var parts = stage.split(":");
    boolean targets = parts[0].equals("POST") || parts[0].equals("COMMENT");
    UUID after = parts.length > 1 && !parts[1].equals("START") ? UUID.fromString(parts[1]) : null;
    UUID target = null;
    if (targets) {
      var ids =
          content.ownedTargets(
              ContentNotificationSources.Type.valueOf(parts[0]), p.userId(), after, 1);
      if (!ids.isEmpty()) target = ids.getFirst();
    }
    var batch =
        stage.equals("START") || targets && target == null
            ? new NotificationErasureStore.Batch("GROUP", List.of())
            : store.pending(p.userId(), targets ? parts[0] : null, target);
    var recipients =
        batch.items().stream()
            .map(NotificationErasureStore.Item::recipient)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    java.util.stream.Stream.concat(java.util.stream.Stream.of(p.userId()), recipients.stream())
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(id -> accounts.guard(id, false));
    var c = erasure.begin(p.userId(), Step.NOTIFICATION, p.version());
    if (c == null) return;
    var now = erasure.databaseTime();
    // The permanent account guard is effective before this owner reaches its prerequisites.
    for (var id : recipients) inbox.guard(id, now);
    if (stage.equals("START")) {
      inbox.guard(c.userId(), now);
      store.terminal(c.userId(), now);
      int count = store.preferences(c.userId());
      erasure.advance(c, "OWN", count, "RUNNABLE", null, null, null);
      return;
    }
    store.erase(batch, now);
    String next = stage;
    if (batch.items().isEmpty()) {
      if (!targets) next = "POST:START";
      else if (target != null) next = parts[0] + ":" + target;
      else if (parts[0].equals("POST")) next = "COMMENT:START";
      else {
        erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "NOTIFICATION_ABSENCE_V1");
        return;
      }
    }
    erasure.advance(
        c,
        next,
        batch.items().isEmpty() && target != null ? 1 : batch.items().size(),
        "RUNNABLE",
        null,
        null,
        null);
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
