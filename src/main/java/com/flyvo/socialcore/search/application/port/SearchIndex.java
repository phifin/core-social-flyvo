package com.flyvo.socialcore.search.application.port;

import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import com.flyvo.socialcore.search.domain.SearchTerms;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Real projection boundary: candidates expose only IDs/ranks, never stored text as a fallback. */
public interface SearchIndex {
  record Document(
      Kind kind,
      UUID id,
      Long version,
      UUID owner,
      UUID scope,
      Long accountVersion,
      boolean tombstone,
      boolean missing,
      String key,
      String title,
      String body) {
    public static Document absent(Kind kind, UUID id) {
      return new Document(kind, id, null, null, null, null, true, true, "", "", "");
    }
  }

  record Key(long rank, UUID id) {}

  record Owned(Kind kind, UUID id) {}

  record Rebuild(
      UUID id,
      UUID requester,
      Kind kind,
      String stage,
      UUID after,
      long version,
      long examined,
      long changed,
      Instant createdAt,
      Instant completedAt) {}

  Instant cutoff();

  List<Key> candidates(
      Kind kind, SearchTerms query, UUID scope, Instant cutoff, Key after, int limit);

  boolean upsert(Document document);

  void erase(UUID owner, Kind kind, UUID id);

  /**
   * Bounded count/checksum comparison; accepts genuinely newer stored vectors, not mixed stale
   * state.
   */
  void verifySnapshots(List<Document> documents);

  List<UUID> scan(Kind kind, UUID after, int limit);

  List<Owned> owned(UUID owner, Owned after, int limit);

  Rebuild rebuild(UUID id, boolean lock);

  Rebuild start(UUID id, UUID requester, Kind kind, Instant now);

  void checkpoint(
      Rebuild previous, String stage, UUID after, int examined, int changed, Instant now);
}
