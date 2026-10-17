package com.flyvo.socialcore.notification.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.CommunityNotificationSources;
import com.flyvo.socialcore.content.api.ContentNotificationSources;
import com.flyvo.socialcore.graph.api.GraphNotificationSources;
import com.flyvo.socialcore.notification.api.NotificationQueries.Kind;
import com.flyvo.socialcore.notification.application.port.InboxRepository;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationProjector implements EventConsumer {
  private final InboxRepository store;
  private final ContentNotificationSources content;
  private final GraphNotificationSources graph;
  private final CommunityNotificationSources community;
  private final AccountQueries accounts;
  private final NotificationEligibility eligibility;
  private final ObjectMapper json;
  private final Clock clock;
  private final io.micrometer.core.instrument.Timer lag;

  public NotificationProjector(
      InboxRepository store,
      ContentNotificationSources content,
      GraphNotificationSources graph,
      CommunityNotificationSources community,
      AccountQueries accounts,
      NotificationEligibility eligibility,
      ObjectMapper json,
      Clock clock,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.store = store;
    this.content = content;
    this.graph = graph;
    this.community = community;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.json = json;
    this.clock = clock;
    lag = metrics.timer("social.notification.delivery.lag");
  }

  public String name() {
    return "notification-inbox-v1";
  }

  public boolean accepts(String type) {
    return Set.of(
            "FriendRequestCreated",
            "FriendshipEstablished",
            "CommunityInvitationCreated",
            "PostCreated",
            "PostUpdated",
            "PostAudienceChanged",
            "PostDeleted",
            "PostRemoved",
            "PostRestored",
            "CommentCreated",
            "CommentUpdated",
            "CommentDeleted",
            "CommentRemoved",
            "CommentRestored",
            "ReactionChanged")
        .contains(type);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(EventEnvelope event) {
    var now = clock.instant();
    if (event.occurredAt().isBefore(now.minusSeconds(30L * 86400))) return;
    if (event.schemaVersion() != 1) throw new IllegalStateException("UNSUPPORTED_EVENT_SCHEMA");
    var payload = json.readTree(event.payload());
    UUID source = event.aggregateId(), actor;
    String sourceType, targetType;
    UUID target;
    boolean active, newActivity;
    boolean closesActivity = event.type().endsWith("Deleted") || event.type().endsWith("Removed");
    long currentVersion = event.aggregateVersion();
    ContentNotificationSources.Type contentType = null;
    String reactionType = null;
    var routes = new ArrayList<NotificationRoutes.Route>();
    if (event.type().startsWith("Friend")) {
      if (payload.path("requestId").isNull() || payload.path("requestId").isMissingNode()) return;
      source = UUID.fromString(payload.path("requestId").asText());
      var sources = graph.inspect(List.of(source));
      if (sources.isEmpty()) return;
      var s = sources.getFirst();
      boolean acceptance = event.type().equals("FriendshipEstablished");
      actor = acceptance ? s.addresseeId() : s.requesterId();
      routes.add(
          new NotificationRoutes.Route(
              acceptance ? s.requesterId() : s.addresseeId(),
              acceptance ? Kind.FRIENDSHIP_ACCEPTED : Kind.FRIEND_REQUEST));
      sourceType = "GRAPH";
      targetType = "FRIEND_REQUEST";
      target = source;
      active = true;
      newActivity = true;
    } else if (event.type().equals("CommunityInvitationCreated")) {
      source = UUID.fromString(payload.path("sourceId").asText());
      var sources = community.inspect(List.of(source));
      if (sources.isEmpty()) return;
      var s = sources.getFirst();
      actor = s.inviterId();
      routes.add(new NotificationRoutes.Route(s.inviteeId(), Kind.COMMUNITY_INVITATION));
      sourceType = "INVITATION";
      targetType = "COMMUNITY_INVITATION";
      target = source;
      active = true;
      newActivity = true;
    } else {
      sourceType = event.aggregateType().toUpperCase(Locale.ROOT);
      var type = ContentNotificationSources.Type.valueOf(sourceType);
      contentType = type;
      var sources = content.inspect(type, List.of(source));
      if (sources.isEmpty()) return;
      var s = sources.getFirst();
      actor = s.actorId();
      targetType = s.targetType();
      target = s.targetId();
      active = s.active();
      newActivity = event.type().endsWith("Created");
      switch (type) {
        case POST -> {
          for (var user : s.mentionedUsers())
            routes.add(new NotificationRoutes.Route(user, Kind.MENTION));
        }
        case COMMENT -> {
          routes.add(new NotificationRoutes.Route(s.postAuthorId(), Kind.COMMENT_ON_POST));
          routes.add(new NotificationRoutes.Route(s.rootAuthorId(), Kind.REPLY_TO_COMMENT));
          routes.add(new NotificationRoutes.Route(s.replyToUserId(), Kind.REPLY_TO_COMMENT));
        }
        case REACTION -> {
          routes.add(new NotificationRoutes.Route(s.targetAuthorId(), Kind.REACTION_ON_CONTENT));
          reactionType = active ? s.reactionType() : null;
          newActivity = !payload.path("type").isNull() && payload.path("previousType").isNull();
          closesActivity = payload.path("type").isNull();
        }
      }
    }
    var dedup = NotificationRoutes.deduplicate(actor, routes);
    var activeRecipients =
        accounts.activeIds(dedup.stream().map(NotificationRoutes.Route::recipient).toList());
    for (var route : dedup) {
      if (activeRecipients.contains(route.recipient())) store.guard(route.recipient(), now);
    }
    // Refresh once after the recipient serialization boundary: a concurrent
    // projection must not let a pre-lock source snapshot consume a late origin.
    if (contentType != null && !activeRecipients.isEmpty()) {
      var sources = content.inspect(contentType, List.of(source));
      if (sources.isEmpty()) return;
      var current = sources.getFirst();
      currentVersion = current.version();
      if (currentVersion < event.aggregateVersion())
        throw new IllegalStateException("UNAVAILABLE_SOURCE_VERSION");
      active = current.active();
      reactionType = active ? current.reactionType() : null;
    }
    for (var route : dedup) {
      if (!activeRecipients.contains(route.recipient())) continue;
      var contribution =
          new InboxRepository.Contribution(
              UUID.randomUUID(), null, actor, sourceType, source, event.aggregateVersion());
      boolean eligible =
          active
              && store.preferences(route.recipient()).get(route.kind())
              && !eligibility
                  .filter(route.recipient(), route.kind(), List.of(contribution))
                  .isEmpty();
      store.apply(
          new InboxRepository.Change(
              event.eventId(),
              route.recipient(),
              route.kind(),
              sourceType,
              source,
              actor,
              event.aggregateVersion(),
              currentVersion,
              targetType,
              target,
              eligible,
              reactionType,
              newActivity,
              closesActivity,
              event.occurredAt()),
          now);
    }
    lag.record(
        Duration.between(event.occurredAt(), now).isNegative()
            ? Duration.ZERO
            : Duration.between(event.occurredAt(), now));
  }
}
