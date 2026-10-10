package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.community.api.MembershipCommands;
import com.flyvo.socialcore.community.api.MembershipQueries;
import com.flyvo.socialcore.moderation.application.port.ModerationActions;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberModeration {
  private final MembershipCommands members;
  private final ModerationActions actions;
  private final DurableWork work;
  private final Clock clock;
  private final tools.jackson.databind.ObjectMapper json;

  public MemberModeration(
      MembershipCommands members,
      ModerationActions actions,
      DurableWork work,
      Clock clock,
      tools.jackson.databind.ObjectMapper json) {
    this.members = members;
    this.actions = actions;
    this.work = work;
    this.clock = clock;
    this.json = json;
  }

  public record Result(UUID id, MembershipQueries.Facts membership) {}

  @Transactional
  public Result apply(
      ActorContext actor,
      UUID community,
      UUID target,
      long version,
      String action,
      String reason,
      Instant until) {
    if (target == null
        || action == null
        || reason == null
        || reason.isBlank()
        || reason.length() > 2000) throw Failure.invalid();
    var transition = members.sanction(actor, community, target, version, action, until);
    var id = UUID.randomUUID();
    String request = MDC.get("requestId");
    if (request == null) request = UUID.randomUUID().toString();
    actions.append(
        id,
        actor.userId(),
        community,
        target,
        action,
        reason,
        json.writeValueAsString(transition.before()),
        json.writeValueAsString(transition.after()),
        request,
        clock.instant());
    work.auditScoped(
        actor.userId(), action, "member", target, community, "SUCCESS", "MEMBER_SANCTION");
    work.event(
        "ModerationActionApplied",
        "moderation",
        id,
        0,
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.moderation.api.event.ModerationActionApplied(
                id, community, target, action)));
    return new Result(id, transition.after());
  }
}
