package com.flyvo.socialcore.profile.infrastructure.persistence;

import com.flyvo.socialcore.profile.application.port.ProfileErasureStore;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileErasureStore implements ProfileErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcProfileErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Long erase(UUID user, Instant until) {
    var handles =
        jdbc.query(
            "SELECT handle_normalized FROM profile.profile WHERE user_id=? FOR UPDATE",
            (r, n) -> r.getString(1),
            user);
    if (!handles.isEmpty()) {
      var handle = handles.getFirst();
      jdbc.queryForObject(
          "SELECT pg_advisory_xact_lock(hashtextextended('profile.handle:'||?,0))",
          Object.class,
          handle);
      if (jdbc.update(
              "UPDATE profile.handle_claim SET state='RESERVED',user_id=NULL,expires_at=? WHERE handle_normalized=? AND state='LIVE' AND user_id=?",
              until.atOffset(ZoneOffset.UTC),
              handle,
              user)
          != 1) throw new IllegalStateException("HANDLE_CLAIM_MISMATCH");
    }
    var versions =
        jdbc.query(
            "INSERT INTO profile.erasure_tombstone(user_id,source_version) SELECT user_id,version+1 FROM profile.profile WHERE user_id=? RETURNING source_version",
            (r, n) -> r.getLong(1),
            user);
    jdbc.update("DELETE FROM profile.preference WHERE user_id=?", user);
    jdbc.update("DELETE FROM profile.profile WHERE user_id=?", user);
    return versions.isEmpty() ? null : versions.getFirst();
  }

  public int expireReservations() {
    // Do not lock claims before their advisory key; registrations lock in the opposite order.
    var handles =
        jdbc.query(
            "SELECT handle_normalized FROM profile.handle_claim WHERE state='RESERVED' AND expires_at<=clock_timestamp() ORDER BY handle_normalized LIMIT 100",
            (r, n) -> r.getString(1));
    int changed = 0;
    for (var handle : handles) {
      jdbc.queryForObject(
          "SELECT pg_advisory_xact_lock(hashtextextended('profile.handle:'||?,0))",
          Object.class,
          handle);
      changed +=
          jdbc.update(
              "DELETE FROM profile.handle_claim WHERE handle_normalized=? AND state='RESERVED' AND expires_at<=clock_timestamp()",
              handle);
    }
    return changed;
  }
}
