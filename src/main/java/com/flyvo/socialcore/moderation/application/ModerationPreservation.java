package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.moderation.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** Independent moderation intent/export boundary; private payload never enters the archive. */
@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ModerationPreservation implements ExternalJobHandler {
  private final PreservationStore store;
  private final DurableWork work;
  private final RecoveryArchive archive;
  private final Secrets secrets;
  private final ObjectMapper json;
  private final UUID lineage;
  private final TransactionTemplate tx;
  private final ScopedWork scoped;

  public ModerationPreservation(
      PreservationStore store,
      DurableWork work,
      RecoveryArchive archive,
      Secrets secrets,
      ObjectMapper json,
      Environment env,
      org.springframework.transaction.PlatformTransactionManager manager,
      ScopedWork scoped) {
    this.store = store;
    this.work = work;
    this.archive = archive;
    this.secrets = secrets;
    this.json = json;
    this.lineage = UUID.fromString(env.getRequiredProperty("app.erasure.lineage"));
    this.tx = new TransactionTemplate(manager);
    this.scoped = scoped;
  }

  public String kind() {
    return "MODERATION_PRESERVE";
  }

  public record Record(
      UUID lineage,
      UUID id,
      String protocol,
      String policy,
      String operation,
      CaseStore.Root root,
      long version,
      UUID actor,
      UUID community,
      Instant holdUntil,
      Instant proposedHoldUntil,
      String status,
      String retentionState,
      Instant recordedAt,
      List<PreservationStore.History> history) {}

  public record Baseline(boolean ready, int examined) {}

  public UUID intent(
      CaseStore.Control c,
      UUID actor,
      Instant until,
      String operation,
      List<PreservationStore.History> history) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("PRESERVATION_PREPARE_REQUIRES_TRANSACTION");
    if (history.size() > 100) throw Failure.invalid();
    UUID id = UUID.randomUUID();
    String record =
        json.writeValueAsString(
            new Record(
                lineage,
                id,
                AccountErasure.PROTOCOL,
                AccountErasure.POLICY,
                operation,
                c.root(),
                c.version(),
                actor,
                c.community(),
                c.holdUntil(),
                until,
                c.status(),
                c.retentionState(),
                c.now(),
                history));
    String digest = HexFormat.of().formatHex(secrets.hash(record));
    store.add(
        new PreservationStore.Export(
            id,
            c.root(),
            c.version(),
            operation,
            actor,
            c.community(),
            until,
            record,
            digest,
            null,
            id));
    work.job(kind(), id.toString(), id.toString(), c.now());
    return id;
  }

  public Baseline baseline(CaseStore.Control c) {
    var state = store.state(c.root(), c.version());
    if (state.pending() != null) {
      var e = store.find(state.pending());
      if (e == null || e.version() != c.version() || !e.root().equals(c.root()))
        throw new IllegalStateException("PRESERVATION_BASELINE_MISMATCH");
      if (!e.digest().equals(e.receipt())) return new Baseline(false, 0);
      store.acknowledge(c.root(), c.version());
      state = store.state(c.root(), c.version());
    }
    if (state.complete()) return new Baseline(true, 0);
    var history = store.history(c.root(), state.after(), 100);
    var id = intent(c, null, c.holdUntil(), "BASELINE", history);
    store.pending(
        c.root(),
        c.version(),
        id,
        history.isEmpty() ? state.after() : history.getLast().id(),
        history.size() < 100);
    return new Baseline(false, history.size());
  }

  public void require(UUID id, CaseStore.Control c, UUID actor, Instant until) {
    var e = store.find(id);
    if (e == null
        || !e.root().equals(c.root())
        || e.version() != c.version()
        || !"PRESERVE".equals(e.operation())
        || !Objects.equals(e.actor(), actor)
        || !Objects.equals(e.community(), c.community())
        || !Objects.equals(e.until(), until)
        || !e.digest().equals(e.receipt())) throw new Failure(409, "PRESERVATION_RECEIPT_STALE");
  }

  private PreservationStore.Export exact(UUID id, String execution) {
    var e = store.find(id);
    if (e == null) throw new IllegalStateException("PRESERVATION_INTENT_MISSING");
    return e.execution().toString().equals(execution) ? e : null;
  }

  public String prepare(String payload, Lease lease) {
    var e = exact(UUID.fromString(payload), lease.executionKey());
    return e == null ? "STALE" : json.writeValueAsString(e);
  }

  public String execute(String prepared) {
    if (prepared.equals("STALE")) return "STALE";
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("ARCHIVE_UNDER_TRANSACTION");
    var e = json.readValue(prepared, PreservationStore.Export.class);
    var receipt = archive.append(e.id(), e.digest(), e.record().getBytes(StandardCharsets.UTF_8));
    archive.verify(receipt);
    if (!e.id().equals(receipt.recordId()) || !e.digest().equals(receipt.digest()))
      throw new IllegalStateException("PRESERVATION_RECEIPT_MISMATCH");
    return json.writeValueAsString(receipt);
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if (prepared.equals("STALE")) return;
    var e = exact(UUID.fromString(payload), lease.executionKey());
    if (e == null) return;
    var captured = json.readValue(prepared, PreservationStore.Export.class);
    var r = json.readValue(result, RecoveryArchive.Receipt.class);
    if (!e.id().equals(captured.id())
        || !e.execution().equals(captured.execution())
        || !e.digest().equals(captured.digest())
        || !e.record().equals(captured.record())
        || !e.id().equals(r.recordId())
        || !e.digest().equals(r.digest()))
      throw new IllegalStateException("PRESERVATION_RECEIPT_STALE");
    store.receipt(e.id(), r.digest());
  }

  public void failed(String payload, Lease lease) {
    var e = exact(UUID.fromString(payload), lease.executionKey());
    if (e != null && e.receipt() == null) store.failed(e.id());
  }

  public void resumePending() {
    var pending = store.pending(100);
    var statuses =
        scoped.executionStatuses(
            kind(), pending.stream().map(PreservationStore.Export::execution).toList());
    for (var e : pending)
      if ("DEAD".equals(statuses.get(e.execution()))) {
        var execution = UUID.randomUUID();
        store.resume(e.id(), execution);
        work.job(kind(), execution.toString(), e.id().toString(), java.time.Instant.now());
      }
  }

  public void exportNow(UUID id) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("ARCHIVE_UNDER_TRANSACTION");
    var e = tx.execute(s -> store.find(id));
    if (e == null) throw new Failure(503, "PRESERVATION_INTENT_REQUIRED");
    String prepared = json.writeValueAsString(e), result = execute(prepared);
    tx.executeWithoutResult(
        s ->
            complete(
                id.toString(),
                new Lease(0, 1, Instant.now().plusSeconds(60), e.execution().toString()),
                prepared,
                result));
  }
}
