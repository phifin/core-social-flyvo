package com.flyvo.socialcore.account.infrastructure.persistence;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcErasureStore implements ErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime t(Instant v) {
    return v == null ? null : v.atOffset(ZoneOffset.UTC);
  }

  public Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  public Intent intent(UUID id) {
    var rows =
        jdbc.query(
            "SELECT id,user_id,expected_version,created_at,lineage FROM account.erasure_intent WHERE id=?",
            (r, n) ->
                new Intent(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getLong(3),
                    r.getObject(4, OffsetDateTime.class).toInstant(),
                    r.getObject(5, UUID.class)),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Intent prepare(UUID user, long expected, UUID lineage) {
    jdbc.update(
        "INSERT INTO account.erasure_intent(id,user_id,expected_version,protocol,policy,lineage) VALUES (?,?,?,?,?,?) ON CONFLICT(user_id,expected_version) DO NOTHING",
        UUID.randomUUID(),
        user,
        expected,
        AccountErasure.PROTOCOL,
        AccountErasure.POLICY,
        lineage);
    var result =
        intent(
            jdbc.queryForObject(
                "SELECT id FROM account.erasure_intent WHERE user_id=? AND expected_version=?",
                UUID.class,
                user,
                expected));
    if (!lineage.equals(result.lineage()))
      throw new com.flyvo.socialcore.platform.api.Failure(503, "RECOVERY_LINEAGE_MISMATCH");
    return result;
  }

  public Request request(UUID user) {
    var rows =
        jdbc.query(
            "SELECT id,user_id,requested_at,lifecycle_generation,verification_round,version,manifest_digest FROM account.erasure_request WHERE user_id=?",
            (r, n) ->
                new Request(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getObject(3, OffsetDateTime.class).toInstant(),
                    r.getLong(4),
                    r.getLong(5),
                    r.getLong(6),
                    r.getString(7)),
            user);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void createRequest(Intent i, Instant at, long generation, String digest) {
    jdbc.update(
        "INSERT INTO account.erasure_request(id,user_id,requested_at,lifecycle_generation,protocol,policy,roster_digest) VALUES (?,?,?,?,?,?,?)",
        i.id(),
        i.user(),
        t(at),
        generation,
        AccountErasure.PROTOCOL,
        AccountErasure.POLICY,
        digest);
  }

  public void seed(UUID user, UUID request, Step step, String status, String reason) {
    jdbc.update(
        "INSERT INTO account.erasure_step(user_id,request_id,module,step,status,reason) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        user,
        request,
        step.owner(),
        step.step(),
        status,
        reason);
  }

  public State step(UUID user, Step step, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT status,reason,next_check_at,version,failures,updated_at,checkpoint,examined,proof,proof_version,verification_round,execution_id FROM account.erasure_step WHERE user_id=? AND module=? AND step=?"
                + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new State(
                    new StepView(
                        step,
                        r.getString(1),
                        r.getString(2),
                        r.getObject(3, OffsetDateTime.class) == null
                            ? null
                            : r.getObject(3, OffsetDateTime.class).toInstant(),
                        r.getLong(4),
                        r.getLong(5),
                        r.getObject(6, OffsetDateTime.class).toInstant()),
                    r.getString(7),
                    r.getLong(8),
                    r.getString(9),
                    r.getObject(10, Long.class),
                    r.getLong(11),
                    r.getObject(12, UUID.class)),
            user,
            step.owner(),
            step.step());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void advance(
      Context c,
      String checkpoint,
      int count,
      String status,
      String reason,
      Instant due,
      String proof,
      UUID execution) {
    int changed =
        jdbc.update(
            "UPDATE account.erasure_step SET checkpoint=?,examined=examined+?,status=?,reason=?,next_check_at=?,proof=?,proof_version=?,completed_at=?,execution_id=?,version=version+1,updated_at=clock_timestamp() WHERE user_id=? AND module=? AND step=? AND version=? AND verification_round=? AND status<>'DONE'",
            checkpoint,
            count,
            status,
            reason,
            t(due),
            proof,
            "DONE".equals(status) ? c.version() + 1 : null,
            "DONE".equals(status) ? t(now()) : null,
            execution,
            c.userId(),
            c.step().owner(),
            c.step().step(),
            c.version(),
            c.round());
    if (changed != 1) throw new IllegalStateException("ERASURE_STALE_PASS");
    jdbc.update(
        "UPDATE account.erasure_request SET version=version+1,updated_at=clock_timestamp() WHERE id=?",
        c.requestId());
  }

  public void failed(UUID user, Step step, boolean dead) {
    jdbc.update(
        "UPDATE account.erasure_step SET failures=failures+1,status=?,reason='EXECUTION_FAILED',updated_at=clock_timestamp() WHERE user_id=? AND module=? AND step=? AND status<>'DONE'",
        dead ? "FAILED" : "RETRY",
        user,
        step.owner(),
        step.step());
    jdbc.update(
        "UPDATE account.erasure_request SET version=version+1,updated_at=clock_timestamp() WHERE user_id=?",
        user);
  }

  public List<UUID> scan(UUID after, int limit) {
    return jdbc.query(
        "SELECT id FROM account.user_account WHERE status IN ('DELETION_PENDING','DELETED') AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
        (r, n) -> r.getObject(1, UUID.class),
        after,
        after,
        limit);
  }

  private static final org.springframework.jdbc.core.RowMapper<Export> EXPORT =
      (r, n) ->
          new Export(
              r.getObject("id", UUID.class),
              r.getObject("intent_id", UUID.class),
              r.getString("operation"),
              r.getString("record"),
              r.getString("digest"),
              r.getString("receipt_digest"),
              r.getObject("execution_id", UUID.class));

  public Export export(UUID id) {
    var rows = jdbc.query("SELECT * FROM account.erasure_export WHERE id=?", EXPORT, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Export export(UUID intent, String operation) {
    var rows =
        jdbc.query(
            "SELECT * FROM account.erasure_export WHERE intent_id=? AND operation=? ORDER BY created_at,id LIMIT 1",
            EXPORT,
            intent,
            operation);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public UUID addExport(
      UUID id,
      UUID intent,
      String op,
      String owner,
      String kind,
      List<UUID> ids,
      String record,
      String digest) {
    jdbc.update(
        "INSERT INTO account.erasure_export(id,intent_id,operation,owner,resource_kind,resource_ids,record,digest,execution_id) VALUES (?,?,?,?,?,?,?, ?,?)",
        id,
        intent,
        op,
        owner,
        kind,
        ids == null ? null : ids.toArray(UUID[]::new),
        record,
        digest,
        UUID.randomUUID());
    return id;
  }

  public void receipt(UUID id, String digest) {
    if (jdbc.update(
                "UPDATE account.erasure_export SET receipt_digest=?,verified_at=clock_timestamp() WHERE id=? AND digest=? AND receipt_digest IS NULL",
                digest,
                id,
                digest)
            == 0
        && !digest.equals(export(id).receipt()))
      throw new IllegalStateException("ARCHIVE_RECEIPT_MISMATCH");
  }

  public void exportFailed(UUID id) {
    jdbc.update("UPDATE account.erasure_export SET failures=failures+1 WHERE id=?", id);
    jdbc.update(
        "UPDATE account.erasure_request SET version=version+1,updated_at=clock_timestamp() WHERE id=(SELECT intent_id FROM account.erasure_export WHERE id=?)",
        id);
  }

  public java.util.Map<String, Long> resourceFailures(UUID intent) {
    var result = new java.util.HashMap<String, Long>();
    jdbc.query(
        "SELECT owner,sum(failures) FROM account.erasure_export WHERE intent_id=? AND operation='RESOURCE' GROUP BY owner",
        r -> {
          result.put(r.getString(1), r.getLong(2));
        },
        intent);
    return java.util.Map.copyOf(result);
  }

  public boolean pendingResources(UUID intent) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM account.erasure_export WHERE intent_id=? AND operation='RESOURCE' AND receipt_digest IS NULL)",
            Boolean.class,
            intent));
  }

  public boolean pendingResources(UUID intent, String owner) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM account.erasure_export WHERE intent_id=? AND owner=? AND operation='RESOURCE' AND receipt_digest IS NULL)",
            Boolean.class,
            intent,
            owner));
  }

  public List<Export> pending(UUID intent) {
    return jdbc.query(
        "SELECT * FROM account.erasure_export WHERE intent_id=? AND receipt_digest IS NULL ORDER BY created_at,id LIMIT 100",
        EXPORT,
        intent);
  }

  public Resource nextResource(UUID request, String kind, UUID after) {
    var rows =
        jdbc.query(
            "SELECT id FROM account.erasure_export WHERE intent_id=? AND resource_kind=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT 1",
            (r, n) -> r.getObject(1, UUID.class),
            request,
            kind,
            after,
            after);
    return rows.isEmpty() ? null : resource(rows.getFirst());
  }

  public Resource resource(UUID chunk) {
    var rows =
        jdbc.query(
            "SELECT id,owner,resource_kind,resource_ids,receipt_digest IS NOT NULL FROM account.erasure_export WHERE id=? AND operation='RESOURCE'",
            (r, n) ->
                new Resource(
                    r.getObject(1, UUID.class),
                    r.getString(2),
                    r.getString(3),
                    Arrays.asList((UUID[]) r.getArray(4).getArray()),
                    r.getBoolean(5)),
            chunk);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void resumeExport(UUID id, UUID execution) {
    jdbc.update(
        "UPDATE account.erasure_export SET execution_id=? WHERE id=? AND receipt_digest IS NULL",
        execution,
        id);
    jdbc.update(
        "UPDATE account.erasure_request SET version=version+1,updated_at=clock_timestamp() WHERE id=(SELECT intent_id FROM account.erasure_export WHERE id=?)",
        id);
  }

  public String lockManifest(UUID request) {
    return jdbc.queryForObject(
        "SELECT manifest_digest FROM account.erasure_request WHERE id=? FOR UPDATE",
        String.class,
        request);
  }

  public void manifest(UUID request, String digest) {
    jdbc.update(
        "UPDATE account.erasure_request SET manifest_digest=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",
        digest,
        request);
  }

  public boolean issue(UUID user, String reason) {
    return jdbc.update(
            "INSERT INTO account.erasure_issue(user_id,reason) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET reason=EXCLUDED.reason,version=account.erasure_issue.version+1,updated_at=clock_timestamp() WHERE account.erasure_issue.reason<>EXCLUDED.reason",
            user,
            reason)
        > 0;
  }

  public StepView issue(UUID user) {
    var rows =
        jdbc.query(
            "SELECT reason,version,updated_at FROM account.erasure_issue WHERE user_id=?",
            (r, n) ->
                new StepView(
                    Step.RECOVERY_INTENT,
                    "FAILED",
                    r.getString(1),
                    null,
                    r.getLong(2),
                    0,
                    r.getObject(3, OffsetDateTime.class).toInstant()),
            user);
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
