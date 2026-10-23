package com.flyvo.socialcore.moderation.infrastructure.persistence;

import com.flyvo.socialcore.moderation.api.CaseOperations;
import com.flyvo.socialcore.moderation.application.port.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPreservationStore implements PreservationStore {
  private final JdbcTemplate jdbc;

  public JdbcPreservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime at(Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet r, String name) throws SQLException {
    var t = r.getObject(name, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static CaseStore.Root root(ResultSet r, int n) throws SQLException {
    return new CaseStore.Root(
        CaseOperations.Root.valueOf(r.getString(1)), r.getObject(2, UUID.class));
  }

  public Export find(UUID id) {
    var rows =
        jdbc.query(
            "SELECT * FROM moderation.preservation_export WHERE id=?",
            JdbcPreservationStore::export,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Export export(ResultSet r, int n) throws SQLException {
    return new Export(
        r.getObject("id", UUID.class),
        r.getInt("format_version"),
        new CaseStore.Root(
            CaseOperations.Root.valueOf(r.getString("root_type")),
            r.getObject("root_id", UUID.class)),
        r.getLong("root_version"),
        r.getString("operation"),
        r.getObject("actor_id", UUID.class),
        r.getObject("community_id", UUID.class),
        instant(r, "hold_until"),
        r.getString("record"),
        r.getString("digest"),
        r.getString("receipt_digest"),
        r.getObject("execution_id", UUID.class));
  }

  public void add(Export e) {
    jdbc.update(
        "INSERT INTO moderation.preservation_export(id,format_version,root_type,root_id,root_version,operation,actor_id,community_id,hold_until,record,digest,execution_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        e.id(),
        e.formatVersion(),
        e.root().type().name(),
        e.root().id(),
        e.version(),
        e.operation(),
        e.actor(),
        e.community(),
        at(e.until()),
        e.record(),
        e.digest(),
        e.execution());
  }

  public void receipt(UUID id, String digest) {
    if (jdbc.update(
            "UPDATE moderation.preservation_export SET receipt_digest=?,verified_at=clock_timestamp() WHERE id=? AND digest=? AND receipt_digest IS NULL",
            digest,
            id,
            digest)
        != 1) {
      var e = find(id);
      if (e == null || !digest.equals(e.receipt()))
        throw new IllegalStateException("PRESERVATION_RECEIPT_MISMATCH");
    }
  }

  public void failed(UUID id) {
    jdbc.update(
        "UPDATE moderation.preservation_export SET failures=failures+1 WHERE id=? AND receipt_digest IS NULL",
        id);
  }

  public List<Export> pending(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid preservation page");
    return jdbc.query(
        "SELECT * FROM moderation.preservation_export WHERE receipt_digest IS NULL ORDER BY created_at,id LIMIT ?",
        JdbcPreservationStore::export,
        limit);
  }

  public void resume(UUID id, UUID execution) {
    if (jdbc.update(
            "UPDATE moderation.preservation_export SET execution_id=? WHERE id=? AND receipt_digest IS NULL",
            execution,
            id)
        != 1) throw new IllegalStateException("PRESERVATION_EXECUTION_STALE");
  }

  public State state(CaseStore.Root root, long version) {
    jdbc.update(
        "INSERT INTO moderation.preservation_state(root_type,root_id,root_version,format_version) VALUES (?,?,?,?) ON CONFLICT(root_type,root_id) DO UPDATE SET root_version=EXCLUDED.root_version,format_version=EXCLUDED.format_version,after_history=NULL,complete=false,pending_export=NULL,pending_after=NULL,pending_complete=false WHERE moderation.preservation_state.root_version<>EXCLUDED.root_version OR moderation.preservation_state.format_version<>EXCLUDED.format_version",
        root.type().name(),
        root.id(),
        version,
        FORMAT_VERSION);
    return jdbc.queryForObject(
        "SELECT * FROM moderation.preservation_state WHERE root_type=? AND root_id=? FOR UPDATE",
        (r, n) ->
            new State(
                r.getInt("format_version"),
                r.getLong("root_version"),
                r.getObject("after_history", UUID.class),
                r.getBoolean("complete"),
                r.getObject("pending_export", UUID.class),
                r.getObject("pending_after", UUID.class),
                r.getBoolean("pending_complete")),
        root.type().name(),
        root.id());
  }

  public void pending(CaseStore.Root r, long version, UUID export, UUID after, boolean complete) {
    jdbc.update(
        "UPDATE moderation.preservation_state SET pending_export=?,pending_after=?,pending_complete=? WHERE root_type=? AND root_id=? AND root_version=? AND format_version=2",
        export,
        after,
        complete,
        r.type().name(),
        r.id(),
        version);
  }

  public void acknowledge(CaseStore.Root r, long version) {
    if (jdbc.update(
            "UPDATE moderation.preservation_state s SET after_history=pending_after,complete=pending_complete,pending_export=NULL,pending_after=NULL,pending_complete=false WHERE root_type=? AND root_id=? AND root_version=? AND EXISTS(SELECT 1 FROM moderation.preservation_export e WHERE e.id=s.pending_export AND e.root_type=s.root_type AND e.root_id=s.root_id AND e.root_version=s.root_version AND e.format_version=s.format_version AND s.format_version=2 AND e.receipt_digest=e.digest)",
            r.type().name(),
            r.id(),
            version)
        != 1) throw new IllegalStateException("PRESERVATION_RECEIPT_STALE");
  }

  public List<History> history(CaseStore.Root root, UUID after, int limit) {
    if (root.type() != CaseOperations.Root.REPORT) return List.of();
    return jdbc.query(
        "SELECT * FROM moderation.case_history WHERE report_id=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
        (r, n) ->
            new History(
                r.getObject("id", UUID.class),
                r.getObject("case_version", Long.class),
                r.getObject("actor_id", UUID.class),
                r.getString("operation"),
                r.getObject("action_id", UUID.class),
                r.getObject("assigned_to", UUID.class),
                instant(r, "hold_until"),
                instant(r, "created_at")),
        root.id(),
        after,
        after,
        limit);
  }

  private String roots() {
    return "SELECT 'REPORT'::text type,r.id,r.version,r.retention_state FROM moderation.report r UNION ALL SELECT 'ACTION',a.id,r.version,r.retention_state FROM moderation.action a JOIN moderation.action_retention r ON r.action_id=a.id";
  }

  public CaseStore.Root pendingRoot(UUID subject, String targetType, UUID target) {
    String relevant;
    Object[] args;
    if (target == null) {
      relevant =
          "SELECT 'REPORT'::text type,id FROM moderation.report WHERE reporter_id=? OR source_author_id=? OR parent_author_id=? OR assigned_to=? UNION SELECT 'REPORT',report_id FROM moderation.case_history WHERE actor_id=? OR assigned_to=? UNION SELECT CASE WHEN report_id IS NULL THEN 'ACTION' ELSE 'REPORT' END,COALESCE(report_id,id) FROM moderation.action WHERE actor_id=? OR (target_type IN ('ACCOUNT','MEMBER') AND target_id=?)";
      args = Collections.nCopies(8, subject).toArray();
    } else {
      relevant =
          "SELECT 'REPORT'::text type,id FROM moderation.report WHERE target_type=? AND target_id=? UNION SELECT CASE WHEN report_id IS NULL THEN 'ACTION' ELSE 'REPORT' END,COALESCE(report_id,id) FROM moderation.action WHERE target_type=? AND target_id=?";
      args = new Object[] {targetType, target, targetType, target};
    }
    var list =
        jdbc.query(
            "WITH relevant AS ("
                + relevant
                + "), roots AS ("
                + roots()
                + ") SELECT r.type,r.id FROM roots r JOIN relevant v ON v.type=r.type AND v.id=r.id LEFT JOIN moderation.preservation_state p ON p.root_type=r.type AND p.root_id=r.id WHERE r.retention_state<>'DONE' OR p.format_version<>2 OR p.root_version IS NULL OR p.root_version<>r.version OR NOT COALESCE(p.complete,false) ORDER BY r.type,r.id LIMIT 1",
            JdbcPreservationStore::root,
            args);
    return list.isEmpty() ? null : list.getFirst();
  }

  public CaseStore.Root scan(CaseStore.Root after) {
    var rows =
        jdbc.query(
            "SELECT type,id FROM ("
                + roots()
                + ") r WHERE (?::text IS NULL OR (type,id)>(?,?)) ORDER BY type,id LIMIT 1",
            JdbcPreservationStore::root,
            after == null ? null : after.type().name(),
            after == null ? null : after.type().name(),
            after == null ? null : after.id());
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
