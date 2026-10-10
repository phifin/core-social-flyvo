package com.flyvo.socialcore.community.infrastructure.persistence;

import com.flyvo.socialcore.community.application.port.CommunityRepository;
import com.flyvo.socialcore.community.domain.CommunityState.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommunityRepository implements CommunityRepository {
  private final JdbcTemplate jdbc;

  public JdbcCommunityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String name) throws SQLException {
    var value = rs.getObject(name, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static final RowMapper<Community> COMMUNITY =
      (rs, n) ->
          new Community(
              rs.getObject("id", UUID.class),
              rs.getString("slug_normalized"),
              rs.getString("name"),
              rs.getString("description"),
              rs.getString("visibility"),
              rs.getString("join_mode"),
              rs.getString("status"),
              rs.getObject("owner_user_id", UUID.class),
              instant(rs, "created_at"),
              rs.getLong("version"));
  private static final RowMapper<Member> MEMBER =
      (rs, n) ->
          new Member(
              rs.getObject("community_id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getString("role"),
              rs.getString("status"),
              instant(rs, "joined_at"),
              instant(rs, "suspended_until"),
              rs.getLong("version"));
  private static final RowMapper<Invitation> INVITATION =
      (rs, n) ->
          new Invitation(
              rs.getObject("id", UUID.class),
              rs.getObject("community_id", UUID.class),
              rs.getObject("invitee_id", UUID.class),
              rs.getObject("inviter_id", UUID.class),
              rs.getString("status"),
              instant(rs, "expires_at"),
              instant(rs, "created_at"),
              rs.getLong("version"));
  private static final RowMapper<Request> REQUEST =
      (rs, n) ->
          new Request(
              rs.getObject("id", UUID.class),
              rs.getObject("community_id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getString("status"),
              rs.getString("message"),
              instant(rs, "created_at"),
              rs.getLong("version"));

  private <T> T one(String sql, RowMapper<T> mapper, Object... args) {
    var rows = jdbc.query(sql, mapper, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Community community(UUID id, boolean lock, boolean exclusive) {
    return one(
        "SELECT * FROM community.community WHERE id=?"
            + (lock ? (exclusive ? " FOR UPDATE" : " FOR SHARE") : ""),
        COMMUNITY,
        id);
  }

  public List<Community> communities(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.query(
        "SELECT * FROM community.community WHERE id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
            + ")",
        COMMUNITY,
        ids.toArray());
  }

  public void create(Community c, Instant now) {
    jdbc.update(
        "INSERT INTO community.community(id,slug_normalized,name,description,visibility,join_mode,status,owner_user_id,created_at,updated_at) VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?)",
        c.id(),
        c.slug(),
        c.name(),
        c.description(),
        c.visibility(),
        c.joinMode(),
        c.ownerId(),
        time(now),
        time(now));
    join(c.id(), c.ownerId(), now);
  }

  public void settings(
      UUID id, String name, String description, String visibility, String joinMode, Instant now) {
    jdbc.update(
        "UPDATE community.community SET name=?,description=?,visibility=?,join_mode=?,version=version+1,updated_at=? WHERE id=?",
        name,
        description,
        visibility,
        joinMode,
        time(now),
        id);
  }

  public void archive(UUID id, Instant now) {
    jdbc.update(
        "UPDATE community.community SET status='ARCHIVED',version=version+1,updated_at=? WHERE id=?",
        time(now),
        id);
  }

  public void transfer(UUID id, UUID successor, Instant now) {
    jdbc.update(
        "UPDATE community.community SET owner_user_id=?,version=version+1,updated_at=? WHERE id=?",
        successor,
        time(now),
        id);
  }

  public boolean ownsActive(UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM community.community WHERE owner_user_id=? AND status='ACTIVE')",
            Boolean.class,
            user));
  }

  public List<Community> discover(boolean authenticated, int size, int offset) {
    return jdbc.query(
        "SELECT * FROM community.community WHERE (? OR visibility='PUBLIC') ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        COMMUNITY,
        authenticated,
        size,
        offset);
  }

  public Member member(UUID c, UUID u) {
    return one(
        "SELECT * FROM community.membership WHERE community_id=? AND user_id=?", MEMBER, c, u);
  }

  public List<Member> members(UUID c, int size, int offset) {
    return jdbc.query(
        "SELECT * FROM community.membership WHERE community_id=? ORDER BY user_id LIMIT ? OFFSET ?",
        MEMBER,
        c,
        size,
        offset);
  }

  public List<Member> facts(UUID user, List<UUID> communities) {
    if (communities.isEmpty()) return List.of();
    var args = new java.util.ArrayList<Object>();
    args.add(user);
    args.addAll(communities);
    return jdbc.query(
        "SELECT * FROM community.membership WHERE user_id=? AND community_id IN ("
            + String.join(",", java.util.Collections.nCopies(communities.size(), "?"))
            + ")",
        MEMBER,
        args.toArray());
  }

  public void join(UUID c, UUID u, Instant now) {
    jdbc.update(
        "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at) VALUES (?,?,'MEMBER','ACTIVE',?,?) ON CONFLICT(community_id,user_id) DO UPDATE SET role='MEMBER',status='ACTIVE',joined_at=excluded.joined_at,left_at=NULL,suspended_until=NULL,version=community.membership.version+1,updated_at=excluded.updated_at",
        c,
        u,
        time(now),
        time(now));
  }

  public void membership(
      UUID c, UUID u, String role, String status, Instant suspension, Instant now) {
    jdbc.update(
        "UPDATE community.membership SET role=?,status=?,suspended_until=?,left_at=?,updated_at=?,version=version+1 WHERE community_id=? AND user_id=?",
        role,
        status,
        time(suspension),
        "ACTIVE".equals(status) ? null : time(now),
        time(now),
        c,
        u);
  }

  public Invitation invitation(UUID id) {
    return one("SELECT * FROM community.invitation WHERE id=?", INVITATION, id);
  }

  public Invitation pendingInvitation(UUID c, UUID u) {
    return one(
        "SELECT * FROM community.invitation WHERE community_id=? AND invitee_id=? AND status='PENDING'",
        INVITATION,
        c,
        u);
  }

  public void invite(Invitation i) {
    jdbc.update(
        "INSERT INTO community.invitation(id,community_id,invitee_id,inviter_id,status,expires_at,created_at,updated_at) VALUES (?,?,?,?,'PENDING',?,?,?)",
        i.id(),
        i.communityId(),
        i.inviteeId(),
        i.inviterId(),
        time(i.expiresAt()),
        time(i.createdAt()),
        time(i.createdAt()));
  }

  public void resolveInvite(UUID id, String status, Instant now) {
    jdbc.update(
        "UPDATE community.invitation SET status=?,responded_at=?,updated_at=?,version=version+1 WHERE id=? AND status='PENDING'",
        status,
        time(now),
        time(now),
        id);
  }

  public List<Invitation> invitations(UUID user, Instant now, int size, int offset) {
    return jdbc.query(
        "SELECT * FROM community.invitation WHERE invitee_id=? AND status='PENDING' AND expires_at>? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        INVITATION,
        user,
        time(now),
        size,
        offset);
  }

  public Request request(UUID id) {
    return one("SELECT * FROM community.join_request WHERE id=?", REQUEST, id);
  }

  public Request pendingRequest(UUID c, UUID u) {
    return one(
        "SELECT * FROM community.join_request WHERE community_id=? AND user_id=? AND status='PENDING'",
        REQUEST,
        c,
        u);
  }

  public void request(Request r) {
    jdbc.update(
        "INSERT INTO community.join_request(id,community_id,user_id,status,message,created_at,updated_at) VALUES (?,?,?,'PENDING',?,?,?)",
        r.id(),
        r.communityId(),
        r.userId(),
        r.message(),
        time(r.createdAt()),
        time(r.createdAt()));
  }

  public void resolveRequest(UUID id, String status, UUID actor, Instant now) {
    jdbc.update(
        "UPDATE community.join_request SET status=?,decided_by=?,decided_at=?,updated_at=?,version=version+1 WHERE id=? AND status='PENDING'",
        status,
        actor,
        time(now),
        time(now),
        id);
  }

  public List<Request> requests(UUID c, int size, int offset) {
    return jdbc.query(
        "SELECT * FROM community.join_request WHERE community_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        REQUEST,
        c,
        size,
        offset);
  }
}
