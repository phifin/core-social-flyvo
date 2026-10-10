package com.flyvo.socialcore.graph.application.port;

import com.flyvo.socialcore.graph.api.RelationshipQueries.Facts;
import com.flyvo.socialcore.graph.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GraphRepository {
  record Revision(UUID id, long version) {}

  record State(
      boolean forwardFollow,
      boolean reverseFollow,
      boolean friends,
      boolean forwardBlock,
      boolean reverseBlock,
      boolean forwardMute,
      boolean reverseMute) {
    public boolean blocked() {
      return forwardBlock || reverseBlock;
    }
  }

  void guard(long key, boolean exclusive);

  State state(UUID a, UUID b);

  List<Facts> facts(UUID viewer, List<UUID> users);

  boolean edge(String kind, UUID a, UUID b, boolean enabled, Instant now);

  boolean unfriend(UserPair pair);

  void befriend(FriendRequest request, Instant now);

  FriendRequest request(UUID id);

  FriendRequest pending(UserPair pair);

  void insert(FriendRequest request);

  void resolve(UUID id, String status, Instant now);

  Revision bump(UserPair pair);

  List<UUID> edges(UUID viewer, UUID owner, String kind, int size, UUID after);

  List<FriendRequest> requests(UUID actor, String direction, int size, UUID after, Instant now);
}
