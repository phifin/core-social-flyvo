package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.community.api.MembershipQueries;
import com.flyvo.socialcore.moderation.api.CaseOperations;
import com.flyvo.socialcore.moderation.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class CaseWorkflow implements CaseOperations {
  private final CaseStore store;
  private final AccountQueries accounts;
  private final AccountCommands commands;
  private final MembershipQueries scopes;
  private final ModerationActions actions;
  private final ActionPayloads payloads;
  private final DurableWork work;
  private final ObjectMapper json;
  private final Clock clock;
  private final ModerationTelemetry telemetry;

  public CaseWorkflow(
      CaseStore store,
      AccountQueries accounts,
      AccountCommands commands,
      MembershipQueries scopes,
      ModerationActions actions,
      ActionPayloads payloads,
      DurableWork work,
      ObjectMapper json,
      Clock clock,
      ModerationTelemetry telemetry) {
    this.store = store;
    this.accounts = accounts;
    this.commands = commands;
    this.scopes = scopes;
    this.actions = actions;
    this.payloads = payloads;
    this.work = work;
    this.json = json;
    this.clock = clock;
    this.telemetry = telemetry;
  }

  private void guard(ActorContext actor, UUID target) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var ids = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
    ids.add(actor.userId());
    if (target != null) ids.add(target);
    for (var id : ids) {
      var a = accounts.guard(id, false);
      if (id.equals(actor.userId()) && !a.active()) throw Failure.unauthorized();
    }
  }

  private void scope(ActorContext actor, UUID community, boolean write) {
    if (community != null) scopes.guardScope(actor, community, true, write, false);
    else if (!"ADMIN".equals(accounts.find(actor.userId()).platformRole()))
      throw new Failure(403, "ACCESS_DENIED");
  }

  private CaseStore.Control control(Root type, UUID id, UUID community, boolean lock) {
    if (type == null || id == null) throw Failure.invalid();
    var root = new CaseStore.Root(type, id);
    var c = store.control(root, false);
    if (c == null || !Objects.equals(community, c.community())) throw new Failure(404, "NOT_FOUND");
    if (lock) {
      c = store.control(root, true);
      if (c == null || !Objects.equals(community, c.community()))
        throw new Failure(404, "NOT_FOUND");
    }
    return c;
  }

  private void page(int size, int offset) {
    if (size < 1 || size > 100 || offset < 0 || offset > 10000) throw Failure.invalid();
  }

  public Page queue(ActorContext actor, UUID community, Filter filter, int size, int offset) {
    guard(actor, null);
    scope(actor, community, false);
    page(size, offset);
    if (filter == null
        || filter.status() != null
            && !Set.of("OPEN", "IN_REVIEW", "RESOLVED", "DISMISSED").contains(filter.status())
        || filter.assignedTo() != null && filter.unassigned()) throw Failure.invalid();
    var rows = store.queue(community, filter, size, offset);
    audit(actor, community, "CASE_QUEUE_READ", community == null ? actor.userId() : community);
    return new Page(rows);
  }

  public CaseView assign(
      ActorContext actor, UUID community, UUID report, long expected, UUID assignee) {
    guard(actor, assignee);
    scope(actor, community, true);
    if (assignee != null) {
      var a = accounts.find(assignee);
      boolean eligible = a != null && a.active();
      if (community == null) eligible &= a != null && "ADMIN".equals(a.platformRole());
      else {
        var member = scopes.facts(assignee, List.of(community));
        eligible &=
            member.stream()
                .anyMatch(
                    m ->
                        m.status().equals("ACTIVE")
                            && Set.of("OWNER", "ADMIN", "MODERATOR").contains(m.role())
                            && (m.suspendedUntil() == null
                                || !m.suspendedUntil().isAfter(clock.instant())));
      }
      if (!eligible) throw new Failure(403, "ASSIGNEE_NOT_ELIGIBLE");
    }
    var c = control(Root.REPORT, report, community, true);
    version(c, expected);
    if (!Set.of("OPEN", "IN_REVIEW").contains(c.status())) throw Failure.conflict();
    store.assign(report, expected, assignee);
    store.history(report, actor.userId(), "ASSIGNED", null);
    audit(actor, community, "CASE_ASSIGNED", report);
    var next = store.control(c.root(), false);
    work.event(
        "ReportAssigned",
        "report",
        report,
        next.version(),
        0,
        json.writeValueAsString(Collections.singletonMap("reportId", report)));
    return store.caseView(report);
  }

  private void version(CaseStore.Control c, long expected) {
    if (expected < 0) throw Failure.invalid();
    if (c.version() != expected) throw new Failure(412, "STALE_VERSION");
  }

  private Control view(CaseStore.Control c) {
    return new Control(
        c.root().type(),
        c.root().id(),
        c.version(),
        c.holdUntil(),
        c.holdReason(),
        c.retentionState());
  }

  public Control hold(
      ActorContext actor, UUID community, Root type, UUID id, long expected, Hold hold) {
    guard(actor, null);
    scope(actor, community, false);
    if (hold == null) throw Failure.invalid();
    if (type == Root.ACTION) {
      var a = store.action(id);
      if (a == null || !Objects.equals(a.community(), community))
        throw new Failure(404, "NOT_FOUND");
      if (a.reportId() != null) throw new Failure(409, "CASE_HOLD_REQUIRED");
    }
    var c = control(type, id, community, true);
    version(c, expected);
    if (!c.retentionState().equals("NONE")) throw new Failure(409, "REDACTION_STARTED");
    if (hold.expiresAt() == null) {
      if (hold.reasonCode() != null) throw Failure.invalid();
    } else if (!hold.expiresAt().isAfter(c.now())
        || hold.expiresAt().isAfter(c.now().plus(Duration.ofDays(365)))
        || hold.reasonCode() == null
        || !hold.reasonCode().matches("[A-Z_]{1,64}")) throw Failure.invalid();
    store.hold(c.root(), expected, hold.expiresAt(), hold.reasonCode());
    if (type == Root.REPORT)
      store.history(
          id, actor.userId(), hold.expiresAt() == null ? "HOLD_RELEASED" : "HOLD_SET", null);
    work.auditScoped(
        actor.userId(),
        hold.expiresAt() == null ? "MODERATION_HOLD_RELEASED" : "MODERATION_HOLD_SET",
        "moderation",
        id,
        community,
        "SUCCESS",
        hold.expiresAt() == null ? c.holdReason() : hold.reasonCode());
    return view(store.control(c.root(), false));
  }

  public History history(ActorContext actor, UUID community, UUID report, int size, int offset) {
    guard(actor, null);
    scope(actor, community, false);
    page(size, offset);
    control(Root.REPORT, report, community, true);
    var rows =
        store.history(report, size, offset).stream()
            .map(
                h -> {
                  var p = h.payload();
                  var d =
                      p == null || p.redactedAt() != null
                          ? null
                          : payloads.read(p.id(), p.legacy(), p.ciphertext());
                  return new HistoryEntry(
                      h.id(),
                      h.version(),
                      h.actor(),
                      h.operation(),
                      h.actionId(),
                      h.assignee(),
                      h.holdUntil(),
                      h.created(),
                      p != null && p.redactedAt() != null,
                      d == null ? null : d.reason(),
                      d == null ? null : json.writeValueAsString(d.before()),
                      d == null ? null : json.writeValueAsString(d.after()));
                })
            .toList();
    audit(actor, community, "CASE_HISTORY_READ", report);
    return new History(rows);
  }

  public ActionView action(ActorContext actor, UUID community, UUID id) {
    guard(actor, null);
    scope(actor, community, false);
    var a = store.action(id);
    if (a == null || !Objects.equals(a.community(), community)) throw new Failure(404, "NOT_FOUND");
    var c =
        control(
            a.reportId() == null ? Root.ACTION : Root.REPORT,
            a.reportId() == null ? id : a.reportId(),
            community,
            true);
    var p = store.payload(id);
    var d = p.redactedAt() != null ? null : payloads.read(id, p.legacy(), p.ciphertext());
    audit(actor, community, "MODERATION_ACTION_READ", id);
    return new ActionView(
        a.id(),
        a.reportId(),
        a.actor(),
        a.community(),
        a.targetType(),
        a.target(),
        a.operation(),
        a.created(),
        p.redactedAt() != null,
        d == null ? null : d.reason(),
        d == null ? null : json.writeValueAsString(d.before()),
        d == null ? null : json.writeValueAsString(d.after()),
        view(c));
  }

  public AccountResult account(ActorContext actor, long expected, AccountRequest input) {
    if (input == null || input.userId() == null || input.action() == null) throw Failure.invalid();
    // Owner acquires the full sorted account set exclusively, before any action/root.
    var change =
        commands.changeStatus(
            actor,
            input.userId(),
            expected,
            input.action() == AccountAction.SUSPEND_ACCOUNT,
            input.reasonCode());
    var id = UUID.randomUUID();
    actions.appendCase(
        id,
        null,
        actor.userId(),
        null,
        "ACCOUNT",
        input.userId(),
        input.action().name(),
        input.reasonCode(),
        json.writeValueAsString(Map.of("status", change.before(), "version", expected)),
        json.writeValueAsString(Map.of("status", change.after(), "version", change.version())),
        request(),
        clock.instant());
    work.event(
        "ModerationActionApplied",
        "moderation",
        id,
        0,
        0,
        json.writeValueAsString(
            Map.of(
                "actionId",
                id,
                "targetType",
                "ACCOUNT",
                "targetId",
                input.userId(),
                "action",
                input.action().name())));
    return new AccountResult(id, change);
  }

  public AccountState accountState(ActorContext actor, UUID user) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    if (user == null) throw Failure.invalid();
    var observed = accounts.find(actor.userId());
    if (observed == null || !observed.active()) throw Failure.unauthorized();
    if (!"ADMIN".equals(observed.platformRole())) throw new Failure(403, "ACCESS_DENIED");
    if (accounts.find(user) == null) throw new Failure(404, "NOT_FOUND");
    guard(actor, user);
    scope(actor, null, false);
    var current = accounts.find(user);
    audit(actor, null, "ACCOUNT_MODERATION_READ", user);
    return new AccountState(user, current.status(), current.platformRole(), current.version());
  }

  public Progress maintenance(ActorContext actor, Maintenance operation, boolean resume) {
    guard(actor, null);
    scope(actor, null, false);
    if (operation == null) throw Failure.invalid();
    if (resume)
      work.job(
          "MODERATION_" + operation.name(), "operator:" + UUID.randomUUID(), "{}", clock.instant());
    var progress = store.progress();
    audit(
        actor,
        null,
        resume ? "MODERATION_MAINTENANCE_RESUME" : "MODERATION_MAINTENANCE_READ",
        actor.userId());
    return progress;
  }

  private String request() {
    var value = org.slf4j.MDC.get("requestId");
    return value == null ? UUID.randomUUID().toString() : value;
  }

  public void authorizeMetrics(ActorContext actor) {
    guard(actor, null);
    scope(actor, null, false);
    audit(actor, null, "MODERATION_METRICS_READ", actor.userId());
    telemetry.committed("encrypted", 0, store.progress());
  }

  private void audit(ActorContext actor, UUID community, String action, UUID id) {
    work.auditScoped(actor.userId(), action, "moderation", id, community, "SUCCESS", null);
  }
}
