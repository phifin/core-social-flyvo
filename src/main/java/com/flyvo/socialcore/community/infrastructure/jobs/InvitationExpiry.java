package com.flyvo.socialcore.community.infrastructure.jobs;

import com.flyvo.socialcore.community.application.port.CommunityRepository;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class InvitationExpiry implements JobHandler {
  private final CommunityRepository store;
  private final Clock clock;

  public InvitationExpiry(CommunityRepository store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public String kind() {
    return "COMMUNITY_INVITE_EXPIRY";
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(String payload) {
    var parts = payload.split(":");
    var id = UUID.fromString(parts[0]);
    var community = UUID.fromString(parts[1]);
    store.community(community, true, true);
    var invite = store.invitation(id);
    if (invite != null
        && invite.communityId().equals(community)
        && "PENDING".equals(invite.status())
        && !clock.instant().isBefore(invite.expiresAt()))
      store.resolveInvite(id, "EXPIRED", clock.instant());
  }
}
