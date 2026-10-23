package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.*;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.moderation.api.Reports;
import com.flyvo.socialcore.moderation.application.port.*;
import com.flyvo.socialcore.moderation.domain.Report;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportWorkflow implements Reports {
  private final ReportRepository store;
  private final ModerationActions actions;
  private final AccountQueries accounts;
  private final MembershipQueries scopes;
  private final MembershipCommands members;
  private final CommunityCommands communities;
  private final ContentReportProof content;
  private final PostModeration posts;
  private final CommentModeration comments;
  private final ProfileQueries profiles;
  private final Idempotency keys;
  private final Secrets secrets;
  private final DurableWork work;
  private final Clock clock;
  private final ObjectMapper json;
  private final CaseStore cases;
  private final ModerationSubjects subjects;

  public ReportWorkflow(
      ReportRepository store,
      ModerationActions actions,
      AccountQueries accounts,
      MembershipQueries scopes,
      MembershipCommands members,
      CommunityCommands communities,
      ContentReportProof content,
      PostModeration posts,
      CommentModeration comments,
      ProfileQueries profiles,
      Idempotency keys,
      Secrets secrets,
      DurableWork work,
      Clock clock,
      ObjectMapper json,
      CaseStore cases,
      ModerationSubjects subjects) {
    this.store = store;
    this.actions = actions;
    this.accounts = accounts;
    this.scopes = scopes;
    this.members = members;
    this.communities = communities;
    this.content = content;
    this.posts = posts;
    this.comments = comments;
    this.profiles = profiles;
    this.keys = keys;
    this.secrets = secrets;
    this.work = work;
    this.clock = clock;
    this.json = json;
    this.cases = cases;
    this.subjects = subjects;
  }

  public record Captured(Instant capturedAt, String detail, String summary) {}

  private void actor(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
  }

  private void guard(ActorContext actor, UUID... users) {
    actor(actor);
    for (var id :
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(actor.userId()), Arrays.stream(users))
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList()) {
      var a = accounts.guard(id, false);
      if (id.equals(actor.userId()) && !a.active()) throw Failure.unauthorized();
    }
  }

  private View view(Report r) {
    return new View(
        r.id(),
        Target.valueOf(r.targetType()),
        r.targetId(),
        r.community(),
        Category.valueOf(r.category()),
        r.status(),
        r.created(),
        r.resolved(),
        r.version());
  }

  private Report existing(UUID id, boolean lock) {
    var r = store.find(id, lock);
    if (r == null) throw new Failure(404, "NOT_FOUND");
    return r;
  }

  private void scope(ActorContext actor, UUID community, boolean write, boolean exclusive) {
    if (community != null) scopes.guardScope(actor, community, true, write, exclusive);
    else {
      var a = accounts.find(actor.userId());
      if (a == null || !a.active() || !a.platformRole().equals("ADMIN"))
        throw new Failure(403, "ACCESS_DENIED");
    }
  }

  private void sameScope(Report r, UUID community) {
    if (!Objects.equals(r.community(), community)) throw new Failure(404, "NOT_FOUND");
  }

  private String bounded(String text) {
    if (text == null) return "";
    return text.substring(
        0, text.offsetByCodePoints(0, Math.min(2000, text.codePointCount(0, text.length()))));
  }

  @Transactional
  public View submit(ActorContext actor, Submit input, String key) {
    actor(actor);
    if (input == null
        || input.targetType() == null
        || input.targetId() == null
        || input.category() == null
        || input.detail() != null
            && input.detail().codePointCount(0, input.detail().length()) > 4000)
      throw Failure.invalid();
    UUID community = null, author = null, parent = null;
    String summary;
    // These owner calls acquire the complete lock set BEFORE a generic idempotency
    // record or report lock. Replay reauthorizes the target, never cached evidence.
    switch (input.targetType()) {
      case POST, COMMENT -> {
        var p =
            content.reportProof(
                actor,
                input.targetType() == Target.POST ? input.targetId() : null,
                input.targetType() == Target.COMMENT ? input.targetId() : null);
        community = p.communityId();
        author = p.authorId();
        parent = p.parentAuthorId();
        summary = bounded(p.body());
      }
      case USER -> {
        var p = profiles.visible(actor, input.targetId(), true);
        author = p.userId();
        summary = bounded(p.handle() + "\n" + p.displayName() + "\n" + p.bio());
      }
      case COMMUNITY -> {
        var p = communities.reportProof(actor, input.targetId());
        community = p.id();
        summary = bounded(p.name() + "\n" + p.description());
      }
      default -> throw Failure.invalid();
    }
    String route = "reports";
    var replay = keys.claim(actor.userId(), route, key, json.writeValueAsString(input));
    if (replay != null) {
      var r = existing(replay, false);
      if (!r.reporter().equals(actor.userId())) throw new Failure(404, "NOT_FOUND");
      return view(r);
    }
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var id = UUID.randomUUID();
    var encrypted =
        secrets.encrypt(
            "report:" + id, json.writeValueAsString(new Captured(now, input.detail(), summary)));
    if (encrypted.length > 65536) throw Failure.invalid();
    var r =
        store.insertOrUnresolved(
            new Report(
                id,
                actor.userId(),
                input.targetType().name(),
                input.targetId(),
                community,
                author,
                parent,
                input.category().name(),
                encrypted,
                secrets.keyId(),
                "OPEN",
                now,
                null,
                0));
    if (r.id().equals(id)) {
      cases.history(id, actor.userId(), "SUBMITTED", null);
      work.auditScoped(
          actor.userId(), "REPORT_SUBMITTED", "report", id, community, "SUCCESS", null);
      work.event(
          "ReportSubmitted",
          "report",
          id,
          0,
          0,
          json.writeValueAsString(
              Map.of("reportId", id, "targetType", r.targetType(), "targetId", r.targetId())));
    }
    keys.complete(actor.userId(), route, key, r.id());
    return view(r);
  }

  private void page(int size, int offset) {
    if (size < 1 || size > 100 || offset < 0 || offset > 10000) throw Failure.invalid();
  }

  @Transactional
  public Page own(ActorContext actor, int size, int offset) {
    guard(actor);
    page(size, offset);
    work.audit(actor.userId(), "OWN_REPORTS_READ", "account", actor.userId(), "SUCCESS", null);
    return new Page(
        store.page(actor.userId(), null, size, offset).stream().map(this::view).toList(), null);
  }

  @Transactional
  public Page queue(ActorContext actor, UUID community, int size, int offset) {
    guard(actor);
    page(size, offset);
    scope(actor, community, false, false);
    work.auditScoped(
        actor.userId(),
        "CASE_QUEUE_READ",
        "moderation",
        community == null ? actor.userId() : community,
        community,
        "SUCCESS",
        null);
    return new Page(
        store.page(null, community, size, offset).stream().map(this::view).toList(), null);
  }

  @Transactional
  public Evidence evidence(ActorContext actor, UUID community, UUID id) {
    guard(actor);
    scope(actor, community, false, false);
    var root =
        new CaseStore.Root(com.flyvo.socialcore.moderation.api.CaseOperations.Root.REPORT, id);
    var metadata = cases.control(root, false);
    if (metadata == null || !Objects.equals(metadata.community(), community))
      throw new Failure(404, "NOT_FOUND");
    cases.control(root, true);
    var r = existing(id, false);
    sameScope(r, community);
    var snapshot =
        r.evidence() == null
            ? null
            : json.readValue(secrets.decrypt("report:" + id, r.evidence()), Captured.class);
    work.auditScoped(
        actor.userId(), "REPORT_EVIDENCE_READ", "report", id, community, "SUCCESS", null);
    return new Evidence(
        view(r),
        snapshot == null ? null : snapshot.detail(),
        snapshot == null ? null : snapshot.capturedAt(),
        snapshot == null ? null : snapshot.summary(),
        snapshot == null);
  }

  @Transactional
  public Result resolve(ActorContext actor, UUID community, long expected, Resolution input) {
    actor(actor);
    if (input == null
        || input.reportId() == null
        || input.action() == null
        || input.reason() == null
        || input.reason().isBlank()
        || input.reason().codePointCount(0, input.reason().length()) > 2000)
      throw Failure.invalid();
    var metadata =
        cases.control(
            new CaseStore.Root(
                com.flyvo.socialcore.moderation.api.CaseOperations.Root.REPORT, input.reportId()),
            false);
    if (metadata == null || !Objects.equals(metadata.community(), community))
      throw new Failure(404, "NOT_FOUND");
    subjects.existing(actor, metadata.root());
    boolean sanction =
        Set.of(Action.SUSPEND_MEMBER, Action.BAN_MEMBER, Action.UNBAN_MEMBER)
            .contains(input.action());
    if (input.sourceVersion() != null && input.sourceVersion() < 0
        || input.suspendedUntil() != null && input.action() != Action.SUSPEND_MEMBER)
      throw Failure.invalid();
    scope(actor, community, true, sanction);
    var first = existing(input.reportId(), false);
    sameScope(first, community);
    if (first.version() != expected) throw new Failure(412, "STALE_VERSION");
    if (!Set.of("OPEN", "IN_REVIEW").contains(first.status())) throw Failure.conflict();
    UUID actionId = UUID.randomUUID(), target = first.id();
    String targetType = "REPORT";
    Object before = Map.of("status", first.status()), after;
    boolean review = input.action() == Action.REVIEW_REPORT,
        dismiss = input.action() == Action.DISMISS_REPORT;
    switch (input.action()) {
      case REMOVE_CONTENT, RESTORE_CONTENT -> {
        if (community == null
            || input.sourceVersion() == null
            || !Set.of("POST", "COMMENT").contains(first.targetType())) throw Failure.invalid();
        var change =
            first.targetType().equals("POST")
                ? posts.apply(
                    actor,
                    community,
                    first.targetId(),
                    input.sourceVersion(),
                    input.action().name(),
                    actionId)
                : comments.applyComment(
                    actor,
                    community,
                    first.targetId(),
                    input.sourceVersion(),
                    input.action().name(),
                    actionId);
        target = first.targetId();
        targetType = first.targetType();
        before = Map.of("status", change.before());
        after = Map.of("status", change.after());
      }
      case SUSPEND_MEMBER, BAN_MEMBER, UNBAN_MEMBER -> {
        if (community == null
            || input.sourceVersion() == null
            || first.author() == null
            || !Set.of("POST", "COMMENT").contains(first.targetType())) throw Failure.invalid();
        var change =
            members.sanction(
                actor,
                community,
                first.author(),
                input.sourceVersion(),
                input.action().name(),
                input.suspendedUntil());
        target = first.author();
        targetType = "MEMBER";
        before = change.before();
        after = change.after();
      }
      default -> {
        if (input.sourceVersion() != null || input.suspendedUntil() != null)
          throw Failure.invalid();
        after = Map.of("status", review ? "IN_REVIEW" : dismiss ? "DISMISSED" : "RESOLVED");
      }
    }
    var current = existing(first.id(), true);
    if (current.version() != expected) throw new Failure(412, "STALE_VERSION");
    Report next;
    try {
      next = current.transition(review, dismiss, clock.instant());
    } catch (IllegalStateException e) {
      throw Failure.conflict();
    }
    store.save(next, clock.instant());
    String request = org.slf4j.MDC.get("requestId");
    if (request == null) request = UUID.randomUUID().toString();
    actions.appendCase(
        actionId,
        current.id(),
        actor.userId(),
        community,
        targetType,
        target,
        input.action().name(),
        input.reason(),
        json.writeValueAsString(before),
        json.writeValueAsString(after),
        request,
        clock.instant());
    cases.history(current.id(), actor.userId(), input.action().name(), actionId);
    work.auditScoped(
        actor.userId(), input.action().name(), "report", current.id(), community, "SUCCESS", null);
    work.event(
        "ModerationActionApplied",
        "moderation",
        actionId,
        0,
        0,
        json.writeValueAsString(
            Map.of(
                "actionId",
                actionId,
                "reportId",
                current.id(),
                "targetType",
                targetType,
                "targetId",
                target,
                "action",
                input.action().name())));
    work.event(
        review ? "ReportReviewed" : "ReportResolved",
        "report",
        next.id(),
        next.version(),
        0,
        json.writeValueAsString(Map.of("reportId", next.id(), "status", next.status())));
    return new Result(actionId, view(next));
  }
}
