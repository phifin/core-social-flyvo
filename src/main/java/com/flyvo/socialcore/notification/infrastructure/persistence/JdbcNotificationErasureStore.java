package com.flyvo.socialcore.notification.infrastructure.persistence;

import com.flyvo.socialcore.notification.application.port.NotificationErasureStore;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationErasureStore implements NotificationErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcNotificationErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void terminal(UUID subject, Instant now) {
    jdbc.update(
        "INSERT INTO notification.recipient_state(recipient_id,terminal,changed_at) VALUES (?,true,?) ON CONFLICT(recipient_id) DO UPDATE SET terminal=true,watermark=notification.recipient_state.watermark+1,changed_at=EXCLUDED.changed_at",
        subject,
        now.atOffset(ZoneOffset.UTC));
  }

  public int preferences(UUID subject) {
    return jdbc.update("DELETE FROM notification.preference WHERE user_id=?", subject);
  }

  public Batch pending(UUID subject, String type, UUID target) {
    boolean own = target == null;
    var args = own ? new Object[] {subject, subject} : new Object[] {type, target};
    String sources =
        own
            ? "(s.recipient_id=? OR s.actor_id=?)"
            : "s.notification_id IN (SELECT id FROM notification.notification WHERE target_type=? AND target_id=?)";
    var items =
        jdbc.query(
            "SELECT s.recipient_id,s.notification_id,s.kind,s.source_type,s.source_id,s.actor_id FROM notification.source_state s WHERE "
                + sources
                + " AND (s.active OR s.notification_id IS NOT NULL) ORDER BY s.recipient_id,s.kind,s.source_type,s.source_id,s.actor_id LIMIT 100",
            (r, n) ->
                new Item(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getString(3),
                    r.getString(4),
                    r.getObject(5, UUID.class),
                    r.getObject(6, UUID.class)),
            args);
    if (!items.isEmpty()) return new Batch("SOURCE", items);
    String groups =
        own ? "(n.recipient_id=? OR c.actor_id=?)" : "(n.target_type=? AND n.target_id=?)";
    items =
        jdbc.query(
            "SELECT n.recipient_id,c.id FROM notification.contribution c JOIN notification.notification n ON n.id=c.notification_id WHERE "
                + groups
                + " ORDER BY n.recipient_id,c.id LIMIT 100",
            (r, n) ->
                new Item(
                    r.getObject(1, UUID.class), r.getObject(2, UUID.class), null, null, null, null),
            args);
    if (!items.isEmpty()) return new Batch("CONTRIBUTION", items);
    items =
        jdbc.query(
            "SELECT n.recipient_id,n.id FROM notification.notification n WHERE "
                + (own ? "n.recipient_id=?" : "n.target_type=? AND n.target_id=?")
                + " AND NOT EXISTS(SELECT 1 FROM notification.contribution c WHERE c.notification_id=n.id) AND NOT EXISTS(SELECT 1 FROM notification.source_state s WHERE s.notification_id=n.id) ORDER BY n.recipient_id,n.id LIMIT 100",
            (r, n) ->
                new Item(
                    r.getObject(1, UUID.class), r.getObject(2, UUID.class), null, null, null, null),
            own ? new Object[] {subject} : args);
    return new Batch("GROUP", items);
  }

  public void erase(Batch batch, Instant now) {
    for (var i : batch.items())
      switch (batch.table()) {
        case "SOURCE" ->
            jdbc.update(
                "UPDATE notification.source_state SET active=false,notification_id=NULL,activity_decided_version=GREATEST(COALESCE(activity_decided_version,0),latest_source_version),updated_at=? WHERE recipient_id=? AND kind=? AND source_type=? AND source_id=? AND actor_id=?",
                now.atOffset(ZoneOffset.UTC),
                i.recipient(),
                i.kind(),
                i.type(),
                i.source(),
                i.actor());
        case "CONTRIBUTION" ->
            jdbc.update("DELETE FROM notification.contribution WHERE id=?", i.id());
        case "GROUP" ->
            jdbc.update(
                "DELETE FROM notification.notification WHERE id=? AND NOT EXISTS(SELECT 1 FROM notification.contribution WHERE notification_id=?) AND NOT EXISTS(SELECT 1 FROM notification.source_state WHERE notification_id=?)",
                i.id(),
                i.id(),
                i.id());
        default -> throw new IllegalStateException("NOTIFICATION_ERASURE_TABLE_INVALID");
      }
    for (var recipient : batch.items().stream().map(Item::recipient).distinct().toList())
      jdbc.update(
          "UPDATE notification.recipient_state SET watermark=watermark+1,changed_at=? WHERE recipient_id=?",
          now.atOffset(ZoneOffset.UTC),
          recipient);
  }
}
