package com.flyvo.socialcore.notification.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.CommunityNotificationSources;
import com.flyvo.socialcore.content.api.ContentNotificationSources;
import com.flyvo.socialcore.graph.api.GraphNotificationSources;
import com.flyvo.socialcore.graph.api.RelationshipQueries;
import com.flyvo.socialcore.notification.api.NotificationQueries.Kind;
import com.flyvo.socialcore.notification.application.port.InboxRepository.Contribution;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NotificationEligibility {
  private final AccountQueries accounts;
  private final RelationshipQueries graph;
  private final ContentNotificationSources content;
  private final GraphNotificationSources requests;
  private final CommunityNotificationSources invitations;
  private final Clock clock;

  public NotificationEligibility(
      AccountQueries accounts,
      RelationshipQueries graph,
      ContentNotificationSources content,
      GraphNotificationSources requests,
      CommunityNotificationSources invitations,
      Clock clock) {
    this.accounts = accounts;
    this.graph = graph;
    this.content = content;
    this.requests = requests;
    this.invitations = invitations;
    this.clock = clock;
  }

  public List<Contribution> filter(UUID recipient, Kind kind, List<Contribution> batch) {
    if (batch.isEmpty()) return List.of();
    var actors = batch.stream().map(Contribution::actor).distinct().toList();
    var active = accounts.activeIds(actors);
    var allowedActors = new HashSet<UUID>();
    for (var f : graph.facts(recipient, actors))
      if (active.contains(f.userId())
          && !f.blocked()
          && !f.muted()
          && !f.userId().equals(recipient)) allowedActors.add(f.userId());
    var allowedSources = new HashSet<UUID>();
    for (var type : batch.stream().map(Contribution::sourceType).distinct().toList()) {
      var ids =
          batch.stream()
              .filter(c -> c.sourceType().equals(type))
              .map(Contribution::sourceId)
              .distinct()
              .toList();
      switch (type) {
        case "POST", "COMMENT", "REACTION" ->
            allowedSources.addAll(
                content.eligible(
                    recipient, ContentNotificationSources.Type.valueOf(type), kind.name(), ids));
        case "GRAPH" -> {
          for (var s : requests.inspect(ids)) {
            boolean valid =
                kind == Kind.FRIEND_REQUEST
                    ? s.addresseeId().equals(recipient)
                        && "PENDING".equals(s.status())
                        && clock.instant().isBefore(s.expiresAt())
                    : kind == Kind.FRIENDSHIP_ACCEPTED
                        && s.requesterId().equals(recipient)
                        && "ACCEPTED".equals(s.status())
                        && s.friends();
            if (valid) allowedSources.add(s.requestId());
          }
        }
        case "INVITATION" -> {
          for (var s : invitations.inspect(ids))
            if (kind == Kind.COMMUNITY_INVITATION
                && s.inviteeId().equals(recipient)
                && s.eligible()
                && "PENDING".equals(s.status())
                && clock.instant().isBefore(s.expiresAt())) allowedSources.add(s.invitationId());
        }
        default -> throw new IllegalStateException("UNKNOWN_SOURCE_TYPE");
      }
    }
    return batch.stream()
        .filter(c -> allowedActors.contains(c.actor()) && allowedSources.contains(c.sourceId()))
        .toList();
  }
}
