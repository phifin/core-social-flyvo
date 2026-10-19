package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.application.port.AccountRepository;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import com.flyvo.socialcore.platform.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class Erasures implements AccountErasure {
  private final AccountRepository accounts;
  private final ErasureStore store;
  private final DurableWork work;
  private final RecoveryArchive archive;
  private final ScopedWork scoped;
  private final ErasurePolicy policy;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public Erasures(
      AccountRepository accounts,
      ErasureStore store,
      DurableWork work,
      RecoveryArchive archive,
      ScopedWork scoped,
      ErasurePolicy policy,
      ObjectMapper json,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.accounts = accounts;
    this.store = store;
    this.work = work;
    this.archive = archive;
    this.scoped = scoped;
    this.policy = policy;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  public record Journal(
      UUID lineage,
      UUID recordId,
      UUID requestId,
      UUID subject,
      String protocol,
      String policy,
      String rosterDigest,
      long generation,
      long round,
      String operation,
      String owner,
      String resourceKind,
      List<UUID> resourceIds,
      Instant recordedAt,
      Instant effectiveNotBefore,
      List<Prerequisite> prerequisites) {}

  public record Prerequisite(Step step, long version, long round, String proof) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID prepare(UUID user, long expected) {
    policy.requireEnabled();
    var account = accounts.guard(user, true);
    if (account == null || !"ACTIVE".equals(account.status())) throw Failure.unauthorized();
    if (account.version() != expected) throw new Failure(412, "STALE_VERSION");
    var intent = store.prepare(user, expected, policy.lineage());
    if (store.export(intent.id(), "PREPARED") == null)
      addExport(intent, "PREPARED", null, null, null);
    return intent.id();
  }

  private UUID addExport(
      ErasureStore.Intent i, String operation, String owner, String kind, List<UUID> ids) {
    var id = UUID.randomUUID();
    var request = store.request(i.user());
    if (!i.matches(request)) request = null;
    if (!"PREPARED".equals(operation) && request == null)
      throw new IllegalStateException("ERASURE_EXPORT_REQUEST_MISMATCH");
    var record =
        new Journal(
            i.lineage(),
            id,
            i.id(),
            i.user(),
            PROTOCOL,
            POLICY,
            ErasureProtocol.rosterDigest(),
            i.expectedVersion() + 1,
            request == null ? 1 : request.round(),
            operation,
            owner,
            kind,
            ids,
            store.now(),
            request == null ? i.createdAt() : request.at(),
            List.of());
    var encoded = json.writeValueAsString(record);
    store.addExport(
        id, i.id(), operation, owner, kind, ids, encoded, ErasureProtocol.digest(encoded));
    scheduleExport(store.export(id));
    return id;
  }

  private void scheduleExport(ErasureStore.Export export) {
    work.job("ERASURE_EXPORT", export.execution().toString(), export.id().toString(), store.now());
  }

  public void exportPrepared(UUID intentId) {
    policy.requireEnabled();
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("ARCHIVE_UNDER_TRANSACTION");
    var export = tx.execute(s -> store.export(intentId, "PREPARED"));
    if (export == null) throw new Failure(503, "RECOVERY_INTENT_MISSING");
    if (!policy.lineage().equals(tx.execute(s -> store.intent(intentId)).lineage()))
      throw new Failure(503, "RECOVERY_LINEAGE_MISMATCH");
    var receipt =
        archive.append(
            export.id(), export.digest(), export.record().getBytes(StandardCharsets.UTF_8));
    archive.verify(receipt);
    tx.executeWithoutResult(
        s -> {
          if (!export.id().equals(receipt.recordId()) || !export.digest().equals(receipt.digest()))
            throw new IllegalStateException("ARCHIVE_RECEIPT_MISMATCH");
          store.receipt(export.id(), receipt.digest());
        });
  }

  /** Called only by account's guarded lifecycle command in its originating TX. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void committed(UUID user, long generation, Instant at) {
    policy.requireEnabled();
    var intent = store.prepare(user, generation - 1, policy.lineage());
    var prepared = store.export(intent.id(), "PREPARED");
    if (prepared == null || !prepared.digest().equals(prepared.receipt()))
      throw new Failure(503, "RECOVERY_RECEIPT_REQUIRED");
    store.createRequest(intent, at, generation, ErasureProtocol.rosterDigest());
    for (var step : Step.values())
      store.seed(
          user,
          intent.id(),
          step,
          step == Step.RECOVERY_INTENT ? "RUNNABLE" : "WAITING_DEPENDENCY",
          step.kind() == null
              ? "OWNER_NOT_IMPLEMENTED"
              : step == Step.RECOVERY_INTENT ? null : "PREREQUISITE_PENDING");
    addExport(intent, "COMMITTED", null, null, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Context begin(UUID user, Step step, long expected) {
    policy.requireEnabled();
    var account = accounts.guard(user, false);
    if (account == null || !"DELETION_PENDING".equals(account.status())) throw Failure.conflict();
    var request = store.request(user);
    if (request == null || step == null) throw Failure.conflict();
    if (!policy.lineage().equals(store.intent(request.id()).lineage()))
      throw new Failure(503, "RECOVERY_LINEAGE_MISMATCH");
    var state = store.step(user, step, true);
    if (state == null
        || state.view().version() != expected
        || "DONE".equals(state.view().status())
        || "FAILED".equals(state.view().status())) return null;
    if (step.kind() == null) throw new Failure(409, "OWNER_NOT_IMPLEMENTED");
    for (var prerequisite : ErasureProtocol.predecessors(step))
      if (!"DONE".equals(store.step(user, prerequisite, false).view().status())) return null;
    return new Context(
        user,
        request.id(),
        step,
        expected,
        request.round(),
        request.at(),
        state.checkpoint(),
        state.examined());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void advance(
      Context c,
      String checkpoint,
      int examined,
      String status,
      String reason,
      Instant due,
      String proof) {
    if (c == null
        || examined < 0
        || examined > 100
        || checkpoint == null
        || !checkpoint.matches("[A-Za-z0-9_:-]{1,128}")
        || !Set.of(
                "RUNNABLE",
                "DONE",
                "WAITING_DEPENDENCY",
                "WAITING_EXTERNAL",
                "WAITING_RETENTION",
                "WAITING_CASE",
                "WAITING_HOLD")
            .contains(status)
        || reason != null && !reason.matches("[A-Z_]{1,64}")
        || proof != null && !proof.matches("[A-Za-z0-9_:-]{1,128}")) throw Failure.invalid();
    var current = begin(c.userId(), c.step(), c.version());
    if (!c.equals(current)) throw new Failure(409, "ERASURE_STALE_PASS");
    if ("DONE".equals(status)
        && (proof == null || store.pendingResources(c.requestId(), c.step().owner())))
      throw new Failure(409, "ERASURE_RESOURCE_RECEIPT_REQUIRED");
    var execution = UUID.randomUUID();
    store.advance(c, checkpoint, examined, status, reason, due, proof, execution);
    work.audit(null, "ERASURE_PASS", "account", c.userId(), "SUCCESS", c.step().name());
    if ("RUNNABLE".equals(status) || due != null)
      schedule(c.userId(), c.step(), c.version() + 1, execution, due == null ? store.now() : due);
    if ("DONE".equals(status))
      work.job(
          "ERASURE_RECONCILE", UUID.randomUUID().toString(), c.userId().toString(), store.now());
  }

  private void schedule(UUID user, Step step, long version, UUID execution, Instant due) {
    if (step.kind() == null || step == Step.RECOVERY_INTENT) return;
    work.job(
        step.kind(),
        execution.toString(),
        json.writeValueAsString(new Pass(user, step, version, execution)),
        due);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void executionFailed(Pass pass, int attempts) {
    accounts.guard(pass.userId(), false);
    var state = store.step(pass.userId(), pass.step(), true);
    if (state != null
        && state.view().version() == pass.version()
        && pass.executionId().equals(state.execution()))
      store.failed(pass.userId(), pass.step(), attempts >= 10);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID recordResources(Context c, String kind, List<UUID> ids) {
    if (c == null
        || ids == null
        || ids.isEmpty()
        || ids.size() > 100
        || ids.stream().anyMatch(Objects::isNull)
        || ids.stream().distinct().count() != ids.size()
        || !Set.of("MAIL_PROOF", "MEDIA_ASSET").contains(kind)) throw Failure.invalid();
    if (!c.equals(begin(c.userId(), c.step(), c.version()))) throw Failure.conflict();
    var old = store.lockManifest(c.requestId());
    var chunk = addExport(store.intent(c.requestId()), "RESOURCE", c.step().owner(), kind, ids);
    store.manifest(
        c.requestId(),
        ErasureProtocol.digest(old + ":" + chunk + ":" + store.export(chunk).digest()));
    return chunk;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean resourcesExported(Context c, UUID chunk) {
    if (!c.equals(begin(c.userId(), c.step(), c.version()))) throw Failure.conflict();
    var e = store.export(chunk);
    return e != null
        && e.intent().equals(c.requestId())
        && "RESOURCE".equals(e.operation())
        && e.digest().equals(e.receipt());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> resourceIds(Context c, UUID chunk) {
    if (!resourcesExported(c, chunk)) throw new Failure(409, "ERASURE_RESOURCE_RECEIPT_REQUIRED");
    var r = store.resource(chunk);
    if (!c.step().owner().equals(r.owner())) throw Failure.conflict();
    return r.ids();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean stepDone(Context c, Step step) {
    if (!c.equals(begin(c.userId(), c.step(), c.version()))) throw Failure.conflict();
    return "DONE".equals(store.step(c.userId(), step, false).view().status());
  }

  @Transactional(readOnly = true)
  public List<UUID> scanSubjects(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw Failure.invalid();
    return store.scan(after, limit);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Instant databaseTime() {
    policy.requireEnabled();
    return store.now();
  }

  /** Reconcile fixed rows only; never dispatch unknown handlers or replace DEAD budgets. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reconcile(UUID user) {
    policy.requireEnabled();
    var account = accounts.guard(user, true);
    var request = store.request(user);
    if (request == null) {
      if (account == null || !Set.of("DELETION_PENDING", "DELETED").contains(account.status()))
        return;
      String issue =
          "DELETED".equals(account.status())
              ? "HISTORICAL_DELETED_REQUIRES_II"
              : account.deletionRequestedAt() == null
                      || account.version() < 1
                      || account.deletionRequestedAt().isAfter(store.now())
                  ? "HISTORICAL_LIFECYCLE_UNPROVEN"
                  : null;
      if (issue != null) {
        if (store.issue(user, issue))
          work.audit(null, "ERASURE_RECONCILIATION_FAILED", "account", user, "DENIED", issue);
        return;
      }
      var intent = store.prepare(user, account.version() - 1, policy.lineage());
      store.createRequest(
          intent, account.deletionRequestedAt(), account.version(), ErasureProtocol.rosterDigest());
      for (var step : Step.values())
        store.seed(
            user,
            intent.id(),
            step,
            "WAITING_DEPENDENCY",
            step.kind() == null
                ? "OWNER_NOT_IMPLEMENTED"
                : step == Step.RECOVERY_INTENT
                    ? "HISTORICAL_BACKUP_UNPROVEN"
                    : "PREREQUISITE_PENDING");
      addExport(intent, "PREPARED", null, null, null);
      work.audit(
          null,
          "ERASURE_HISTORICAL_REQUEST",
          "account",
          user,
          "SUCCESS",
          "HISTORICAL_BACKUP_UNPROVEN");
      return;
    }
    for (var step : Step.values()) {
      if (step.kind() == null || step == Step.RECOVERY_INTENT) continue;
      var state = store.step(user, step, true);
      if (!Set.of("DONE", "FAILED").contains(state.view().status())
          && state.execution() != null
          && "DEAD".equals(scoped.executionStatus(step.kind(), state.execution()))) {
        store.failed(user, step, true);
        continue;
      }
      if (!"WAITING_DEPENDENCY".equals(state.view().status())
          || !"PREREQUISITE_PENDING".equals(state.view().reason())) continue;
      if (ErasureProtocol.predecessors(step).stream()
          .anyMatch(p -> !"DONE".equals(store.step(user, p, false).view().status()))) continue;
      var c =
          new Context(
              user,
              request.id(),
              step,
              state.view().version(),
              request.round(),
              request.at(),
              state.checkpoint(),
              state.examined());
      var execution = UUID.randomUUID();
      store.advance(c, c.checkpoint(), 0, "RUNNABLE", null, null, null, execution);
      schedule(user, step, c.version() + 1, execution, store.now());
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void preparedRecovered(UUID intentId, long round) {
    var intent = store.intent(intentId);
    var account = accounts.guard(intent.user(), false);
    var request = store.request(intent.user());
    if (account != null
        && "DELETION_PENDING".equals(account.status())
        && intent.matches(request)
        && request.round() == round
        && store.export(intentId, "COMMITTED") == null)
      addExport(intent, "COMMITTED", null, null, null);
  }

  private void admin(ActorContext actor, UUID user) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    if (user == null) throw Failure.invalid();
    var observed = accounts.find(actor.userId());
    if (observed == null
        || !"ACTIVE".equals(observed.status())
        || !"ADMIN".equals(observed.platformRole())) throw new Failure(403, "ACCESS_DENIED");
    for (var id :
        java.util.stream.Stream.of(actor.userId(), user)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList()) {
      var a = accounts.guard(id, true);
      if (a == null) throw new Failure(404, "NOT_FOUND");
      if (id.equals(actor.userId())
          && (!"ACTIVE".equals(a.status()) || !"ADMIN".equals(a.platformRole())))
        throw new Failure(403, "ACCESS_DENIED");
    }
  }

  private Progress view(UUID user) {
    var r = store.request(user);
    if (r == null) {
      var issue = store.issue(user);
      if (issue == null) throw new Failure(404, "NOT_FOUND");
      return new Progress(
          user,
          null,
          accounts.find(user).status(),
          PROTOCOL,
          POLICY,
          issue.version(),
          Arrays.stream(Step.values())
              .map(
                  s ->
                      s == Step.RECOVERY_INTENT
                          ? issue
                          : new StepView(
                              s,
                              "WAITING_DEPENDENCY",
                              "HISTORICAL_LIFECYCLE_UNPROVEN",
                              null,
                              0,
                              0,
                              issue.updatedAt()))
              .toList());
    }
    var pending = store.pending(r.id());
    var statuses =
        scoped.executionStatuses(
            "ERASURE_EXPORT", pending.stream().map(ErasureStore.Export::execution).toList());
    var deadOwners =
        pending.stream()
            .filter(
                e -> "RESOURCE".equals(e.operation()) && "DEAD".equals(statuses.get(e.execution())))
            .map(e -> json.readValue(e.record(), Journal.class).owner())
            .collect(java.util.stream.Collectors.toSet());
    var failures = store.resourceFailures(r.id());
    return new Progress(
        user,
        r.id(),
        accounts.find(user).status(),
        PROTOCOL,
        POLICY,
        r.version(),
        Arrays.stream(Step.values()).map(s -> progressStep(r, s, deadOwners, failures)).toList());
  }

  private StepView progressStep(
      ErasureStore.Request r, Step step, Set<String> deadOwners, Map<String, Long> failures) {
    var state = store.step(r.user(), step, false).view();
    boolean dead = deadOwners.contains(step.owner());
    return new StepView(
        step,
        dead ? "FAILED" : state.status(),
        dead ? "RESOURCE_EXPORT_DEAD" : state.reason(),
        state.nextCheckAt(),
        state.version(),
        state.failures() + failures.getOrDefault(step.owner(), 0L),
        state.updatedAt());
  }

  @Transactional
  public Progress progress(ActorContext actor, UUID user) {
    admin(actor, user);
    var result = view(user);
    work.audit(actor.userId(), "ERASURE_PROGRESS_READ", "account", user, "SUCCESS", null);
    return result;
  }

  @Transactional
  public Progress resume(ActorContext actor, UUID user, long expected, Step step) {
    policy.requireEnabled();
    if (step == null || expected < 0) throw Failure.invalid();
    admin(actor, user);
    var r = store.request(user);
    if (r == null || !"DELETION_PENDING".equals(accounts.find(user).status()))
      throw Failure.conflict();
    if (r.version() != expected) throw new Failure(412, "STALE_VERSION");
    var s = store.step(user, step, true);
    if (step.kind() == null || step == Step.COMPLETION)
      throw new Failure(409, "OWNER_NOT_IMPLEMENTED");
    for (var export : store.pending(r.id())) {
      var record = json.readValue(export.record(), Journal.class);
      boolean matches =
          step == Step.RECOVERY_INTENT && !"RESOURCE".equals(export.operation())
              || step.owner().equals(record.owner());
      if (matches && "DEAD".equals(scoped.executionStatus("ERASURE_EXPORT", export.execution()))) {
        var execution = UUID.randomUUID();
        store.resumeExport(export.id(), execution);
        if (step == Step.RECOVERY_INTENT && "FAILED".equals(s.view().status())) {
          var c =
              new Context(
                  user,
                  r.id(),
                  step,
                  s.view().version(),
                  r.round(),
                  r.at(),
                  s.checkpoint(),
                  s.examined());
          store.advance(c, c.checkpoint(), 0, "RUNNABLE", null, null, null, execution);
        }
        scheduleExport(store.export(export.id()));
        work.audit(
            actor.userId(), "ERASURE_EXPORT_RESUMED", "account", user, "SUCCESS", step.name());
        return view(user);
      }
    }
    if (step == Step.RECOVERY_INTENT) throw new Failure(409, "EXECUTION_NOT_DEAD");
    if (!"FAILED".equals(s.view().status())
        || s.execution() == null
        || !"DEAD".equals(scoped.executionStatus(step.kind(), s.execution())))
      throw new Failure(409, "EXECUTION_NOT_DEAD");
    var c =
        new Context(
            user,
            r.id(),
            step,
            s.view().version(),
            r.round(),
            r.at(),
            s.checkpoint(),
            s.examined());
    var execution = UUID.randomUUID();
    store.advance(c, c.checkpoint(), 0, "RUNNABLE", null, null, null, execution);
    work.audit(actor.userId(), "ERASURE_RESUMED", "account", user, "SUCCESS", step.name());
    schedule(user, step, c.version() + 1, execution, store.now());
    return view(user);
  }

  @Transactional
  public void requireCompletion(UUID user, String vector) {
    accounts.guard(user, true);
    var r = store.request(user);
    if (r == null) throw Failure.conflict();
    var prerequisites =
        new ArrayList<com.flyvo.socialcore.account.domain.ErasureCertificate.Proof>();
    for (var step : Step.values()) {
      var s = store.step(user, step, true);
      if (step == Step.COMPLETION)
        continue; // C02: never hash the certificate's own receipt/version.
      if (!"DONE".equals(s.view().status())
          || s.proofVersion() == null
          || s.proofVersion() != s.view().version()
          || s.round() != r.round()) throw new Failure(409, "ERASURE_PREREQUISITE_INCOMPLETE");
      prerequisites.add(
          new com.flyvo.socialcore.account.domain.ErasureCertificate.Proof(
              step.owner(), step.step(), s.view().version(), s.round(), s.proof()));
    }
    if (store.pendingResources(r.id())) throw new Failure(409, "ERASURE_RESOURCE_RECEIPT_REQUIRED");
    var certificate =
        new com.flyvo.socialcore.account.domain.ErasureCertificate(
            store.intent(r.id()).lineage(),
            r.id(),
            user,
            r.generation(),
            PROTOCOL,
            POLICY,
            ErasureProtocol.rosterDigest(),
            r.round(),
            r.manifestDigest(),
            prerequisites);
    if (!ErasureProtocol.digest(json.writeValueAsString(certificate)).equals(vector))
      throw new Failure(409, "ERASURE_STALE_CERTIFICATE");
    throw new Failure(409, "PACKAGE_I_FINALIZATION_DISABLED");
  }
}
