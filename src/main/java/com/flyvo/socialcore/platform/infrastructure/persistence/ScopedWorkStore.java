package com.flyvo.socialcore.platform.infrastructure.persistence;

import com.flyvo.socialcore.platform.api.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ScopedWorkStore implements ScopedWork {
  private final JdbcTemplate jdbc;
  private final DurableWork work;
  private final TransactionTemplate tx;

  public ScopedWorkStore(
      JdbcTemplate jdbc,
      DurableWork work,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.work = work;
    tx = new TransactionTemplate(manager);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void mail(
      UUID scope, String key, String address, String template, String secret, Instant expires) {
    if (scope == null) throw Failure.invalid();
    work.mail(key, address, template, secret, expires);
    jdbc.update(
        "INSERT INTO platform.work_scope(job_id,scope_id,effect_kind) SELECT id,?,'MAIL' FROM platform.job WHERE kind='MAIL' AND dedup_key=? ON CONFLICT DO NOTHING",
        scope,
        key);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM platform.work_scope s JOIN platform.job j ON j.id=s.job_id WHERE j.kind='MAIL' AND j.dedup_key=? AND s.scope_id=?)",
            Boolean.class,
            key,
            scope))) throw new IllegalStateException("WORK_SCOPE_MISMATCH");
  }

  public Drain cancelMail(UUID scope, List<UUID> proofs, Instant cut) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("CANCELLATION_UNDER_BUSINESS_TRANSACTION");
    if (scope == null
        || proofs == null
        || proofs.size() > 100
        || proofs.stream().anyMatch(java.util.Objects::isNull)) throw Failure.invalid();
    return tx.execute(
        s -> {
          var ids =
              jdbc.query(
                  "SELECT j.id FROM platform.job j WHERE j.kind='MAIL' AND (EXISTS(SELECT 1 FROM platform.work_scope s WHERE s.job_id=j.id AND s.scope_id=?) OR j.dedup_key=ANY(?) OR (?::timestamptz IS NOT NULL AND j.created_at<=? AND EXISTS(SELECT 1 FROM platform.secret_mail_payload p WHERE p.job_id=j.id AND p.expires_at<=clock_timestamp()))) AND (j.status IN ('PENDING','RETRY','RUNNING') OR EXISTS(SELECT 1 FROM platform.secret_mail_payload p WHERE p.job_id=j.id)) ORDER BY j.id LIMIT 100 FOR UPDATE OF j",
                  (r, n) -> r.getObject(1, UUID.class),
                  scope,
                  proofs.stream().map(UUID::toString).toArray(String[]::new),
                  cut == null ? null : cut.atOffset(ZoneOffset.UTC),
                  cut == null ? null : cut.atOffset(ZoneOffset.UTC));
          for (var id : ids) {
            jdbc.update(
                "UPDATE platform.job SET status=CASE WHEN status='DEAD' THEN 'DEAD' ELSE 'COMPLETED' END,completed_at=COALESCE(completed_at,clock_timestamp()),cancelled_at=COALESCE(cancelled_at,clock_timestamp()),cancellation_code='SCOPED_MAIL_CANCELLED',lease_generation=lease_generation+1,lease_owner=NULL,lease_until=NULL WHERE id=?",
                id);
            jdbc.update("DELETE FROM platform.secret_mail_payload WHERE job_id=?", id);
          }
          boolean active =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM platform.work_effect e JOIN platform.job j ON j.id=e.job_id WHERE e.ended_at IS NULL AND (EXISTS(SELECT 1 FROM platform.work_scope s WHERE s.job_id=j.id AND s.scope_id=?) OR j.dedup_key=ANY(?) OR (?::timestamptz IS NOT NULL AND j.created_at<=?)))",
                      Boolean.class,
                      scope,
                      proofs.stream().map(UUID::toString).toArray(String[]::new),
                      cut == null ? null : cut.atOffset(ZoneOffset.UTC),
                      cut == null ? null : cut.atOffset(ZoneOffset.UTC)));
          var unexpired =
              cut == null
                  ? null
                  : jdbc.queryForObject(
                      "SELECT max(p.expires_at) FROM platform.secret_mail_payload p JOIN platform.job j ON j.id=p.job_id WHERE j.created_at<=? AND p.expires_at>clock_timestamp()",
                      java.time.OffsetDateTime.class,
                      cut.atOffset(ZoneOffset.UTC));
          return new Drain(
              ids.size(),
              ids.size() == 100,
              active,
              unexpired == null ? null : unexpired.toInstant());
        });
  }

  @Transactional(readOnly = true)
  public String executionStatus(String kind, UUID execution) {
    var rows =
        jdbc.query(
            "SELECT status FROM platform.job WHERE kind=? AND dedup_key=?",
            (r, n) -> r.getString(1),
            kind,
            execution.toString());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional(readOnly = true)
  public java.util.Map<UUID, String> executionStatuses(String kind, List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull))
      throw Failure.invalid();
    var result = new java.util.HashMap<UUID, String>();
    jdbc.query(
        "SELECT dedup_key,status FROM platform.job WHERE kind=? AND dedup_key=ANY(?)",
        r -> {
          result.put(UUID.fromString(r.getString(1)), r.getString(2));
        },
        kind,
        ids.stream().map(UUID::toString).toArray(String[]::new));
    return java.util.Map.copyOf(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int eraseIdempotency(UUID scope) {
    return jdbc.update(
        "DELETE FROM platform.idempotency_record WHERE (actor_id,route_key,key_hash) IN (SELECT actor_id,route_key,key_hash FROM platform.idempotency_record WHERE actor_id=? ORDER BY route_key,key_hash LIMIT 100)",
        scope);
  }
}
