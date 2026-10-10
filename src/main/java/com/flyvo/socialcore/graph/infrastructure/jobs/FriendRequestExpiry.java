package com.flyvo.socialcore.graph.infrastructure.jobs;

import com.flyvo.socialcore.graph.application.Relationships;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FriendRequestExpiry implements JobHandler {
  private final Relationships relationships;

  public FriendRequestExpiry(Relationships relationships) {
    this.relationships = relationships;
  }

  public String kind() {
    return "FRIEND_REQUEST_EXPIRY";
  }

  public void apply(String payload) {
    relationships.expire(UUID.fromString(payload));
  }
}
