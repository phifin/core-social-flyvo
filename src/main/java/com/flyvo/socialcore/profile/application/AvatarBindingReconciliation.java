package com.flyvo.socialcore.profile.application;

import com.flyvo.socialcore.media.api.MediaQueries;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.application.port.ProfileRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AvatarBindingReconciliation implements JobHandler {
  private final ProfileRepository store;
  private final MediaQueries media;
  private final DurableWork work;

  public AvatarBindingReconciliation(
      ProfileRepository store, MediaQueries media, DurableWork work) {
    this.store = store;
    this.media = media;
    this.work = work;
  }

  public String kind() {
    return "AVATAR_BINDING_RECONCILE";
  }

  public void apply(String payload) {
    var p = store.find(UUID.fromString(payload), false);
    if (p == null || p.avatarMediaId() == null) return;
    var fs = media.bindingFacts(List.of(p.avatarMediaId()));
    if (fs.size() != 1
        || !p.userId().equals(fs.getFirst().owner())
        || !p.userId().equals(fs.getFirst().context())
        || !"AVATAR".equals(fs.getFirst().purpose())
        || !"READY".equals(fs.getFirst().state())
        || fs.getFirst().released())
      work.audit(
          null,
          "MEDIA_BINDING_MISMATCH",
          "profile",
          p.userId(),
          "FAILURE",
          "RECONCILIATION_REQUIRED");
  }
}
