package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;

public interface CommentModeration {
  PostModeration.Transition applyComment(
      ActorContext actor,
      UUID community,
      UUID comment,
      long expected,
      String action,
      UUID actionId);
}
