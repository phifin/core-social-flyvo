package com.flyvo.socialcore.notification.infrastructure.persistence;

import com.flyvo.socialcore.notification.api.NotificationQueries.Kind;
import com.flyvo.socialcore.notification.application.port.InboxRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInbox implements InboxRepository {
  private final JdbcTemplate jdbc;

  public JdbcInbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime at(Instant t) {
    return t.atOffset(ZoneOffset.UTC);
  }

  public void guard(UUID recipient, Instant now) {
    jdbc.update(
        "INSERT INTO notification.recipient_state(recipient_id,changed_at) VALUES (?,?) ON CONFLICT DO NOTHING",
        recipient,
        at(now));
    jdbc.queryForList(
        "SELECT recipient_id FROM notification.recipient_state WHERE recipient_id=? FOR UPDATE",
        recipient);
  }

  private void changed(UUID recipient, Instant now) {
    jdbc.update(
        "UPDATE notification.recipient_state SET watermark=watermark+1,changed_at=GREATEST(changed_at,?) WHERE recipient_id=?",
        at(now),
        recipient);
  }

  public boolean terminal(UUID recipient) {
    return jdbc
        .queryForList(
            "SELECT terminal FROM notification.recipient_state WHERE recipient_id=?",
            Boolean.class,
            recipient)
        .stream()
        .anyMatch(Boolean.TRUE::equals);
  }

  public boolean apply(Change c, Instant now) {
    if (terminal(c.recipient())) return false;
    var keyArgs =
        new Object[] {c.recipient(), c.kind().name(), c.sourceType(), c.sourceId(), c.actor()};
    var previous =
        jdbc.queryForList(
            "SELECT latest_source_version,activity_decided_version,notification_id FROM notification.source_state WHERE recipient_id=? AND kind=? AND source_type=? AND source_id=? AND actor_id=? FOR UPDATE",
            keyArgs);
    long ordered =
        previous.isEmpty()
            ? -1
            : ((Number) previous.getFirst().get("latest_source_version")).longValue();
    long decided =
        previous.isEmpty() || previous.getFirst().get("activity_decided_version") == null
            ? -1
            : ((Number) previous.getFirst().get("activity_decided_version")).longValue();
    boolean origin = c.newActivity() && c.sourceVersion() > decided;
    boolean closure = c.closesActivity() && c.sourceVersion() > decided;
    if (ordered >= c.currentSourceVersion() && !origin && !closure) return false;
    long observed = Math.max(ordered, c.currentSourceVersion());
    // An early update may advance ordering without deciding initial activity.
    // Only a real origin can materialize it; denial consumes the current snapshot.
    // Deletion closes its own epoch, not a newer activation already observed.
    if (!c.active() || origin) decided = observed;
    else if (closure) decided = Math.max(decided, c.sourceVersion());
    UUID old = previous.isEmpty() ? null : (UUID) previous.getFirst().get("notification_id");
    boolean activity = c.active() && origin;
    UUID current = old;
    if (!c.active() && old != null)
      jdbc.update(
          "UPDATE notification.contribution SET active=false,source_version=?,source_event_id=? WHERE notification_id=? AND source_type=? AND source_id=? AND actor_id=?",
          observed,
          c.eventId(),
          old,
          c.sourceType(),
          c.sourceId(),
          c.actor());
    if (c.active() && (old != null || activity)) {
      if (activity || old == null) {
        var bucket = now.truncatedTo(ChronoUnit.HOURS);
        String grouping =
            c.kind() == Kind.REACTION_ON_CONTENT
                ? c.kind() + ":" + c.targetType() + ":" + c.targetId() + ":" + bucket
                : c.kind() + ":" + c.eventId();
        var group =
            jdbc.queryForList(
                "SELECT id FROM notification.notification WHERE recipient_id=? AND grouping_key=?",
                c.recipient(),
                grouping);
        if (group.isEmpty()) {
          current = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO notification.notification(id,recipient_id,kind,target_type,target_id,grouping_key,group_window_start,activity_version,last_activity_at,created_at) VALUES (?,?,?,?,?,?,?,1,?,?)",
              current,
              c.recipient(),
              c.kind().name(),
              c.targetType(),
              c.targetId(),
              grouping,
              c.kind() == Kind.REACTION_ON_CONTENT ? at(bucket) : null,
              at(now),
              at(now));
        } else {
          current = (UUID) group.getFirst().get("id");
          jdbc.update(
              "UPDATE notification.notification SET activity_version=activity_version+1,last_activity_at=GREATEST(last_activity_at,?) WHERE id=?",
              at(now),
              current);
        }
        if (old != null && !old.equals(current))
          jdbc.update(
              "UPDATE notification.contribution SET active=false,source_version=? WHERE notification_id=? AND source_type=? AND source_id=? AND actor_id=?",
              observed,
              old,
              c.sourceType(),
              c.sourceId(),
              c.actor());
      }
      jdbc.update(
          "INSERT INTO notification.contribution(id,notification_id,source_event_id,actor_id,source_type,source_id,source_version,active,occurred_at,reaction_type) VALUES (?,?,?,?,?,?,?,true,?,?) ON CONFLICT(notification_id,source_type,source_id,actor_id) DO UPDATE SET source_event_id=EXCLUDED.source_event_id,source_version=EXCLUDED.source_version,active=true,reaction_type=EXCLUDED.reaction_type,occurred_at=EXCLUDED.occurred_at",
          UUID.randomUUID(),
          current,
          c.eventId(),
          c.actor(),
          c.sourceType(),
          c.sourceId(),
          observed,
          at(c.occurredAt()),
          c.reactionType());
    }
    jdbc.update(
        "INSERT INTO notification.source_state(recipient_id,kind,source_type,source_id,actor_id,latest_source_version,activity_decided_version,active,notification_id,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(recipient_id,kind,source_type,source_id,actor_id) DO UPDATE SET latest_source_version=EXCLUDED.latest_source_version,activity_decided_version=EXCLUDED.activity_decided_version,active=EXCLUDED.active,notification_id=EXCLUDED.notification_id,updated_at=EXCLUDED.updated_at",
        c.recipient(),
        c.kind().name(),
        c.sourceType(),
        c.sourceId(),
        c.actor(),
        observed,
        decided < 0 ? null : decided,
        c.active(),
        current,
        at(now));
    changed(c.recipient(), now);
    return true;
  }

  private Group group(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Group(
        r.getObject("id", UUID.class),
        r.getObject("recipient_id", UUID.class),
        Kind.valueOf(r.getString("kind")),
        r.getString("target_type"),
        r.getObject("target_id", UUID.class),
        r.getString("grouping_key"),
        r.getLong("activity_version"),
        r.getLong("read_version"),
        r.getObject("last_activity_at", OffsetDateTime.class).toInstant());
  }

  public List<Group> scan(UUID recipient, int limit, Instant cutoff, Key after, boolean unread) {
    var args =
        new ArrayList<Object>(
            Arrays.asList(recipient, at(cutoff), at(cutoff.minus(90, ChronoUnit.DAYS))));
    String seek = "";
    if (after != null) {
      seek = " AND (last_activity_at,id)<(?,?)";
      args.add(at(after.time()));
      args.add(after.id());
    }
    args.add(limit);
    return jdbc.query(
        "SELECT * FROM notification.notification WHERE recipient_id=? AND last_activity_at<=? AND last_activity_at>?"
            + (unread ? " AND read_version<activity_version" : "")
            + seek
            + " ORDER BY last_activity_at DESC,id DESC LIMIT ?",
        (r, n) -> group(r),
        args.toArray());
  }

  public Group find(UUID recipient, UUID id) {
    var rows =
        jdbc.query(
            "SELECT * FROM notification.notification WHERE recipient_id=? AND id=?",
            (r, n) -> group(r),
            recipient,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<Contribution> contributions(List<UUID> ids, int limit, UUID after) {
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>(ids);
    args.addAll(Arrays.asList(after, after, limit));
    return jdbc.query(
        "SELECT id,notification_id,actor_id,source_type,source_id,source_version FROM notification.contribution WHERE notification_id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ") AND active AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
        (r, n) ->
            new Contribution(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                r.getObject(3, UUID.class),
                r.getString(4),
                r.getObject(5, UUID.class),
                r.getLong(6)),
        args.toArray());
  }

  public void read(UUID recipient, UUID id, long observed) {
    int changed =
        jdbc.update(
            "UPDATE notification.notification SET read_version=GREATEST(read_version,LEAST(activity_version,?)) WHERE recipient_id=? AND id=? AND read_version<LEAST(activity_version,?)",
            observed,
            recipient,
            id,
            observed);
    if (changed == 1)
      jdbc.update(
          "UPDATE notification.recipient_state SET watermark=watermark+1 WHERE recipient_id=?",
          recipient);
  }

  public Map<Kind, Boolean> preferences(UUID recipient) {
    var map = new EnumMap<Kind, Boolean>(Kind.class);
    for (var k : Kind.values()) map.put(k, true);
    jdbc.query(
        "SELECT kind,in_app_enabled FROM notification.preference WHERE user_id=?",
        r -> {
          map.put(Kind.valueOf(r.getString(1)), r.getBoolean(2));
        },
        recipient);
    return Map.copyOf(map);
  }

  public void preferences(UUID recipient, Map<Kind, Boolean> changes, Instant now) {
    for (var c : changes.entrySet())
      jdbc.update(
          "INSERT INTO notification.preference(user_id,kind,in_app_enabled) VALUES (?,?,?) ON CONFLICT(user_id,kind) DO UPDATE SET in_app_enabled=EXCLUDED.in_app_enabled",
          recipient,
          c.getKey().name(),
          c.getValue());
    changed(recipient, now);
  }

  public long watermark(UUID recipient) {
    var rows =
        jdbc.queryForList(
            "SELECT watermark FROM notification.recipient_state WHERE recipient_id=?",
            Long.class,
            recipient);
    return rows.isEmpty() ? 0 : rows.getFirst();
  }

  public int prune(Instant now) {
    var recipients =
        jdbc.query(
            "SELECT DISTINCT recipient_id FROM notification.notification WHERE last_activity_at<? ORDER BY recipient_id LIMIT 20",
            (r, n) -> r.getObject(1, UUID.class),
            at(now.minus(90, ChronoUnit.DAYS)));
    int deleted = 0;
    for (var recipient : recipients) {
      guard(recipient, now);
      deleted +=
          jdbc.update(
              "DELETE FROM notification.notification WHERE id IN (SELECT id FROM notification.notification WHERE recipient_id=? AND last_activity_at<? ORDER BY last_activity_at,id LIMIT 100)",
              recipient,
              at(now.minus(90, ChronoUnit.DAYS)));
    }
    // Keep source versions indefinitely for active semantic sources. Only inactive
    // ordering older than inbox+replay horizons can be removed.
    jdbc.update(
        "DELETE FROM notification.source_state WHERE (recipient_id,kind,source_type,source_id,actor_id) IN (SELECT recipient_id,kind,source_type,source_id,actor_id FROM notification.source_state WHERE NOT active AND notification_id IS NULL AND updated_at<? ORDER BY updated_at,recipient_id LIMIT 100 FOR UPDATE SKIP LOCKED)",
        at(now.minus(120, ChronoUnit.DAYS)));
    return deleted;
  }
}
