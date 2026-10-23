package com.flyvo.socialcore.graph.application.port;

import java.util.List;
import java.util.UUID;

/** Indexed reverse traversal of live relations; immutable pair revisions are retained. */
public interface GraphErasureStore {
  List<UUID> counterparts(UUID subject, int limit);
}
