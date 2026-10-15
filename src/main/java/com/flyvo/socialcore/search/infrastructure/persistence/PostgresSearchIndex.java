package com.flyvo.socialcore.search.infrastructure.persistence;

import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import com.flyvo.socialcore.search.domain.SearchTerms;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresSearchIndex implements SearchIndex {
  private final JdbcTemplate jdbc;

  public PostgresSearchIndex(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  @Transactional(readOnly = true)
  public Instant cutoff() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  @Transactional(readOnly = true)
  public List<Key> candidates(
      Kind kind, SearchTerms query, UUID scope, Instant cutoff, Key after, int limit) {
    if (limit < 1 || limit > 100) throw com.flyvo.socialcore.platform.api.Failure.invalid();
    var args = new ArrayList<Object>();
    args.add(query.terms());
    String extra = "", rank = "floor(ts_rank(search_vector,q.tsq,32)*1000000)::bigint";
    if (kind != Kind.POST) {
      rank +=
          " + CASE WHEN key_normalized=? THEN 2000000 ELSE 0 END"
              + " + CASE WHEN name_normalized=? THEN 1500000 ELSE 0 END"
              + " + CASE WHEN key_normalized LIKE ? ESCAPE E'\\\\' THEN 1000000 ELSE 0 END"
              + " + CASE WHEN name_normalized LIKE ? ESCAPE E'\\\\' THEN 500000 ELSE 0 END";
      Collections.addAll(
          args, query.normalized(), query.normalized(), query.prefix(), query.prefix());
      extra = " OR key_normalized LIKE ? ESCAPE E'\\\\' OR name_normalized LIKE ? ESCAPE E'\\\\'";
    }
    args.add(kind.name());
    args.add(time(cutoff));
    if (kind != Kind.POST) Collections.addAll(args, query.prefix(), query.prefix());
    if (scope != null) args.add(scope);
    String seek = "";
    if (after != null) {
      seek = " WHERE (rank_score<? OR (rank_score=? AND source_id>?))";
      Collections.addAll(args, after.rank(), after.rank(), after.id());
    }
    args.add(limit);
    // The bounded plain-term parser never concatenates query text into SQL.
    return jdbc.query(
        "WITH q AS (SELECT plainto_tsquery('simple'::regconfig,?) tsq), ranked AS ("
            + "SELECT source_id,"
            + rank
            + " rank_score FROM search.document CROSS JOIN q"
            + " WHERE source_kind=? AND NOT tombstone AND updated_at<=? AND (search_vector@@q.tsq"
            + extra
            + ")"
            + (scope == null ? "" : " AND scope_id=?")
            + ") SELECT source_id,rank_score FROM ranked"
            + seek
            + " ORDER BY rank_score DESC,source_id ASC LIMIT ?",
        (r, n) -> new Key(r.getLong(2), r.getObject(1, UUID.class)),
        args.toArray());
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public boolean upsert(Document d) {
    String key = d.tombstone() ? "" : d.key(),
        title = d.tombstone() ? "" : d.title(),
        body = d.tombstone() ? "" : d.body();
    return jdbc.update(
            """
        INSERT INTO search.document AS current(source_kind,source_id,source_version,owner_id,scope_id,account_version,
          tombstone,missing_source,source_key,title,body,key_normalized,name_normalized,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())
        ON CONFLICT(source_kind,source_id) DO UPDATE SET
          source_version=COALESCE(EXCLUDED.source_version,current.source_version),
          owner_id=COALESCE(EXCLUDED.owner_id,current.owner_id),
          scope_id=COALESCE(EXCLUDED.scope_id,current.scope_id),
          account_version=COALESCE(EXCLUDED.account_version,current.account_version),
          tombstone=EXCLUDED.tombstone,missing_source=EXCLUDED.missing_source,
          source_key=EXCLUDED.source_key,title=EXCLUDED.title,body=EXCLUDED.body,
          key_normalized=EXCLUDED.key_normalized,name_normalized=EXCLUDED.name_normalized,
          updated_at=EXCLUDED.updated_at
        WHERE NOT current.missing_source AND (EXCLUDED.missing_source OR (
          EXCLUDED.source_version>=current.source_version
          AND COALESCE(EXCLUDED.account_version,-1)>=COALESCE(current.account_version,-1)
          AND (current.owner_id IS NULL OR current.owner_id=EXCLUDED.owner_id)
          AND ROW(current.source_version,current.account_version,current.tombstone,current.source_key,current.title,current.body)
            IS DISTINCT FROM ROW(EXCLUDED.source_version,EXCLUDED.account_version,EXCLUDED.tombstone,EXCLUDED.source_key,EXCLUDED.title,EXCLUDED.body)))
        """,
            d.kind().name(),
            d.id(),
            d.version(),
            d.owner(),
            d.scope(),
            d.accountVersion(),
            d.tombstone(),
            d.missing(),
            key,
            title,
            body,
            SearchTerms.normalize(key),
            SearchTerms.normalize(title))
        == 1;
  }

  @Transactional(readOnly = true)
  public List<UUID> scan(Kind kind, UUID after, int limit) {
    if (limit < 1 || limit > 100) throw com.flyvo.socialcore.platform.api.Failure.invalid();
    return jdbc.queryForList(
        "SELECT source_id FROM search.document WHERE source_kind=?"
            + (after == null ? "" : " AND source_id>?")
            + " ORDER BY source_id LIMIT ?",
        UUID.class,
        after == null
            ? new Object[] {kind.name(), limit}
            : new Object[] {kind.name(), after, limit});
  }

  private static String fingerprintSql() {
    return "sha256(convert_to("
        + java.util.stream.Stream.of(
                "source_key", "title", "body", "key_normalized", "name_normalized")
            .map(c -> "octet_length(" + c + ")::text||':'||" + c)
            .collect(java.util.stream.Collectors.joining("||"))
        + ",'UTF8'))";
  }

  private static byte[] fingerprint(Document d) {
    String key = d.tombstone() ? "" : d.key(),
        title = d.tombstone() ? "" : d.title(),
        body = d.tombstone() ? "" : d.body();
    var encoded = new StringBuilder();
    for (String text :
        List.of(key, title, body, SearchTerms.normalize(key), SearchTerms.normalize(title)))
      encoded
          .append(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
          .append(':')
          .append(text);
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void verifySnapshots(List<Document> documents) {
    if (documents.isEmpty()) return;
    Kind kind = documents.getFirst().kind();
    if (documents.size() > 100 || documents.stream().anyMatch(d -> d.kind() != kind))
      throw com.flyvo.socialcore.platform.api.Failure.invalid();
    record Proof(
        Long version,
        Long account,
        boolean tombstone,
        boolean missing,
        UUID owner,
        UUID scope,
        byte[] checksum) {}
    var args = new ArrayList<Object>();
    args.add(kind.name());
    documents.forEach(d -> args.add(d.id()));
    var stored = new HashMap<UUID, Proof>();
    jdbc.query(
        "SELECT source_id,source_version,account_version,tombstone,missing_source,owner_id,scope_id,"
            + fingerprintSql()
            + " checksum FROM search.document WHERE source_kind=? AND source_id IN ("
            + String.join(",", Collections.nCopies(documents.size(), "?"))
            + ")",
        r -> {
          stored.put(
              r.getObject("source_id", UUID.class),
              new Proof(
                  r.getObject("source_version", Long.class),
                  r.getObject("account_version", Long.class),
                  r.getBoolean("tombstone"),
                  r.getBoolean("missing_source"),
                  r.getObject("owner_id", UUID.class),
                  r.getObject("scope_id", UUID.class),
                  r.getBytes("checksum")));
        },
        args.toArray());
    if (stored.size() != documents.size())
      throw new IllegalStateException("SEARCH_SNAPSHOT_COUNT_MISMATCH");
    for (var d : documents) {
      var p = stored.get(d.id());
      boolean equal =
          Objects.equals(p.version(), d.version())
              && Objects.equals(p.account(), d.accountVersion());
      boolean newer =
          p.version() != null
              && d.version() != null
              && p.version() >= d.version()
              && (p.account() == null ? -1 : p.account())
                  >= (d.accountVersion() == null ? -1 : d.accountVersion())
              && !equal;
      boolean valid =
          d.missing()
              ? p.missing() && p.tombstone() && Arrays.equals(p.checksum(), fingerprint(d))
              : !p.missing()
                  && Objects.equals(p.owner(), d.owner())
                  && Objects.equals(p.scope(), d.scope())
                  && (newer
                      || equal
                          && p.tombstone() == d.tombstone()
                          && Arrays.equals(p.checksum(), fingerprint(d)));
      if (!valid) throw new IllegalStateException("SEARCH_SNAPSHOT_CHECKSUM_MISMATCH");
    }
  }

  @Transactional(readOnly = true)
  public List<Owned> owned(UUID owner, Owned after, int limit) {
    if (limit < 1 || limit > 100) throw com.flyvo.socialcore.platform.api.Failure.invalid();
    return jdbc.query(
        "SELECT source_kind,source_id FROM search.document WHERE owner_id=?"
            + (after == null ? "" : " AND (source_kind,source_id)>(?,?)")
            + " ORDER BY source_kind,source_id LIMIT ?",
        (r, n) -> new Owned(Kind.valueOf(r.getString(1)), r.getObject(2, UUID.class)),
        after == null
            ? new Object[] {owner, limit}
            : new Object[] {owner, after.kind().name(), after.id(), limit});
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Rebuild rebuild(UUID id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT * FROM search.rebuild WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Rebuild(
                    r.getObject("id", UUID.class),
                    r.getObject("requested_by", UUID.class),
                    Kind.valueOf(r.getString("source_kind")),
                    r.getString("stage"),
                    r.getObject("after_id", UUID.class),
                    r.getLong("version"),
                    r.getLong("examined"),
                    r.getLong("changed"),
                    r.getObject("created_at", OffsetDateTime.class).toInstant(),
                    r.getObject("completed_at", OffsetDateTime.class) == null
                        ? null
                        : r.getObject("completed_at", OffsetDateTime.class).toInstant()),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Rebuild start(UUID id, UUID requester, Kind kind, Instant now) {
    var inserted =
        jdbc.query(
            """
        INSERT INTO search.rebuild(id,requested_by,source_kind,stage,created_at,updated_at)
        VALUES (?,?,?,'EXPORT',?,?) ON CONFLICT(source_kind) WHERE stage<>'DONE' DO NOTHING RETURNING id
        """,
            (r, n) -> r.getObject(1, UUID.class),
            id,
            requester,
            kind.name(),
            time(now),
            time(now));
    if (inserted.isEmpty())
      throw new com.flyvo.socialcore.platform.api.Failure(409, "REBUILD_ACTIVE");
    return rebuild(id, true);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void checkpoint(
      Rebuild r, String stage, UUID after, int examined, int changed, Instant now) {
    if (jdbc.update(
            "UPDATE search.rebuild SET stage=?,after_id=?,version=version+1,examined=examined+?,"
                + "changed=changed+?,updated_at=?,completed_at=? WHERE id=? AND version=?",
            stage,
            after,
            examined,
            changed,
            time(now),
            "DONE".equals(stage) ? time(now) : null,
            r.id(),
            r.version())
        != 1) throw new IllegalStateException("REBUILD_CHECKPOINT_CONFLICT");
  }
}
