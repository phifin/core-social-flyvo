package com.flyvo.socialcore.search.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SearchQueries {
  enum Kind {
    USER,
    COMMUNITY,
    POST
  }

  record Page<T>(List<T> items, String nextCursor) {}

  record User(UUID id, String handle, String name, long version) {}

  record Community(
      UUID id, String slug, String name, String snippet, String visibility, long version) {}

  record Post(
      UUID id,
      UUID authorId,
      String displayName,
      UUID communityId,
      String snippet,
      Instant publishedAt,
      long version) {}

  Page<User> users(ActorContext actor, String query, int size, String after);

  Page<Community> communities(ActorContext actor, String query, int size, String after);

  Page<Post> posts(ActorContext actor, String query, UUID communityId, int size, String after);
}
