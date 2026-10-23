package com.flyvo.socialcore.community.infrastructure.persistence;

import com.flyvo.socialcore.community.application.port.CommunityErasureStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommunityErasureStore implements CommunityErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcCommunityErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean ownsActive(UUID subject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM community.community WHERE owner_user_id=? AND status='ACTIVE')",
            Boolean.class,
            subject));
  }

  private static Item item(ResultSet r, int n) throws SQLException {
    return new Item(
        r.getString(1),
        r.getObject(2, UUID.class),
        r.getObject(3, UUID.class),
        r.getObject(4, UUID.class));
  }

  public List<Item> pending(UUID subject, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid erasure page");
    return jdbc.query(
        "SELECT kind,id,community_id,other_user FROM ("
            + "SELECT 'MEMBER' AS kind,m.community_id AS id,m.community_id,NULL::uuid AS other_user "
            + "FROM community.membership m JOIN community.community c ON c.id=m.community_id "
            + "WHERE m.user_id=? AND m.status='ACTIVE' AND NOT (c.status='ARCHIVED' AND c.owner_user_id=?) "
            + "UNION ALL SELECT 'INVITE',i.id,i.community_id,CASE WHEN i.invitee_id=? THEN i.inviter_id ELSE i.invitee_id END "
            + "FROM community.invitation i WHERE (i.invitee_id=? OR i.inviter_id=?) AND i.status='PENDING' "
            + "UNION ALL SELECT 'REQUEST',r.id,r.community_id,NULL::uuid "
            + "FROM community.join_request r WHERE r.user_id=? AND (r.status='PENDING' OR r.message IS NOT NULL)"
            + ") work ORDER BY kind,id LIMIT ?",
        JdbcCommunityErasureStore::item,
        subject,
        subject,
        subject,
        subject,
        subject,
        subject,
        limit);
  }

  public void guardCommunities(List<UUID> ids) {
    for (UUID id : ids) {
      var found =
          jdbc.queryForList(
              "SELECT id FROM community.community WHERE id=? FOR UPDATE", UUID.class, id);
      if (found.isEmpty()) throw new IllegalStateException("COMMUNITY_ERASURE_ROOT_MISSING");
    }
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  public boolean erase(UUID subject, Item item, Instant now) {
    int changed =
        switch (item.kind()) {
          case "MEMBER" ->
              jdbc.update(
                  "UPDATE community.membership m SET status='LEFT',left_at=?,suspended_until=NULL,updated_at=?,version=version+1 "
                      + "WHERE m.community_id=? AND m.user_id=? AND m.status='ACTIVE' "
                      + "AND NOT EXISTS(SELECT 1 FROM community.community c WHERE c.id=m.community_id "
                      + "AND c.owner_user_id=? AND c.status='ACTIVE') "
                      + "AND NOT EXISTS(SELECT 1 FROM community.community c WHERE c.id=m.community_id "
                      + "AND c.owner_user_id=? AND c.status='ARCHIVED')",
                  time(now),
                  time(now),
                  item.communityId(),
                  subject,
                  subject,
                  subject);
          case "INVITE" ->
              jdbc.update(
                  "UPDATE community.invitation SET status='REVOKED',responded_at=?,updated_at=?,version=version+1 "
                      + "WHERE id=? AND community_id=? AND status='PENDING' AND (invitee_id=? OR inviter_id=?)",
                  time(now),
                  time(now),
                  item.id(),
                  item.communityId(),
                  subject,
                  subject);
          case "REQUEST" ->
              jdbc.update(
                  "UPDATE community.join_request SET status=CASE WHEN status='PENDING' THEN 'CANCELLED' ELSE status END,"
                      + "message=NULL,decided_at=CASE WHEN status='PENDING' THEN ? ELSE decided_at END,"
                      + "decided_by=CASE WHEN status='PENDING' THEN ? ELSE decided_by END,updated_at=?,version=version+1 "
                      + "WHERE id=? AND community_id=? AND user_id=? AND (status='PENDING' OR message IS NOT NULL)",
                  time(now),
                  subject,
                  time(now),
                  item.id(),
                  item.communityId(),
                  subject);
          default -> throw new IllegalStateException("UNKNOWN_COMMUNITY_ERASURE_ITEM");
        };
    return changed == 1;
  }
}
