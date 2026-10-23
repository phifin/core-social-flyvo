package com.flyvo.socialcore.moderation.infrastructure.persistence;

import com.flyvo.socialcore.moderation.api.CaseOperations;
import com.flyvo.socialcore.moderation.api.Reports;
import com.flyvo.socialcore.moderation.application.port.CaseStore;
import com.flyvo.socialcore.platform.api.Failure;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcCases implements CaseStore {
  private final JdbcTemplate jdbc;

  JdbcCases(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant at(ResultSet r, String column) throws SQLException {
    var value = r.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private boolean report(Root root) {
    return root.type() == CaseOperations.Root.REPORT;
  }

  public Control control(Root root, boolean lock) {
    String sql =
        report(root)
            ? "SELECT id,community_id,source_author_id,parent_author_id,version,assigned_to,status,hold_until,hold_reason,retention_state,resolved_at AS age_anchor,resolved_at+interval '365 days' AS eligible_at,clock_timestamp() AS db_now FROM moderation.report WHERE id=?"
                + (lock ? " FOR UPDATE" : "")
            : "SELECT r.action_id AS id,a.community_id,NULL::uuid AS source_author_id,NULL::uuid AS parent_author_id,r.version,NULL::uuid AS assigned_to,'STANDALONE' AS status,r.hold_until,r.hold_reason,r.retention_state,a.created_at AS age_anchor,a.created_at+interval '365 days' AS eligible_at,clock_timestamp() AS db_now FROM moderation.action_retention r JOIN moderation.action a ON a.id=r.action_id WHERE r.action_id=?"
                + (lock ? " FOR UPDATE OF r" : "");
    var list =
        jdbc.query(
            sql,
            (r, n) ->
                new Control(
                    root,
                    r.getObject("community_id", UUID.class),
                    r.getObject("source_author_id", UUID.class),
                    r.getObject("parent_author_id", UUID.class),
                    r.getLong("version"),
                    r.getObject("assigned_to", UUID.class),
                    r.getString("status"),
                    at(r, "hold_until"),
                    r.getString("hold_reason"),
                    r.getString("retention_state"),
                    eligibility(root, r),
                    at(r, "db_now")),
            root.id());
    return list.isEmpty() ? null : list.getFirst();
  }

  private Eligibility eligibility(Root root, ResultSet r) throws SQLException {
    var anchor =
        report(root) ? EligibilityAnchor.REPORT_RESOLVED_AT : EligibilityAnchor.ACTION_CREATED_AT;
    if (report(root) && Set.of("OPEN", "IN_REVIEW").contains(r.getString("status")))
      return new Eligibility(EligibilityState.UNRESOLVED, anchor, null, null);
    Instant at = at(r, "age_anchor"), eligible = at(r, "eligible_at");
    boolean known =
        at != null
            && eligible != null
            && (!report(root) || Set.of("RESOLVED", "DISMISSED").contains(r.getString("status")));
    return new Eligibility(
        known ? EligibilityState.KNOWN : EligibilityState.UNKNOWN,
        anchor,
        at,
        known ? eligible : null);
  }

  public List<UUID> subjects(Root root) {
    return jdbc.queryForList(
        report(root)
            ? "SELECT DISTINCT u FROM moderation.report r CROSS JOIN LATERAL unnest(ARRAY[r.reporter_id,r.source_author_id,r.parent_author_id,r.assigned_to]) u WHERE r.id=? AND u IS NOT NULL ORDER BY u"
            : "SELECT DISTINCT u FROM moderation.action a CROSS JOIN LATERAL unnest(ARRAY[a.actor_id,CASE WHEN a.target_type IN ('MEMBER','ACCOUNT') THEN a.target_id ELSE NULL END]) u WHERE a.id=? AND u IS NOT NULL ORDER BY u",
        UUID.class,
        root.id());
  }

  public List<CaseOperations.CaseView> queue(
      UUID community, CaseOperations.Filter filter, int size, int offset) {
    var args = new ArrayList<Object>();
    String where = " WHERE community_id IS NOT DISTINCT FROM ?::uuid";
    args.add(community);
    if (filter.status() != null) {
      where += " AND status=?";
      args.add(filter.status());
    }
    if (filter.assignedTo() != null) {
      where += " AND assigned_to=?";
      args.add(filter.assignedTo());
    }
    if (filter.unassigned()) where += " AND assigned_to IS NULL";
    args.add(size);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM moderation.report"
            + where
            + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        (r, n) ->
            new CaseOperations.CaseView(
                new Reports.View(
                    r.getObject("id", UUID.class),
                    Reports.Target.valueOf(r.getString("target_type")),
                    r.getObject("target_id", UUID.class),
                    r.getObject("community_id", UUID.class),
                    Reports.Category.valueOf(r.getString("category")),
                    r.getString("status"),
                    at(r, "created_at"),
                    at(r, "resolved_at"),
                    r.getLong("version")),
                r.getObject("assigned_to", UUID.class),
                at(r, "hold_until"),
                r.getString("hold_reason"),
                r.getString("retention_state")),
        args.toArray());
  }

  public void assign(UUID report, long expected, UUID assignee) {
    changed(
        jdbc.update(
            "UPDATE moderation.report SET assigned_to=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",
            assignee,
            report,
            expected));
  }

  public CaseOperations.CaseView caseView(UUID id) {
    var rows =
        jdbc.query(
            "SELECT * FROM moderation.report WHERE id=?",
            (r, n) ->
                new CaseOperations.CaseView(
                    new Reports.View(
                        r.getObject("id", UUID.class),
                        Reports.Target.valueOf(r.getString("target_type")),
                        r.getObject("target_id", UUID.class),
                        r.getObject("community_id", UUID.class),
                        Reports.Category.valueOf(r.getString("category")),
                        r.getString("status"),
                        at(r, "created_at"),
                        at(r, "resolved_at"),
                        r.getLong("version")),
                    r.getObject("assigned_to", UUID.class),
                    at(r, "hold_until"),
                    r.getString("hold_reason"),
                    r.getString("retention_state")),
            id);
    if (rows.isEmpty()) throw new Failure(404, "NOT_FOUND");
    return rows.getFirst();
  }

  public void hold(Root root, long expected, Instant until, String reason) {
    String table = report(root) ? "moderation.report" : "moderation.action_retention";
    String id = report(root) ? "id" : "action_id";
    changed(
        jdbc.update(
            "UPDATE "
                + table
                + " SET hold_until=?,hold_reason=?,version=version+1 WHERE "
                + id
                + "=? AND version=?",
            until == null ? null : until.atOffset(ZoneOffset.UTC),
            reason,
            root.id(),
            expected));
  }

  private void changed(int n) {
    if (n != 1) throw new Failure(412, "STALE_VERSION");
  }

  public void history(UUID report, UUID actor, String operation, UUID actionId) {
    jdbc.update(
        "INSERT INTO moderation.case_history(id,report_id,case_version,actor_id,operation,action_id,assigned_to,hold_until,created_at) SELECT ?,id,version,?,?,?,CASE WHEN ?='ASSIGNED' THEN assigned_to ELSE NULL END,hold_until,clock_timestamp() FROM moderation.report WHERE id=?",
        UUID.randomUUID(),
        actor,
        operation,
        actionId,
        operation,
        report);
  }

  private Payload payload(ResultSet r) throws SQLException {
    var id = r.getObject("action_id", UUID.class);
    return id == null
        ? null
        : new Payload(
            id,
            r.getObject("report_id", UUID.class),
            r.getString("legacy_payload"),
            r.getBytes("ciphertext"),
            r.getString("key_id"),
            at(r, "redacted_at"));
  }

  public List<History> history(UUID report, int size, int offset) {
    return jdbc.query(
        "SELECT h.*,d.legacy_payload,d.ciphertext,d.key_id,d.redacted_at FROM moderation.case_history h LEFT JOIN moderation.action_detail d ON d.action_id=h.action_id WHERE h.report_id=? ORDER BY h.created_at,h.id LIMIT ? OFFSET ?",
        (r, n) ->
            new History(
                r.getObject("id", UUID.class),
                r.getObject("case_version", Long.class),
                r.getObject("actor_id", UUID.class),
                r.getString("operation"),
                r.getObject("action_id", UUID.class),
                r.getObject("assigned_to", UUID.class),
                at(r, "hold_until"),
                at(r, "created_at"),
                payload(r)),
        report,
        size,
        offset);
  }

  public Action action(UUID id) {
    var list =
        jdbc.query(
            "SELECT * FROM moderation.action WHERE id=?",
            (r, n) ->
                new Action(
                    r.getObject("id", UUID.class),
                    r.getObject("report_id", UUID.class),
                    r.getObject("actor_id", UUID.class),
                    r.getObject("community_id", UUID.class),
                    r.getString("target_type"),
                    r.getObject("target_id", UUID.class),
                    r.getString("action"),
                    at(r, "created_at"),
                    null),
            id);
    return list.isEmpty() ? null : list.getFirst();
  }

  public Payload payload(UUID action) {
    var list =
        jdbc.query(
            "SELECT d.*,a.report_id FROM moderation.action_detail d JOIN moderation.action a ON a.id=d.action_id WHERE d.action_id=?",
            (r, n) -> payload(r),
            action);
    if (list.isEmpty()) throw new IllegalStateException("MODERATION_PAYLOAD_MISSING");
    return list.getFirst();
  }

  public Root legacyRoot() {
    var list =
        jdbc.query(
            "SELECT a.id,a.report_id FROM moderation.action a JOIN moderation.action_detail d ON d.action_id=a.id WHERE d.legacy_payload IS NOT NULL ORDER BY a.id LIMIT 1",
            (r, n) -> {
              var report = r.getObject("report_id", UUID.class);
              return new Root(
                  report == null ? CaseOperations.Root.ACTION : CaseOperations.Root.REPORT,
                  report == null ? r.getObject("id", UUID.class) : report);
            });
    return list.isEmpty() ? null : list.getFirst();
  }

  public List<Payload> legacy(Root root, int limit) {
    if (limit < 1 || limit > 100) throw Failure.invalid();
    return jdbc.query(
        "SELECT a.id AS action_id,a.report_id,d.* FROM moderation.action a JOIN moderation.action_detail d ON d.action_id=a.id WHERE "
            + (report(root) ? "a.report_id=?" : "a.id=?")
            + " AND d.legacy_payload IS NOT NULL ORDER BY a.id LIMIT ? FOR UPDATE OF d",
        (r, n) -> payload(r),
        root.id(),
        limit);
  }

  public void encrypted(UUID action, byte[] ciphertext, String keyId) {
    changed(
        jdbc.update(
            "UPDATE moderation.action_detail SET legacy_payload=NULL,ciphertext=?,key_id=? WHERE action_id=? AND legacy_payload IS NOT NULL",
            ciphertext,
            keyId,
            action));
  }

  public Root retentionRoot() {
    var cases =
        jdbc.queryForList(
            "SELECT id FROM moderation.report WHERE retention_state<>'DONE' AND status IN ('RESOLVED','DISMISSED') AND resolved_at<=statement_timestamp()-interval '365 days' AND (hold_until IS NULL OR hold_until<=clock_timestamp()) ORDER BY resolved_at,id LIMIT 1",
            UUID.class);
    if (!cases.isEmpty()) return new Root(CaseOperations.Root.REPORT, cases.getFirst());
    var actions =
        jdbc.queryForList(
            "SELECT a.id FROM moderation.action a JOIN moderation.action_retention r ON r.action_id=a.id WHERE r.retention_state<>'DONE' AND a.created_at<=statement_timestamp()-interval '365 days' AND (r.hold_until IS NULL OR r.hold_until<=clock_timestamp()) ORDER BY a.created_at,a.id LIMIT 1",
            UUID.class);
    return actions.isEmpty() ? null : new Root(CaseOperations.Root.ACTION, actions.getFirst());
  }

  private boolean eligible(Root root) {
    String sql =
        report(root)
            ? "SELECT status IN ('RESOLVED','DISMISSED') AND resolved_at+interval '365 days'<=clock_timestamp() AND (hold_until IS NULL OR hold_until<=clock_timestamp()) AND retention_state<>'DONE' FROM moderation.report WHERE id=?"
            : "SELECT a.created_at+interval '365 days'<=clock_timestamp() AND (r.hold_until IS NULL OR r.hold_until<=clock_timestamp()) AND r.retention_state<>'DONE' FROM moderation.action a JOIN moderation.action_retention r ON r.action_id=a.id WHERE a.id=?";
    return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, root.id()));
  }

  private void state(Root root, String state) {
    jdbc.update(
        "UPDATE "
            + (report(root) ? "moderation.report" : "moderation.action_retention")
            + " SET retention_state=?,version=version+1 WHERE "
            + (report(root) ? "id" : "action_id")
            + "=?",
        state,
        root.id());
    if (report(root)) history(root.id(), null, "RETENTION_" + state, null);
  }

  public int redact(Root root, int limit) {
    if (limit < 1 || limit > 100) throw Failure.invalid();
    var c = control(root, true);
    if (c == null || !eligible(root)) return 0;
    if (c.retentionState().equals("NONE")) state(root, "STARTED");
    int changed = 0;
    if (report(root))
      changed =
          jdbc.update(
              "UPDATE moderation.report_evidence SET ciphertext=NULL,key_id=NULL,redacted_at=clock_timestamp() WHERE report_id=? AND redacted_at IS NULL",
              root.id());
    var ids =
        jdbc.queryForList(
            "SELECT a.id FROM moderation.action a JOIN moderation.action_detail d ON d.action_id=a.id WHERE "
                + (report(root) ? "a.report_id=?" : "a.id=?")
                + " AND d.redacted_at IS NULL ORDER BY a.id LIMIT ? FOR UPDATE OF d",
            UUID.class,
            root.id(),
            limit - changed);
    for (var id : ids)
      changed +=
          jdbc.update(
              "UPDATE moderation.action_detail SET legacy_payload=NULL,ciphertext=NULL,key_id=NULL,redacted_at=clock_timestamp() WHERE action_id=? AND redacted_at IS NULL",
              id);
    boolean remains =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM moderation.action a JOIN moderation.action_detail d ON d.action_id=a.id WHERE "
                    + (report(root) ? "a.report_id=?" : "a.id=?")
                    + " AND d.redacted_at IS NULL)",
                Boolean.class,
                root.id()));
    if (!remains) state(root, "DONE");
    return changed;
  }

  public CaseOperations.Progress progress() {
    return new CaseOperations.Progress(
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation.action_detail WHERE legacy_payload IS NOT NULL",
            Long.class),
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation.report WHERE retention_state='STARTED'", Long.class),
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation.action_retention WHERE retention_state='STARTED'",
            Long.class),
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation.preservation_export WHERE receipt_digest IS NULL",
            Long.class),
        jdbc.queryForObject(
            "SELECT COALESCE(sum(failures),0) FROM moderation.preservation_export", Long.class));
  }
}
