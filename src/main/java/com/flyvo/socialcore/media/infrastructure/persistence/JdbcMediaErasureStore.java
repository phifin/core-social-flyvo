package com.flyvo.socialcore.media.infrastructure.persistence;

import com.flyvo.socialcore.media.application.port.MediaErasureStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMediaErasureStore implements MediaErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcMediaErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  public List<UUID> scan(UUID owner, UUID after, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid erasure page");
    return jdbc.queryForList(
        "SELECT id FROM media.asset WHERE owner_id=? AND (?::uuid IS NULL OR id>?) "
            + "ORDER BY id LIMIT ?",
        UUID.class,
        owner,
        after,
        after,
        limit);
  }

  public UUID nextUnpurged(UUID owner) {
    var rows =
        jdbc.queryForList(
            "SELECT id FROM media.asset WHERE owner_id=? AND erasure_purged_at IS NULL "
                + "ORDER BY id LIMIT 1",
            UUID.class,
            owner);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void claim(UUID owner, UUID request, UUID asset, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE media.asset SET erasure_request_id=?,"
                + "erasure_reserved_due=CASE WHEN erasure_request_id IS NULL AND state<>'DELETED' "
                + "AND retained_bytes=0 THEN declared_bytes ELSE erasure_reserved_due END,"
                + "erasure_retained_due=CASE WHEN erasure_request_id IS NULL AND state<>'DELETED' "
                + "THEN retained_bytes ELSE erasure_retained_due END,"
                + "state='DELETING',processing_generation=processing_generation+CASE WHEN erasure_request_id IS NULL THEN 1 ELSE 0 END,"
                + "version=version+CASE WHEN erasure_request_id IS NULL THEN 1 ELSE 0 END,updated_at=? "
                + "WHERE id=? AND owner_id=? AND erasure_purged_at IS NULL "
                + "AND (erasure_request_id IS NULL OR erasure_request_id=?)",
            request,
            time(now),
            asset,
            owner,
            request);
    if (changed != 1) throw new IllegalStateException("MEDIA_ERASURE_CLAIM_STALE");
  }

  public boolean complete(
      UUID owner, UUID request, UUID asset, long expectedVersion, String drainDigest, Instant now) {
    if (drainDigest == null || !drainDigest.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid drain digest");
    var rows =
        jdbc.query(
            "SELECT version,erasure_reserved_due,erasure_retained_due FROM media.asset "
                + "WHERE id=? AND owner_id=? AND erasure_request_id=? AND erasure_purged_at IS NULL FOR UPDATE",
            (r, n) -> new long[] {r.getLong(1), r.getLong(2), r.getLong(3)},
            asset,
            owner,
            request);
    if (rows.isEmpty() || rows.getFirst()[0] != expectedVersion) return false;
    var due = rows.getFirst();
    int accounting =
        jdbc.update(
            "UPDATE media.quota SET reserved_bytes=reserved_bytes-?,retained_bytes=retained_bytes-?,"
                + "issued_count=0,version=version+1 WHERE owner_id=? "
                + "AND reserved_bytes>=? AND retained_bytes>=?",
            due[1],
            due[2],
            owner,
            due[1],
            due[2]);
    if (accounting != 1) throw new IllegalStateException("MEDIA_ERASURE_ACCOUNTING_MISMATCH");
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM media.variant WHERE asset_id=?)", Boolean.class, asset)))
      throw new IllegalStateException("MEDIA_VARIANT_METADATA_REMAINS");
    int changed =
        jdbc.update(
            "UPDATE media.asset SET state='DELETED',erasure_purged_at=?,erasure_drain_digest=?,"
                + "erasure_reserved_due=0,erasure_retained_due=0,declared_mime=NULL,declared_bytes=0,"
                + "sha256=NULL,retained_bytes=0,width=NULL,height=NULL,uploaded_version_id=NULL,"
                + "failure_code=NULL,version=version+1,updated_at=? WHERE id=? AND version=? "
                + "AND erasure_request_id=? AND erasure_purged_at IS NULL",
            time(now),
            drainDigest,
            time(now),
            asset,
            expectedVersion,
            request);
    if (changed != 1) throw new IllegalStateException("MEDIA_ERASURE_COMPLETE_STALE");
    return true;
  }

  public int pruneVariants(UUID asset, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid erasure page");
    return jdbc.update(
        "DELETE FROM media.variant WHERE asset_id=? AND id IN (SELECT id FROM media.variant WHERE asset_id=? ORDER BY id LIMIT ?)",
        asset,
        asset,
        limit);
  }

  public boolean residual(UUID owner) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM media.asset a WHERE a.owner_id=? AND "
                + "(a.erasure_purged_at IS NULL OR a.state<>'DELETED' OR a.sha256 IS NOT NULL "
                + "OR a.declared_mime IS NOT NULL OR a.declared_bytes<>0 OR a.retained_bytes<>0 "
                + "OR a.erasure_drain_digest IS NULL OR EXISTS"
                + "(SELECT 1 FROM media.variant v WHERE v.asset_id=a.id) OR EXISTS"
                + "(SELECT 1 FROM media.binding b WHERE b.asset_id=a.id AND b.released_at IS NULL))) "
                + "OR EXISTS(SELECT 1 FROM media.quota WHERE owner_id=? "
                + "AND (reserved_bytes<>0 OR retained_bytes<>0))",
            Boolean.class,
            owner,
            owner));
  }
}
