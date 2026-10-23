package com.flyvo.socialcore.graph.infrastructure.persistence;

import com.flyvo.socialcore.graph.application.port.GraphErasureStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGraphErasureStore implements GraphErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcGraphErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> counterparts(UUID subject, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid erasure page");
    return jdbc.queryForList(
        "SELECT other_user FROM ("
            + "SELECT followed_id AS other_user FROM graph.follow WHERE follower_id=? "
            + "UNION SELECT follower_id FROM graph.follow WHERE followed_id=? "
            + "UNION SELECT blocked_id FROM graph.block WHERE blocker_id=? "
            + "UNION SELECT blocker_id FROM graph.block WHERE blocked_id=? "
            + "UNION SELECT muted_id FROM graph.mute WHERE muter_id=? "
            + "UNION SELECT muter_id FROM graph.mute WHERE muted_id=? "
            + "UNION SELECT high_user_id FROM graph.friendship WHERE low_user_id=? "
            + "UNION SELECT low_user_id FROM graph.friendship WHERE high_user_id=? "
            + "UNION SELECT addressee_id FROM graph.friend_request WHERE requester_id=? AND status='PENDING' "
            + "UNION SELECT requester_id FROM graph.friend_request WHERE addressee_id=? AND status='PENDING'"
            + ") live ORDER BY other_user LIMIT ?",
        UUID.class,
        subject,
        subject,
        subject,
        subject,
        subject,
        subject,
        subject,
        subject,
        subject,
        subject,
        limit);
  }
}
