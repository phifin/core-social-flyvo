package com.flyvo.socialcore.identity.application.port;

import com.flyvo.socialcore.platform.api.ActorContext;

public interface AccessTokens {
  String issue(ActorContext actor);

  ActorContext validate(String token);
}
