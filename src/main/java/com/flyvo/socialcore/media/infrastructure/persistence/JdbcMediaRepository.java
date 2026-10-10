package com.flyvo.socialcore.media.infrastructure.persistence;

import com.flyvo.socialcore.media.application.port.MediaRepository;
import com.flyvo.socialcore.media.domain.MediaState.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMediaRepository implements MediaRepository {
  private final JdbcTemplate jdbc;

  public JdbcMediaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime time(Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
    var t = rs.getObject(name, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  public Asset asset(UUID id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT * FROM media.asset WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Asset(
                    r.getObject("id", UUID.class),
                    r.getObject("owner_id", UUID.class),
                    r.getString("purpose"),
                    r.getString("state"),
                    r.getString("quarantine_key"),
                    r.getString("uploaded_version_id"),
                    r.getString("declared_mime"),
                    r.getLong("declared_bytes"),
                    r.getString("sha256"),
                    r.getLong("retained_bytes"),
                    r.getLong("processing_generation"),
                    instant(r, "upload_expires_at"),
                    instant(r, "orphan_expires_at"),
                    instant(r, "purge_after"),
                    r.getLong("version")),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Quota quota(UUID owner, Instant now) {
    jdbc.update(
        "INSERT INTO media.quota(owner_id,window_start) VALUES (?,?) ON CONFLICT DO NOTHING",
        owner,
        time(now));
    return jdbc.queryForObject(
        "SELECT * FROM media.quota WHERE owner_id=? FOR UPDATE",
        (r, n) ->
            new Quota(
                r.getLong("reserved_bytes"),
                r.getLong("retained_bytes"),
                instant(r, "window_start"),
                r.getInt("issued_count")),
        owner);
  }

  public void charge(UUID owner, long reserved, long retained) {
    jdbc.update(
        "UPDATE media.quota SET reserved_bytes=reserved_bytes+?,retained_bytes=retained_bytes+?,version=version+1 WHERE owner_id=?",
        reserved,
        retained,
        owner);
  }

  public void issued(UUID owner, Instant now, boolean reset) {
    if (reset)
      jdbc.update(
          "UPDATE media.quota SET window_start=?,issued_count=1 WHERE owner_id=?",
          time(now),
          owner);
    else jdbc.update("UPDATE media.quota SET issued_count=issued_count+1 WHERE owner_id=?", owner);
  }

  public void create(Asset a, Instant now) {
    jdbc.update(
        "INSERT INTO media.asset(id,owner_id,purpose,state,quarantine_key,declared_mime,declared_bytes,sha256,upload_expires_at,orphan_expires_at,created_at,updated_at) VALUES (?,?,?,'UPLOAD_PENDING',?,?,?,?,?,?,?,?)",
        a.id(),
        a.owner(),
        a.purpose(),
        a.key(),
        a.mime(),
        a.declaredBytes(),
        a.sha256(),
        time(a.uploadExpires()),
        time(a.orphanExpires()),
        time(now),
        time(now));
  }

  public void finalizeUpload(UUID id, String version, Instant now) {
    jdbc.update(
        "UPDATE media.asset SET state='UPLOADED',uploaded_version_id=?,finalized_at=?,updated_at=?,version=version+1 WHERE id=?",
        version,
        time(now),
        time(now),
        id);
  }

  public void processing(UUID id, long generation, Instant now) {
    jdbc.update(
        "UPDATE media.asset SET state='PROCESSING',processing_generation=?,updated_at=?,version=version+1 WHERE id=?",
        generation,
        time(now),
        id);
  }

  public void ready(UUID id, long generation, List<Variant> variants, Instant now) {
    for (var v : variants)
      jdbc.update(
          "INSERT INTO media.variant(id,asset_id,name,object_key,object_version_id,mime,bytes,width,height,generation) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(asset_id,name,generation) DO NOTHING",
          UUID.randomUUID(),
          id,
          v.name(),
          v.key(),
          v.objectVersion(),
          v.mime(),
          v.bytes(),
          v.width(),
          v.height(),
          generation);
    var display = variants.getFirst();
    jdbc.update(
        "UPDATE media.asset SET state='READY',width=?,height=?,retained_bytes=?,ready_at=?,orphan_expires_at=?,updated_at=?,version=version+1 WHERE id=?",
        display.width(),
        display.height(),
        variants.stream().mapToLong(Variant::bytes).sum(),
        time(now),
        time(now.plusSeconds(86400)),
        time(now),
        id);
  }

  public void state(UUID id, String state, String code, Instant now) {
    jdbc.update(
        "UPDATE media.asset SET state=?,failure_code=?,updated_at=?,version=version+1 WHERE id=?",
        state,
        code,
        time(now),
        id);
  }

  public Binding binding(UUID asset) {
    var rows =
        jdbc.query(
            "SELECT * FROM media.binding WHERE asset_id=?",
            (r, n) ->
                new Binding(
                    r.getString("context_type"),
                    r.getObject("context_id", UUID.class),
                    instant(r, "released_at")),
            asset);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void bind(UUID asset, String purpose, UUID context, Instant now) {
    jdbc.update(
        "INSERT INTO media.binding(asset_id,context_type,context_id,bound_at) VALUES (?,?,?,?)",
        asset,
        purpose,
        context,
        time(now));
  }

  public void release(UUID asset, Instant now, Instant purgeAfter) {
    jdbc.update(
        "UPDATE media.binding SET released_at=? WHERE asset_id=? AND released_at IS NULL",
        time(now),
        asset);
    jdbc.update(
        "UPDATE media.asset SET purge_after=?,version=version+1 WHERE id=?",
        time(purgeAfter),
        asset);
  }

  public List<Variant> variants(UUID asset, long generation) {
    return jdbc.query(
        "SELECT * FROM media.variant WHERE asset_id=? AND generation=? ORDER BY name",
        (r, n) ->
            new Variant(
                r.getString("name"),
                r.getString("object_key"),
                r.getString("object_version_id"),
                r.getString("mime"),
                r.getLong("bytes"),
                r.getInt("width"),
                r.getInt("height"),
                r.getLong("generation")),
        asset,
        generation);
  }

  public List<com.flyvo.socialcore.media.api.MediaQueries.BindingFacts> bindingFacts(
      List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.query(
        "SELECT a.id,a.owner_id,a.purpose,a.state,b.context_id,b.released_at FROM media.asset a LEFT JOIN media.binding b ON b.asset_id=a.id WHERE a.id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
            + ")",
        (r, n) ->
            new com.flyvo.socialcore.media.api.MediaQueries.BindingFacts(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                r.getString(3),
                r.getString(4),
                r.getObject(5, UUID.class),
                r.getObject(6) != null),
        ids.toArray());
  }

  public List<UUID> owners(UUID after, int size) {
    return jdbc.query(
        "SELECT owner_id FROM media.quota WHERE (?::uuid IS NULL OR owner_id>?) ORDER BY owner_id LIMIT ?",
        (r, n) -> r.getObject(1, UUID.class),
        after,
        after,
        size);
  }

  public Quota actualQuota(UUID owner) {
    return jdbc.queryForObject(
        "SELECT COALESCE(sum(CASE WHEN retained_bytes=0 THEN declared_bytes ELSE 0 END),0),COALESCE(sum(retained_bytes),0) FROM media.asset WHERE owner_id=? AND state<>'DELETED'",
        (r, n) -> new Quota(r.getLong(1), r.getLong(2), null, 0),
        owner);
  }
}
