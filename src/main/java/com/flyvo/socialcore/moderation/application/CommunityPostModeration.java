package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.content.api.PostModeration;
import com.flyvo.socialcore.moderation.application.port.ModerationActions;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityPostModeration {
  private final PostModeration posts;
  private final ModerationActions actions;
  private final DurableWork work;
  private final Clock clock;
  private final tools.jackson.databind.ObjectMapper json;
  private final ModerationSubjects subjects;

  public CommunityPostModeration(
      PostModeration posts,
      ModerationActions actions,
      DurableWork work,
      Clock clock,
      tools.jackson.databind.ObjectMapper json,
      ModerationSubjects subjects) {
    this.posts = posts;
    this.actions = actions;
    this.work = work;
    this.clock = clock;
    this.json = json;
    this.subjects = subjects;
  }

  public record Result(UUID actionId, long version, String status) {}

  @Transactional
  public Result apply(
      ActorContext actor, UUID community, UUID post, long expected, String action, String reason) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    if (reason == null
        || reason.isBlank()
        || reason.length() > 2000
        || action == null
        || !java.util.Set.of("REMOVE_CONTENT", "RESTORE_CONTENT").contains(action))
      throw Failure.invalid();
    subjects.post(actor, post);
    UUID id = UUID.randomUUID();
    var change = posts.apply(actor, community, post, expected, action, id);
    String request = org.slf4j.MDC.get("requestId");
    if (request == null) request = UUID.randomUUID().toString();
    actions.appendPost(
        id,
        actor.userId(),
        community,
        post,
        action,
        reason,
        json.writeValueAsString(Map.of("status", change.before())),
        json.writeValueAsString(Map.of("status", change.after())),
        request,
        clock.instant());
    work.auditScoped(actor.userId(), action, "post", post, community, "SUCCESS", null);
    work.event(
        "ModerationActionApplied",
        "moderation",
        id,
        0,
        0,
        json.writeValueAsString(
            Map.of("actionId", id, "communityId", community, "postId", post, "action", action)));
    return new Result(id, change.version(), change.after());
  }
}
