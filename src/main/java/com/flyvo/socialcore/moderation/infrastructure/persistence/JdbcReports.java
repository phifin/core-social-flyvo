package com.flyvo.socialcore.moderation.infrastructure.persistence;

import com.flyvo.socialcore.moderation.application.port.ReportRepository;
import com.flyvo.socialcore.moderation.domain.Report;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReports implements ReportRepository {
  private static final String SELECT =
      "SELECT r.*,e.ciphertext AS evidence_encrypted,e.key_id AS evidence_key_id FROM moderation.report r JOIN moderation.report_evidence e ON e.report_id=r.id";
  private final JdbcTemplate jdbc;

  JdbcReports(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private Report row(ResultSet r, int n) throws SQLException {
    var resolved = r.getObject("resolved_at", OffsetDateTime.class);
    return new Report(
        r.getObject("id", UUID.class),
        r.getObject("reporter_id", UUID.class),
        r.getString("target_type"),
        r.getObject("target_id", UUID.class),
        r.getObject("community_id", UUID.class),
        r.getObject("source_author_id", UUID.class),
        r.getObject("parent_author_id", UUID.class),
        r.getString("category"),
        r.getBytes("evidence_encrypted"),
        r.getString("evidence_key_id"),
        r.getString("status"),
        r.getObject("created_at", OffsetDateTime.class).toInstant(),
        resolved == null ? null : resolved.toInstant(),
        r.getLong("version"));
  }

  public Report find(UUID id, boolean lock) {
    var rows =
        jdbc.query(SELECT + " WHERE r.id=?" + (lock ? " FOR UPDATE OF r" : ""), this::row, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Report insertOrUnresolved(Report r) {
    var t = r.created().atOffset(ZoneOffset.UTC);
    var ids =
        jdbc.queryForList(
            "INSERT INTO moderation.report(id,reporter_id,target_type,target_id,community_id,source_author_id,parent_author_id,category,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?) ON CONFLICT (reporter_id,target_type,target_id) WHERE status IN ('OPEN','IN_REVIEW') DO NOTHING RETURNING id",
            UUID.class,
            r.id(),
            r.reporter(),
            r.targetType(),
            r.targetId(),
            r.community(),
            r.author(),
            r.parentAuthor(),
            r.category(),
            t,
            t);
    if (!ids.isEmpty()) {
      jdbc.update(
          "INSERT INTO moderation.report_evidence(report_id,ciphertext,key_id) VALUES (?,?,?)",
          r.id(),
          r.evidence(),
          r.keyId());
      return r;
    }
    // The source guards precede this row lock; a committed resolver can remove
    // the conflict between statements. A missing row asks the client to retry.
    var rows =
        jdbc.query(
            SELECT
                + " WHERE r.reporter_id=? AND r.target_type=? AND r.target_id=? AND r.status IN ('OPEN','IN_REVIEW') FOR UPDATE OF r",
            this::row,
            r.reporter(),
            r.targetType(),
            r.targetId());
    if (rows.isEmpty()) throw new com.flyvo.socialcore.platform.api.Failure(409, "REPORT_RETRY");
    return rows.getFirst();
  }

  public void save(Report r, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE moderation.report SET status=?,resolved_at=?,updated_at=?,version=? WHERE id=? AND version=?",
            r.status(),
            r.resolved() == null ? null : r.resolved().atOffset(ZoneOffset.UTC),
            now.atOffset(ZoneOffset.UTC),
            r.version(),
            r.id(),
            r.version() - 1);
    if (changed != 1) throw new com.flyvo.socialcore.platform.api.Failure(412, "STALE_VERSION");
  }

  public List<Report> page(UUID reporter, UUID community, int size, int offset) {
    if (reporter != null)
      return jdbc.query(
          SELECT + " WHERE r.reporter_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
          this::row,
          reporter,
          size,
          offset);
    if (community != null)
      return jdbc.query(
          SELECT + " WHERE r.community_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
          this::row,
          community,
          size,
          offset);
    return jdbc.query(
        SELECT
            + " WHERE r.community_id IS NULL ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
        this::row,
        size,
        offset);
  }
}
