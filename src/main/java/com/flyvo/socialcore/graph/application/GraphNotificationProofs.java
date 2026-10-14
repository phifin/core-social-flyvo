package com.flyvo.socialcore.graph.application;

import com.flyvo.socialcore.graph.api.GraphNotificationSources;
import com.flyvo.socialcore.graph.application.port.GraphRepository;
import com.flyvo.socialcore.platform.api.Failure;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphNotificationProofs implements GraphNotificationSources {
  private final GraphRepository store;

  public GraphNotificationProofs(GraphRepository store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public List<Source> inspect(List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull))
      throw Failure.invalid();
    return store.notificationSources(ids);
  }
}
