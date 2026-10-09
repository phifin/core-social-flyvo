package com.flyvo.socialcore.platform.infrastructure.persistence;

import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.EventConsumer;
import com.flyvo.socialcore.platform.api.Secrets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class DurableWorkStore implements DurableWork {
  private final JdbcTemplate jdbc;
  private final Secrets secrets;
  private final Clock clock;
  private final ObjectMapper json;
  private final List<EventConsumer> consumers;
  private final TransactionTemplate tx;

  public DurableWorkStore(
      JdbcTemplate jdbc,
      Secrets secrets,
      Clock clock,
      ObjectMapper json,
      List<EventConsumer> consumers,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.secrets = secrets;
    this.clock = clock;
    this.json = json;
    this.consumers = consumers;
    this.tx = new TransactionTemplate(manager);
  }

  public static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void mail(
      String dedupKey, String address, String template, String secret, Instant expiresAt) {
    var id = UUID.randomUUID();
    var now = time(clock.instant());
    int inserted =
        jdbc.update(
            "INSERT INTO platform.job(id,kind,dedup_key,payload,status,available_at,created_at) VALUES (?,'MAIL',?,?::jsonb,'PENDING',?,?) ON CONFLICT(kind,dedup_key) DO NOTHING",
            id,
            dedupKey,
            json.writeValueAsString(java.util.Map.of("payloadId", id, "template", template)),
            now,
            now);
    if (inserted == 1)
      jdbc.update(
          "INSERT INTO platform.secret_mail_payload(job_id,ciphertext,key_id,expires_at) VALUES (?,?,?,?)",
          id,
          secrets.encrypt(
              "mail:" + id, json.writeValueAsString(new Mail(address, template, secret))),
          secrets.keyId(),
          time(expiresAt));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void audit(
      UUID actor, String action, String targetType, UUID target, String outcome, String reason) {
    jdbc.update(
        "INSERT INTO platform.audit_entry(id,actor_id,action,target_type,target_id,outcome,reason_code,request_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        actor,
        action,
        targetType,
        target,
        outcome,
        reason,
        correlation(),
        time(clock.instant()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void event(
      String type,
      String aggregateType,
      UUID aggregateId,
      long version,
      int sequence,
      String payload) {
    var id = UUID.randomUUID();
    var now = time(clock.instant());
    jdbc.update(
        "INSERT INTO platform.outbox_event(id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,event_sequence,occurred_at,payload,correlation_id,created_at) VALUES (?,?,1,?,?,?,?,?,?::jsonb,?,?)",
        id,
        type,
        aggregateType,
        aggregateId,
        version,
        sequence,
        now,
        payload,
        correlation(),
        now);
    for (var consumer : consumers)
      if (consumer.accepts(type))
        jdbc.update(
            "INSERT INTO platform.outbox_delivery(event_id,consumer_name,status,available_at) VALUES (?,?,'PENDING',?)",
            id,
            consumer.name(),
            now);
  }

  private String correlation() {
    String id = MDC.get("requestId");
    return id == null ? UUID.randomUUID().toString() : id;
  }

  public record Mail(String address, String template, String secret) {
    @Override
    public String toString() {
      return "Mail[redacted]";
    }
  }

  public record Claim(
      UUID id, UUID owner, long generation, int attempts, byte[] ciphertext, Instant expiresAt) {}

  public Claim claim(UUID owner) {
    return tx.execute(
        status -> {
          var now = time(clock.instant());
          var ids =
              jdbc.query(
                  "SELECT id FROM platform.job WHERE ((status IN ('PENDING','RETRY') AND available_at<=?) OR (status='RUNNING' AND lease_until<=?)) AND attempts<10 ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                  (rs, n) -> rs.getObject(1, UUID.class),
                  now,
                  now);
          if (ids.isEmpty()) return null;
          var id = ids.getFirst();
          jdbc.update(
              "UPDATE platform.job SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_until=?,lease_generation=lease_generation+1 WHERE id=?",
              owner,
              time(clock.instant().plusSeconds(60)),
              id);
          return jdbc.queryForObject(
              "SELECT j.lease_generation,j.attempts,p.ciphertext,p.expires_at FROM platform.job j LEFT JOIN platform.secret_mail_payload p ON p.job_id=j.id WHERE j.id=?",
              (rs, n) ->
                  new Claim(
                      id,
                      owner,
                      rs.getLong(1),
                      rs.getInt(2),
                      rs.getBytes(3),
                      rs.getObject(4, OffsetDateTime.class) == null
                          ? null
                          : rs.getObject(4, OffsetDateTime.class).toInstant()),
              id);
        });
  }

  public Mail decode(Claim claim) {
    return json.readValue(secrets.decrypt("mail:" + claim.id(), claim.ciphertext()), Mail.class);
  }

  public boolean finish(Claim claim, String error) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              boolean dead =
                  error != null
                      && (claim.attempts() >= 10
                          || "PAYLOAD_EXPIRED".equals(error)
                          || "PAYLOAD_INVALID".equals(error));
              long delay = retryDelay(claim.attempts());
              int changed =
                  jdbc.update(
                      "UPDATE platform.job SET status=?,last_error_code=?,available_at=?,completed_at=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_generation=? AND lease_until>?",
                      error == null ? "COMPLETED" : dead ? "DEAD" : "RETRY",
                      error,
                      time(clock.instant().plusSeconds(delay)),
                      error == null ? time(clock.instant()) : null,
                      claim.id(),
                      claim.owner(),
                      claim.generation(),
                      time(clock.instant()));
              if (changed == 1 && (error == null || dead))
                jdbc.update("DELETE FROM platform.secret_mail_payload WHERE job_id=?", claim.id());
              return changed == 1;
            }));
  }

  public void consumeOne(UUID owner) {
    record Delivery(UUID id, String consumer, long generation, int attempts) {}
    var claim =
        tx.execute(
            status -> {
              var due =
                  jdbc.query(
                      "SELECT event_id,consumer_name,lease_generation,attempts FROM platform.outbox_delivery WHERE ((status IN ('PENDING','RETRY') AND available_at<=?) OR (status='RUNNING' AND lease_until<=?)) AND attempts<10 ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                      (rs, n) ->
                          new Delivery(
                              rs.getObject(1, UUID.class),
                              rs.getString(2),
                              rs.getLong(3) + 1,
                              rs.getInt(4) + 1),
                      time(clock.instant()),
                      time(clock.instant()));
              if (due.isEmpty()) return null;
              var item = due.getFirst();
              jdbc.update(
                  "UPDATE platform.outbox_delivery SET status='RUNNING',attempts=attempts+1,lease_owner=?,lease_generation=?,lease_until=? WHERE event_id=? AND consumer_name=?",
                  owner,
                  item.generation(),
                  time(clock.instant().plusSeconds(60)),
                  item.id(),
                  item.consumer());
              return item;
            });
    if (claim == null) return;
    try {
      tx.executeWithoutResult(
          status -> {
            var lease =
                jdbc.queryForList(
                    "SELECT event_id FROM platform.outbox_delivery WHERE event_id=? AND consumer_name=? AND lease_owner=? AND lease_generation=? AND lease_until>? FOR UPDATE",
                    claim.id(),
                    claim.consumer(),
                    owner,
                    claim.generation(),
                    time(clock.instant()));
            if (lease.isEmpty()) return;
            int fresh =
                jdbc.update(
                    "INSERT INTO platform.consumer_receipt(consumer_name,event_id,processed_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    claim.consumer(),
                    claim.id(),
                    time(clock.instant()));
            if (fresh == 1) {
              var event =
                  jdbc.queryForMap(
                      "SELECT event_type,payload::text FROM platform.outbox_event WHERE id=?",
                      claim.id());
              consumers.stream()
                  .filter(c -> c.name().equals(claim.consumer()))
                  .findFirst()
                  .orElseThrow()
                  .apply(
                      claim.id(), (String) event.get("event_type"), (String) event.get("payload"));
            }
            int acknowledged =
                jdbc.update(
                    "UPDATE platform.outbox_delivery SET status='COMPLETED',completed_at=?,lease_owner=NULL,lease_until=NULL WHERE event_id=? AND consumer_name=? AND lease_generation=? AND lease_until>?",
                    time(clock.instant()),
                    claim.id(),
                    claim.consumer(),
                    claim.generation(),
                    time(clock.instant()));
            if (acknowledged != 1) throw new IllegalStateException("OUTBOX_LEASE_EXPIRED");
          });
    } catch (RuntimeException failure) {
      tx.executeWithoutResult(
          status ->
              jdbc.update(
                  "UPDATE platform.outbox_delivery SET status=CASE WHEN attempts>=10 THEN 'DEAD' ELSE 'RETRY' END,last_error_code='CONSUMER_FAILED',available_at=?,lease_owner=NULL,lease_until=NULL WHERE event_id=? AND consumer_name=? AND lease_owner=? AND lease_generation=? AND lease_until>?",
                  time(clock.instant().plusSeconds(retryDelay(claim.attempts()))),
                  claim.id(),
                  claim.consumer(),
                  owner,
                  claim.generation(),
                  time(clock.instant())));
    }
  }

  private static long retryDelay(int attempts) {
    long delay = Math.min(3600, 5L << Math.min(10, attempts - 1));
    return Math.min(
        3600,
        delay + java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, delay / 4)));
  }

  public void prune() {
    tx.executeWithoutResult(
        status -> {
          var now = time(clock.instant());
          jdbc.update(
              "UPDATE platform.job SET status='DEAD',last_error_code='ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE id IN (SELECT id FROM platform.job WHERE status='RUNNING' AND lease_until<=? AND attempts>=10 ORDER BY lease_until,id LIMIT 500)",
              now);
          jdbc.update(
              "UPDATE platform.outbox_delivery SET status='DEAD',last_error_code='ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE (event_id,consumer_name) IN (SELECT event_id,consumer_name FROM platform.outbox_delivery WHERE status='RUNNING' AND lease_until<=? AND attempts>=10 ORDER BY lease_until,event_id,consumer_name LIMIT 500)",
              now);
          jdbc.update(
              "DELETE FROM platform.secret_mail_payload WHERE job_id IN (SELECT p.job_id FROM platform.secret_mail_payload p JOIN platform.job j ON j.id=p.job_id WHERE p.expires_at<=? OR j.status='DEAD' ORDER BY p.expires_at,p.job_id LIMIT 500)",
              now);
          jdbc.update(
              "DELETE FROM platform.job WHERE id IN (SELECT j.id FROM platform.job j WHERE j.status='COMPLETED' AND j.completed_at<? AND NOT EXISTS(SELECT 1 FROM platform.secret_mail_payload p WHERE p.job_id=j.id) ORDER BY j.completed_at LIMIT 500)",
              time(clock.instant().minusSeconds(30 * 86400L)));
          var cutoff = time(clock.instant().minusSeconds(30 * 86400L));
          jdbc.update(
              "DELETE FROM platform.consumer_receipt WHERE event_id IN (SELECT e.id FROM platform.outbox_event e WHERE e.created_at<? AND NOT EXISTS(SELECT 1 FROM platform.outbox_delivery d WHERE d.event_id=e.id AND d.status<>'COMPLETED') ORDER BY e.created_at LIMIT 500)",
              cutoff);
          jdbc.update(
              "DELETE FROM platform.outbox_delivery WHERE event_id IN (SELECT e.id FROM platform.outbox_event e WHERE e.created_at<? AND NOT EXISTS(SELECT 1 FROM platform.outbox_delivery d WHERE d.event_id=e.id AND d.status<>'COMPLETED') ORDER BY e.created_at LIMIT 500)",
              cutoff);
          jdbc.update(
              "DELETE FROM platform.outbox_event WHERE id IN (SELECT e.id FROM platform.outbox_event e WHERE e.created_at<? AND NOT EXISTS(SELECT 1 FROM platform.outbox_delivery d WHERE d.event_id=e.id) ORDER BY e.created_at LIMIT 500)",
              cutoff);
        });
  }

  public long queueDepth(String status) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM platform.job WHERE status=?", Long.class, status);
  }

  public double oldestPendingSeconds() {
    return jdbc.queryForObject(
        "SELECT COALESCE(EXTRACT(EPOCH FROM (?::timestamptz-min(created_at))),0) FROM platform.job WHERE status IN ('PENDING','RUNNING','RETRY')",
        Double.class,
        time(clock.instant()));
  }
}
