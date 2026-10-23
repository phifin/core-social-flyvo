package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** C02: immutable eleven-proof certificate, outside-lock export, exclusive final compare. */
@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ErasureCompletion implements ExternalJobHandler {
  private final AccountRepository accounts;
  private final ErasureStore store;
  private final Erasures erasure;
  private final RecoveryArchive archive;
  private final DurableWork work;
  private final ErasurePolicy policy;
  private final ObjectMapper json;

  public ErasureCompletion(
      AccountRepository accounts,
      ErasureStore store,
      Erasures erasure,
      RecoveryArchive archive,
      DurableWork work,
      ErasurePolicy policy,
      ObjectMapper json) {
    this.accounts = accounts;
    this.store = store;
    this.erasure = erasure;
    this.archive = archive;
    this.work = work;
    this.policy = policy;
    this.json = json;
  }

  public String kind() {
    return "ERASURE_COMPLETE";
  }

  public record Prepared(
      Pass pass,
      UUID attempt,
      UUID request,
      long round,
      long generation,
      String record,
      String digest) {}

  private Context guard(Pass p, Lease lease) {
    policy.requireEnabled();
    if (p.step() != Step.COMPLETION) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    var a = accounts.guard(p.userId(), true);
    if (a == null || !"DELETION_PENDING".equals(a.status())) return null;
    for (var step : Step.values()) store.step(p.userId(), step, true);
    var state = store.step(p.userId(), Step.COMPLETION, false);
    if (state == null
        || state.view().version() != p.version()
        || !Objects.equals(state.execution(), p.executionId())
        || !p.executionId().toString().equals(lease.executionKey())) return null;
    return erasure.begin(p.userId(), Step.COMPLETION, p.version());
  }

  public String prepare(String payload, Lease lease) {
    var p = json.readValue(payload, Pass.class);
    var c = guard(p, lease);
    if (c == null) return "STALE";
    String record = json.writeValueAsString(erasure.certificate(c.userId())),
        digest = ErasureProtocol.digest(record);
    ErasureStore.Export attempt = null;
    if (c.checkpoint().startsWith("ATTEMPT:"))
      attempt = store.export(UUID.fromString(c.checkpoint().substring(8)));
    if (attempt == null || !attempt.digest().equals(digest)) {
      var id = UUID.randomUUID();
      store.addExport(id, c.requestId(), "COMPLETION", "account", null, null, record, digest);
      attempt = store.export(id);
      store.completionAttempt(c, id);
    }
    if (!attempt.intent().equals(c.requestId())
        || !"COMPLETION".equals(attempt.operation())
        || !attempt.record().equals(record))
      throw new IllegalStateException("ERASURE_CERTIFICATE_ATTEMPT_MISMATCH");
    if (!attempt.execution().equals(p.executionId()))
      store.resumeExport(attempt.id(), p.executionId());
    return json.writeValueAsString(
        new Prepared(
            p,
            attempt.id(),
            c.requestId(),
            c.round(),
            store.request(c.userId()).generation(),
            record,
            digest));
  }

  public String execute(String prepared) {
    if (prepared.equals("STALE")) return "STALE";
    var p = json.readValue(prepared, Prepared.class);
    var receipt =
        archive.append(p.attempt(), p.digest(), p.record().getBytes(StandardCharsets.UTF_8));
    archive.verify(receipt);
    return json.writeValueAsString(receipt);
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if (prepared.equals("STALE")) return;
    var p = json.readValue(payload, Pass.class);
    var captured = json.readValue(prepared, Prepared.class);
    if (!p.equals(captured.pass()))
      throw new IllegalStateException("ERASURE_CERTIFICATE_PASS_MISMATCH");
    var c = guard(p, lease);
    if (c == null) return;
    var request = store.request(c.userId());
    var attempt = store.export(captured.attempt());
    if (!c.requestId().equals(captured.request())
        || c.round() != captured.round()
        || request.generation() != captured.generation()
        || attempt == null
        || !attempt.intent().equals(request.id())
        || !attempt.execution().equals(p.executionId())
        || !attempt.record().equals(captured.record())
        || !attempt.digest().equals(captured.digest())
        || !c.checkpoint().equals("ATTEMPT:" + attempt.id()))
      throw new IllegalStateException("ERASURE_CERTIFICATE_RECEIPT_STALE");
    String current = json.writeValueAsString(erasure.certificate(c.userId()));
    if (!current.equals(captured.record())) {
      erasure.advance(c, "START", 0, "RUNNABLE", null, null, null);
      return;
    }
    var receipt = json.readValue(result, RecoveryArchive.Receipt.class);
    if (!receipt.recordId().equals(attempt.id()) || !receipt.digest().equals(attempt.digest()))
      throw new IllegalStateException("ERASURE_CERTIFICATE_RECEIPT_MISMATCH");
    store.receipt(attempt.id(), receipt.digest());
    erasure.advance(c, "VERIFIED", 0, "DONE", null, null, attempt.digest());
    var account = accounts.find(c.userId());
    account.completeDeletion(store.now());
    accounts.flush();
    work.audit(null, "ACCOUNT_ERASURE_COMPLETED", "account", c.userId(), "SUCCESS", null);
    work.event(
        "AccountDeletionCompleted",
        "account",
        c.userId(),
        account.version(),
        0,
        json.writeValueAsString(Map.of("userId", c.userId(), "requestId", request.id())));
  }

  public void failed(String payload, Lease lease) {
    var p = json.readValue(payload, Pass.class);
    accounts.guard(p.userId(), false);
    var state = store.step(p.userId(), Step.COMPLETION, true);
    if (state == null
        || state.view().version() != p.version()
        || !Objects.equals(state.execution(), p.executionId())
        || !p.executionId().toString().equals(lease.executionKey())) return;
    if (state.checkpoint().startsWith("ATTEMPT:")) {
      var e = store.export(UUID.fromString(state.checkpoint().substring(8)));
      if (e != null && e.execution().equals(p.executionId())) store.exportFailed(e.id());
    }
    erasure.executionFailed(p, lease.attempt());
  }
}
