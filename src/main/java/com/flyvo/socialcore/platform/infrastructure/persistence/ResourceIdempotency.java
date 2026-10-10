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
    jdbc.update(
        "INSERT INTO platform.idempotency_record(actor_id,route_key,key_hash,request_hash,expires_at,created_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        actor,
        route,
        secrets.hash(key),
        secrets.hash(request),
        DurableWorkStore.time(clock.instant().plusSeconds(86400)),
        now);
    var row =
        jdbc.queryForMap(
            "SELECT request_hash,response_reference,expires_at FROM platform.idempotency_record WHERE actor_id=? AND route_key=? AND key_hash=? FOR UPDATE",
            actor,
            route,
            secrets.hash(key));
    if (!clock.instant().isBefore(((java.sql.Timestamp) row.get("expires_at")).toInstant())) {
      jdbc.update(
          "UPDATE platform.idempotency_record SET request_hash=?,response_reference=NULL,created_at=?,expires_at=? WHERE actor_id=? AND route_key=? AND key_hash=?",
          secrets.hash(request),
          now,
          DurableWorkStore.time(clock.instant().plusSeconds(86400)),
          actor,
          route,
          secrets.hash(key));
      return null;
    }
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
