package com.flyvo.socialcore.identity.api;

import com.flyvo.socialcore.platform.api.ActorContext;

public interface SessionQueries {
  void requireCurrent(ActorContext actor);
}
