package com.flyvo.socialcore.content.infrastructure.persistence;

import com.flyvo.socialcore.content.api.Interactions;
import com.flyvo.socialcore.content.application.port.InteractionRepository;
import com.flyvo.socialcore.content.domain.comment.Comment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInteractions implements InteractionRepository {
  private final JdbcTemplate jdbc;
  private final tools.jackson.databind.ObjectMapper json;

  public JdbcInteractions(JdbcTemplate jdbc, tools.jackson.databind.ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static OffsetDateTime time(Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet r, String name) throws SQLException {
    var t = r.getObject(name, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static Comment comment(ResultSet r) throws SQLException {
    return new Comment(
        r.getObject("id", UUID.class),
        r.getObject("post_id", UUID.class),
        r.getObject("author_id", UUID.class),
        r.getObject("root_comment_id", UUID.class),
        r.getObject("reply_to_user_id", UUID.class),
        r.getString("body"),
        r.getString("status"),
        instant(r, "created_at"),
        instant(r, "edited_at"),
        instant(r, "deleted_at"),
        instant(r, "removed_at"),
        r.getObject("removal_action_id", UUID.class),
        r.getLong("version"));
  }

  public void lockPost(UUID id, boolean exclusive) {
    if (jdbc.queryForList(
            "SELECT id FROM content.post WHERE id=? FOR " + (exclusive ? "UPDATE" : "SHARE"), id)
        .isEmpty()) throw new com.flyvo.socialcore.platform.api.Failure(404, "NOT_FOUND");
  }

  public void lockComment(UUID id, boolean exclusive) {
    if (jdbc.queryForList(
            "SELECT id FROM content.comment WHERE id=? FOR " + (exclusive ? "UPDATE" : "SHARE"), id)
        .isEmpty()) throw new com.flyvo.socialcore.platform.api.Failure(404, "NOT_FOUND");
  }

  public Comment find(UUID id, boolean exclusive) {
    if (exclusive) lockComment(id, true);
    var rows = jdbc.query("SELECT * FROM content.comment WHERE id=?", (r, n) -> comment(r), id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void insert(Comment c) {
    jdbc.update(
        "INSERT INTO content.comment(id,post_id,author_id,root_comment_id,reply_to_user_id,body,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'VISIBLE',?,?)",
        c.id(),
        c.post(),
        c.author(),
        c.root(),
        c.replyTo(),
        c.body(),
        time(c.created()),
        time(c.created()));
  }

  public void save(Comment c, Instant now) {
    jdbc.update(
        "UPDATE content.comment SET body=?,status=?,edited_at=?,deleted_at=?,removed_at=?,removal_action_id=?,version=?,updated_at=? WHERE id=?",
        c.body(),
        c.status(),
        time(c.edited()),
        time(c.deleted()),
        time(c.removed()),
        c.removalAction(),
        c.version(),
        time(now),
        c.id());
  }

  public void redactDeleted(UUID id, Instant cutoff, Instant now) {
    jdbc.update(
        "UPDATE content.comment SET body='',updated_at=? WHERE id=? AND status='DELETED' AND deleted_at<=?",
        time(now),
        id,
        time(cutoff));
  }

  public List<Comment> redactForDeletedPost(UUID post, Instant cutoff, Instant now) {
    return jdbc.query(
        """
      WITH old AS (
        SELECT c.id,p.deleted_at parent_deleted FROM content.comment c JOIN content.post p ON p.id=c.post_id
        WHERE c.post_id=? AND p.status='DELETED' AND p.deleted_at<=? AND c.body<>''
        ORDER BY c.id LIMIT 500 FOR UPDATE OF c SKIP LOCKED
      ) UPDATE content.comment c SET body='',status='DELETED',deleted_at=COALESCE(c.deleted_at,old.parent_deleted),
        updated_at=?,version=c.version+1 FROM old WHERE c.id=old.id RETURNING c.*
      """,
        (r, n) -> comment(r),
        post,
        time(cutoff),
        time(now));
  }

  // This predicate is evaluated in the same statement snapshot as all returned text.
  private static String actorVisible(String actor) {
    return "EXISTS(SELECT 1 FROM account_api.active_users_v1 a WHERE a.user_id="
        + actor
        + ") AND NOT EXISTS(SELECT 1 FROM graph_api.blocks_v1 b WHERE (b.blocker_id=v.id AND b.blocked_id="
        + actor
        + ") OR (b.blocked_id=v.id AND b.blocker_id="
        + actor
        + "))";
  }

  private static String eligible() {
    return "WITH eligible AS (SELECT * FROM ("
        + JdbcPosts.FACTS
        + ") facts WHERE "
        + JdbcPosts.ELIGIBLE
        + "), viewer AS (SELECT ?::uuid id) ";
  }

  private static ArrayList<Object> args(UUID viewer, Instant now) {
    return new ArrayList<>(
        Arrays.asList(
            viewer, time(now), viewer, viewer, time(now), viewer, time(now), viewer, viewer,
            viewer));
  }

  public List<Row> comments(
      UUID viewer,
      UUID post,
      UUID root,
      UUID single,
      int limit,
      Instant asOf,
      Key after,
      Instant now) {
    var args = args(viewer, now);
    args.addAll(Arrays.asList(post, root, root, single, single, time(asOf)));
    String seek = "";
    if (after != null) {
      seek = " AND (c.created_at,c.id)>(?,?)";
      args.add(time(after.time()));
      args.add(after.id());
    }
    args.add(limit);
    return jdbc.query(
        eligible()
            + "SELECT c.*, (c.status='VISIBLE' AND "
            + actorVisible("c.author_id")
            + ") visible, "
            + "EXISTS(SELECT 1 FROM content.comment child WHERE child.root_comment_id=c.id AND child.status='VISIBLE' AND "
            + actorVisible("child.author_id")
            + ") children, "
            + "("
            + actorVisible("c.reply_to_user_id")
            + ") reply_visible "
            + "FROM content.comment c JOIN eligible e ON e.id=c.post_id CROSS JOIN viewer v WHERE c.post_id=? AND "
            + "((?::uuid IS NULL AND c.root_comment_id IS NULL) OR c.root_comment_id=?) AND (?::uuid IS NULL OR c.id=?) AND c.created_at<=?"
            + seek
            + " ORDER BY c.created_at,c.id LIMIT ?",
        (r, n) ->
            new Row(
                comment(r),
                r.getBoolean("visible"),
                r.getBoolean("children"),
                r.getBoolean("reply_visible")),
        args.toArray());
  }

  private static Reaction reaction(ResultSet r) throws SQLException {
    return new Reaction(
        r.getObject("id", UUID.class),
        r.getObject("actor_id", UUID.class),
        Interactions.ReactionType.valueOf(r.getString("type")),
        r.getBoolean("active"),
        r.getLong("version"));
  }

  public Reaction reaction(UUID actor, UUID post, UUID comment) {
    var rows =
        jdbc.query(
            "SELECT * FROM content.reaction WHERE actor_id=? AND "
                + (comment == null ? "post_id" : "comment_id")
                + "=?",
            (r, n) -> reaction(r),
            actor,
            comment == null ? post : comment);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Reaction setReaction(
      UUID actor,
      UUID post,
      UUID comment,
      Interactions.ReactionType type,
      boolean active,
      Instant now) {
    var previous = reaction(actor, post, comment);
    if (previous == null) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO content.reaction(id,actor_id,post_id,comment_id,type,active,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
          id,
          actor,
          comment == null ? post : null,
          comment,
          type.name(),
          active,
          time(now),
          time(now));
      return new Reaction(id, actor, type, active, 0);
    }
    jdbc.update(
        "UPDATE content.reaction SET type=?,active=?,version=version+1,updated_at=? WHERE id=?",
        type.name(),
        active,
        time(now),
        previous.id());
    return new Reaction(previous.id(), actor, type, active, previous.version() + 1);
  }

  private static String source(boolean comment) {
    return "FROM content.reaction r "
        + (comment
            ? "JOIN content.comment c ON c.id=r.comment_id JOIN eligible e ON e.id=c.post_id "
            : "JOIN eligible e ON e.id=r.post_id ")
        + "CROSS JOIN viewer v WHERE r.active AND "
        + actorVisible("r.actor_id")
        + (comment ? " AND c.status='VISIBLE' AND " + actorVisible("c.author_id") : "");
  }

  public List<Reaction> actors(
      UUID viewer, UUID post, UUID comment, int limit, UUID after, Instant now) {
    var args = args(viewer, now);
    args.add(comment == null ? post : comment);
    args.add(after);
    args.add(after);
    args.add(limit);
    return jdbc.query(
        eligible()
            + "SELECT r.* "
            + source(comment != null)
            + " AND "
            + (comment == null ? "r.post_id" : "r.comment_id")
            + "=? AND (?::uuid IS NULL OR r.actor_id>?) ORDER BY r.actor_id LIMIT ?",
        (r, n) -> reaction(r),
        args.toArray());
  }

  public Map<Interactions.ReactionType, Long> counts(
      UUID viewer, UUID post, UUID comment, Instant now) {
    var args = args(viewer, now);
    args.add(comment == null ? post : comment);
    var result = new EnumMap<Interactions.ReactionType, Long>(Interactions.ReactionType.class);
    jdbc.query(
        eligible()
            + "SELECT r.type,count(*) amount "
            + source(comment != null)
            + " AND "
            + (comment == null ? "r.post_id" : "r.comment_id")
            + "=? GROUP BY r.type",
        r -> {
          result.put(Interactions.ReactionType.valueOf(r.getString("type")), r.getLong("amount"));
        },
        args.toArray());
    return Map.copyOf(result);
  }

  public Map<UUID, Interactions.Engagement> engagement(UUID viewer, List<UUID> posts, Instant now) {
    if (posts.isEmpty()) return Map.of();
    var args = args(viewer, now);
    args.addAll(posts);
    var result = new HashMap<UUID, Interactions.Engagement>();
    jdbc.query(
        eligible()
            + "SELECT e.id, (SELECT count(*) FROM content.comment c WHERE c.post_id=e.id AND c.status='VISIBLE' AND "
            + actorVisible("c.author_id")
            + ") comments, "
            + "COALESCE((SELECT jsonb_object_agg(t.type,t.amount) FROM (SELECT r.type,count(*) amount FROM content.reaction r WHERE r.post_id=e.id AND r.active AND "
            + actorVisible("r.actor_id")
            + " GROUP BY r.type) t),'{}'::jsonb) reactions "
            + "FROM eligible e CROSS JOIN viewer v WHERE e.id IN ("
            + String.join(",", Collections.nCopies(posts.size(), "?"))
            + ")",
        r -> {
          var counts =
              new EnumMap<Interactions.ReactionType, Long>(Interactions.ReactionType.class);
          var parsed = json.readTree(r.getString("reactions"));
          for (var type : Interactions.ReactionType.values())
            if (parsed.has(type.name())) counts.put(type, parsed.get(type.name()).asLong());
          result.put(
              r.getObject("id", UUID.class),
              new Interactions.Engagement(r.getLong("comments"), Map.copyOf(counts)));
        },
        args.toArray());
    return Map.copyOf(result);
  }
}
