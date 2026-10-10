package com.flyvo.socialcore.graph.infrastructure.persistence;

import com.flyvo.socialcore.graph.api.RelationshipQueries.Facts;
import com.flyvo.socialcore.graph.application.port.GraphRepository;
import com.flyvo.socialcore.graph.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGraphRepository implements GraphRepository {
  private final JdbcTemplate jdbc;

  public JdbcGraphRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime time(Instant t) {
    return t.atOffset(ZoneOffset.UTC);
  }

  private static final RowMapper<FriendRequest> REQUEST =
      (r, n) ->
          new FriendRequest(
              r.getObject("id", UUID.class),
              r.getObject("requester_id", UUID.class),
              r.getObject("addressee_id", UUID.class),
              r.getString("status"),
              r.getObject("created_at", OffsetDateTime.class).toInstant(),
              r.getObject("expires_at", OffsetDateTime.class).toInstant(),
              r.getLong("version"));
  private static final String FACTS =
      """
      EXISTS(SELECT 1 FROM graph.follow WHERE follower_id=a AND followed_id=b) AS following,
      EXISTS(SELECT 1 FROM graph.follow WHERE follower_id=b AND followed_id=a) AS followed_by,
      EXISTS(SELECT 1 FROM graph.friendship WHERE low_user_id=LEAST(a,b) AND high_user_id=GREATEST(a,b)) AS friends,
      EXISTS(SELECT 1 FROM graph.block WHERE blocker_id=a AND blocked_id=b) AS forward_block,
      EXISTS(SELECT 1 FROM graph.block WHERE blocker_id=b AND blocked_id=a) AS reverse_block,
      EXISTS(SELECT 1 FROM graph.mute WHERE muter_id=a AND muted_id=b) AS forward_mute,
      EXISTS(SELECT 1 FROM graph.mute WHERE muter_id=b AND muted_id=a) AS reverse_mute
      """;

  public void guard(long key, boolean exclusive) {
    jdbc.queryForList(
        exclusive ? "SELECT pg_advisory_xact_lock(?)" : "SELECT pg_advisory_xact_lock_shared(?)",
        key);
  }

  public State state(UUID a, UUID b) {
    return jdbc.queryForObject(
        "SELECT " + FACTS + " FROM (VALUES (?::uuid,?::uuid)) AS pair(a,b)",
        (r, n) ->
            new State(
                r.getBoolean(1),
                r.getBoolean(2),
                r.getBoolean(3),
                r.getBoolean(4),
                r.getBoolean(5),
                r.getBoolean(6),
                r.getBoolean(7)),
        a,
        b);
  }

  public List<Facts> facts(UUID viewer, List<UUID> users) {
    if (users.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    for (var u : users) {
      args.add(viewer);
      args.add(u);
    }
    return jdbc.query(
        "SELECT b,"
            + FACTS
            + " FROM (VALUES "
            + String.join(",", Collections.nCopies(users.size(), "(?::uuid,?::uuid)"))
            + ") AS pair(a,b)",
        (r, n) ->
            new Facts(
                r.getObject(1, UUID.class),
                r.getBoolean(2),
                r.getBoolean(3),
                r.getBoolean(4),
                r.getBoolean(5) || r.getBoolean(6),
                r.getBoolean(7)),
        args.toArray());
  }

  private record Edge(String table, String source, String target) {}

  private Edge edge(String kind) {
    return switch (kind) {
      case "following" -> new Edge("graph.follow", "follower_id", "followed_id");
      case "blocks" -> new Edge("graph.block", "blocker_id", "blocked_id");
      case "mutes" -> new Edge("graph.mute", "muter_id", "muted_id");
      default -> throw new IllegalArgumentException("Unknown edge");
    };
  }

  public boolean edge(String kind, UUID a, UUID b, boolean enabled, Instant now) {
    var e = edge(kind);
    return enabled
        ? jdbc.update(
                "INSERT INTO "
                    + e.table()
                    + "("
                    + e.source()
                    + ","
                    + e.target()
                    + ",created_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                a,
                b,
                time(now))
            > 0
        : jdbc.update(
                "DELETE FROM " + e.table() + " WHERE " + e.source() + "=? AND " + e.target() + "=?",
                a,
                b)
            > 0;
  }

  public boolean unfriend(UserPair p) {
    return jdbc.update(
            "DELETE FROM graph.friendship WHERE low_user_id=? AND high_user_id=?",
            p.low(),
            p.high())
        > 0;
  }

  public void befriend(FriendRequest r, Instant now) {
    jdbc.update(
        "INSERT INTO graph.friendship(low_user_id,high_user_id,established_at,request_id) VALUES (?,?,?,?)",
        r.pair().low(),
        r.pair().high(),
        time(now),
        r.id());
  }

  public FriendRequest request(UUID id) {
    var rows = jdbc.query("SELECT * FROM graph.friend_request WHERE id=?", REQUEST, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public FriendRequest pending(UserPair p) {
    var rows =
        jdbc.query(
            "SELECT * FROM graph.friend_request WHERE low_user_id=? AND high_user_id=? AND status='PENDING'",
            REQUEST,
            p.low(),
            p.high());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void insert(FriendRequest r) {
    jdbc.update(
        "INSERT INTO graph.friend_request(id,requester_id,addressee_id,status,created_at,expires_at) VALUES (?,?,?,'PENDING',?,?)",
        r.id(),
        r.requester(),
        r.addressee(),
        time(r.createdAt()),
        time(r.expiresAt()));
  }

  public void resolve(UUID id, String status, Instant now) {
    jdbc.update(
        "UPDATE graph.friend_request SET status=?,resolved_at=?,version=version+1 WHERE id=? AND status='PENDING'",
        status,
        time(now),
        id);
  }

  public Revision bump(UserPair p) {
    return jdbc.queryForObject(
        "INSERT INTO graph.pair_revision(id,low_user_id,high_user_id,version) VALUES (?,?,?,1) ON CONFLICT(low_user_id,high_user_id) DO UPDATE SET version=graph.pair_revision.version+1 RETURNING id,version",
        (r, n) -> new Revision(r.getObject(1, UUID.class), r.getLong(2)),
        UUID.randomUUID(),
        p.low(),
        p.high());
  }

  public List<UUID> edges(UUID viewer, UUID owner, String kind, int size, UUID after) {
    String candidates =
        switch (kind) {
          case "following" -> "SELECT followed_id AS id FROM graph.follow WHERE follower_id=?";
          case "followers" -> "SELECT follower_id AS id FROM graph.follow WHERE followed_id=?";
          case "friends" ->
              "SELECT CASE WHEN low_user_id=? THEN high_user_id ELSE low_user_id END AS id FROM graph.friendship WHERE ? IN (low_user_id,high_user_id)";
          case "blocks" -> "SELECT blocked_id AS id FROM graph.block WHERE blocker_id=?";
          case "mutes" -> "SELECT muted_id AS id FROM graph.mute WHERE muter_id=?";
          default -> throw new IllegalArgumentException("Unknown list");
        };
    var args = new ArrayList<Object>();
    args.add(owner);
    if (kind.equals("friends")) args.add(owner);
    String filter = "";
    if (!kind.equals("blocks")) {
      filter =
          " AND NOT EXISTS(SELECT 1 FROM graph.block WHERE (blocker_id=? AND blocked_id=c.id) OR (blocker_id=c.id AND blocked_id=?))";
      args.add(viewer);
      args.add(viewer);
    }
    if (after != null) {
      filter += " AND c.id>?";
      args.add(after);
    }
    args.add(size);
    return jdbc.query(
        "SELECT c.id FROM (" + candidates + ") c WHERE true" + filter + " ORDER BY c.id LIMIT ?",
        (r, n) -> r.getObject(1, UUID.class),
        args.toArray());
  }

  public List<FriendRequest> requests(
      UUID actor, String direction, int size, UUID after, Instant now) {
    String column = direction.equals("incoming") ? "addressee_id" : "requester_id";
    var args = new ArrayList<Object>(List.of(actor, time(now)));
    String filter = "";
    if (after != null) {
      filter = " AND r.id>?";
      args.add(after);
    }
    args.add(size);
    return jdbc.query(
        "SELECT r.* FROM graph.friend_request r WHERE "
            + column
            + "=? AND status='PENDING' AND expires_at>? AND NOT EXISTS(SELECT 1 FROM graph.block WHERE (blocker_id=r.requester_id AND blocked_id=r.addressee_id) OR (blocker_id=r.addressee_id AND blocked_id=r.requester_id))"
            + filter
            + " ORDER BY r.id LIMIT ?",
        REQUEST,
        args.toArray());
  }
}
