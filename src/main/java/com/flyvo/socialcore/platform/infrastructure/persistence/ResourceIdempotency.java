package com.flyvo.socialcore.platform.infrastructure.persistence;

import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@Transactional(propagation = Propagation.MANDATORY)
class ResourceIdempotency implements Idempotency {
  private final JdbcTemplate jdbc;
  private final Secrets secrets;
  private final Clock clock;

  ResourceIdempotency(JdbcTemplate jdbc, Secrets secrets, Clock clock) {
    this.jdbc = jdbc;
    this.secrets = secrets;
    this.clock = clock;
  }

  public UUID claim(UUID actor, String route, String key, String request) {
    if (key == null) return null;
    validate(key);
    var now = DurableWorkStore.time(clock.instant());
    // One statement locks/inserts/renews and returns the current record. A separate
    // INSERT/SELECT can lose an expired conflicting row to cleanup between statements.
    // Live replays preserve every logical field, including the original expiry.
    var row =
        jdbc.queryForMap(
            """
            INSERT INTO platform.idempotency_record AS current_record
              (actor_id,route_key,key_hash,request_hash,expires_at,created_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (actor_id,route_key,key_hash) DO UPDATE SET
              request_hash=CASE WHEN current_record.expires_at<=EXCLUDED.created_at
                THEN EXCLUDED.request_hash ELSE current_record.request_hash END,
              response_reference=CASE WHEN current_record.expires_at<=EXCLUDED.created_at
                THEN NULL ELSE current_record.response_reference END,
              created_at=CASE WHEN current_record.expires_at<=EXCLUDED.created_at
                THEN EXCLUDED.created_at ELSE current_record.created_at END,
              expires_at=CASE WHEN current_record.expires_at<=EXCLUDED.created_at
                THEN EXCLUDED.expires_at ELSE current_record.expires_at END
            RETURNING request_hash,response_reference
            """,
            actor,
            route,
            secrets.hash(key),
            secrets.hash(request),
            DurableWorkStore.time(now.toInstant().plusSeconds(86400)),
            now);
    if (!Arrays.equals((byte[]) row.get("request_hash"), secrets.hash(request)))
      throw new Failure(409, "IDEMPOTENCY_CONFLICT");
    return (UUID) row.get("response_reference");
  }

  public void complete(UUID actor, String route, String key, UUID reference) {
    if (key != null)
      jdbc.update(
          "UPDATE platform.idempotency_record SET response_reference=? WHERE actor_id=? AND route_key=? AND key_hash=?",
          reference,
          actor,
          route,
          secrets.hash(key));
  }

  private void validate(String key) {
    if (!key.matches("[\\x21-\\x7e]{1,128}")) throw Failure.invalid();
  }
}
