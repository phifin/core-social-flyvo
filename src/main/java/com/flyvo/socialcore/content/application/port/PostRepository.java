package com.flyvo.socialcore.content.application.port;

import com.flyvo.socialcore.content.api.ContentQueries;
import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy.Facts;
import com.flyvo.socialcore.content.domain.post.Post;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PostRepository {
  Post find(UUID id, boolean lock);

  List<Post> load(List<UUID> ids);

  record SearchSource(
      UUID id, UUID authorId, UUID communityId, long version, String status, String body) {}

  List<SearchSource> searchSources(List<UUID> ids);

  List<UUID> searchScan(UUID after, int limit);

  List<Post> searchLoad(UUID viewer, List<UUID> ids, UUID community, Instant now);

  Instant databaseTime();

  List<Key> feedCandidates(
      UUID viewer, ContentQueries.FeedScope scope, int limit, Instant asOf, Key after, Instant now);

  List<Post> feedLoad(UUID viewer, ContentQueries.FeedScope scope, List<UUID> ids, Instant now);

  void create(Post post);

  void save(Post post);

  void revision(Post post, UUID editor, Instant now);

  List<ContentQueries.Revision> revisions(UUID post, int limit, int before, Instant retainedSince);

  record Key(UUID id, Instant published) {}

  List<Key> candidates(
      UUID viewer, UUID author, UUID community, int limit, Instant asOf, Key after, Instant now);

  Map<UUID, Facts> facts(UUID viewer, List<UUID> ids, Instant now);

  boolean pruneRevisions(UUID post, Instant cutoff);

  void redactDeleted(UUID post, Instant now);
}
