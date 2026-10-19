package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountErasure.Context;
import com.flyvo.socialcore.account.api.AccountErasure.Step;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import com.flyvo.socialcore.platform.api.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ErasureExports implements ExternalJobHandler {
  private final ErasureStore store;
  private final Erasures erasure;
  private final RecoveryArchive archive;
  private final ObjectMapper json;
  private final ErasurePolicy policy;
  private final com.flyvo.socialcore.account.api.AccountQueries accounts;

  public ErasureExports(
      ErasureStore store,
      Erasures erasure,
      RecoveryArchive archive,
      ObjectMapper json,
      ErasurePolicy policy,
      com.flyvo.socialcore.account.api.AccountQueries accounts) {
    this.store = store;
    this.erasure = erasure;
    this.archive = archive;
    this.json = json;
    this.policy = policy;
    this.accounts = accounts;
  }

  public String kind() {
    return "ERASURE_EXPORT";
  }

  record Prepared(ErasureStore.Export export, Context recovery) {}

  private boolean currentExecution(ErasureStore.Export e, Lease lease) {
    return e != null && e.execution().toString().equals(lease.executionKey());
  }

  private Erasures.Journal journal(ErasureStore.Export e, ErasureStore.Intent i) {
    var j = json.readValue(e.record(), Erasures.Journal.class);
    if (!j.recordId().equals(e.id())
        || !j.requestId().equals(i.id())
        || !j.subject().equals(i.user())
        || !j.lineage().equals(i.lineage())
        || j.generation() != i.expectedVersion() + 1
        || !j.operation().equals(e.operation())
        || !e.digest().equals(ErasureProtocol.digest(e.record())))
      throw new IllegalStateException("ERASURE_EXPORT_IDENTITY_MISMATCH");
    return j;
  }

  private boolean matchesRequest(
      ErasureStore.Intent i, ErasureStore.Request r, Erasures.Journal j) {
    return i.matches(r) && r.round() == j.round() && r.at().equals(j.effectiveNotBefore());
  }

  public String prepare(String payload, Lease lease) {
    policy.requireEnabled();
    var e = store.export(UUID.fromString(payload));
    if (e == null) throw new IllegalStateException("ERASURE_EXPORT_MISSING");
    var intent = store.intent(e.intent());
    accounts.guard(intent.user(), false);
    e = store.export(e.id());
    if (!currentExecution(e, lease)) return "STALE";
    if (!policy.lineage().equals(intent.lineage()))
      throw new Failure(503, "RECOVERY_LINEAGE_MISMATCH");
    var j = journal(e, intent);
    var request = store.request(intent.user());
    if (!"PREPARED".equals(e.operation()) && !matchesRequest(intent, request, j))
      throw new IllegalStateException("ERASURE_EXPORT_REQUEST_MISMATCH");
    Context recovery = null;
    if ("COMMITTED".equals(e.operation())) {
      var s = store.step(intent.user(), Step.RECOVERY_INTENT, true);
      if (s.round() != request.round()
          || s.execution() != null && !s.execution().equals(e.execution()))
        throw new IllegalStateException("ERASURE_EXPORT_EXECUTION_MISMATCH");
      recovery = erasure.begin(intent.user(), Step.RECOVERY_INTENT, s.view().version());
      if (recovery == null && (!"DONE".equals(s.view().status()) || !e.digest().equals(s.proof())))
        throw new IllegalStateException("ERASURE_RECOVERY_NOT_RUNNABLE");
    }
    return json.writeValueAsString(new Prepared(e, recovery));
  }

  public String execute(String prepared) {
    if ("STALE".equals(prepared)) return "STALE";
    var e = json.readValue(prepared, Prepared.class).export();
    var receipt = archive.append(e.id(), e.digest(), e.record().getBytes(StandardCharsets.UTF_8));
    archive.verify(receipt);
    return json.writeValueAsString(receipt);
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if ("STALE".equals(prepared)) return;
    var captured = json.readValue(prepared, Prepared.class);
    var e = captured.export();
    if (!e.id().toString().equals(payload))
      throw new IllegalStateException("ERASURE_EXPORT_IDENTITY_MISMATCH");
    var intent = store.intent(e.intent());
    accounts.guard(intent.user(), false);
    var current = store.export(e.id());
    if (!currentExecution(current, lease) || !current.execution().equals(e.execution())) return;
    var j = journal(e, intent);
    if (!"PREPARED".equals(e.operation())
        && !matchesRequest(intent, store.request(intent.user()), j))
      throw new IllegalStateException("ERASURE_EXPORT_REQUEST_MISMATCH");
    Context context = null;
    if (captured.recovery() != null) {
      context = erasure.begin(intent.user(), Step.RECOVERY_INTENT, captured.recovery().version());
      if (!captured.recovery().equals(context))
        throw new IllegalStateException("ERASURE_STALE_EXPORT_PASS");
    }
    var receipt = json.readValue(result, RecoveryArchive.Receipt.class);
    if (!e.id().equals(receipt.recordId()) || !e.digest().equals(receipt.digest()))
      throw new IllegalStateException("ARCHIVE_RECEIPT_MISMATCH");
    store.receipt(e.id(), receipt.digest());
    if ("PREPARED".equals(e.operation())) erasure.preparedRecovered(e.intent(), j.round());
    if (context != null) erasure.advance(context, "VERIFIED", 0, "DONE", null, null, e.digest());
  }

  public void failed(String payload, Lease lease) {
    var e = store.export(UUID.fromString(payload));
    var intent = store.intent(e.intent());
    accounts.guard(intent.user(), false);
    e = store.export(e.id());
    if (!currentExecution(e, lease)) return;
    store.exportFailed(e.id());
    if ("COMMITTED".equals(e.operation())
        && matchesRequest(intent, store.request(intent.user()), journal(e, intent))) {
      var s = store.step(intent.user(), Step.RECOVERY_INTENT, true);
      if (!"DONE".equals(s.view().status())
          && s.round() == store.request(intent.user()).round()
          && (s.execution() == null || s.execution().equals(e.execution())))
        store.failed(intent.user(), Step.RECOVERY_INTENT, lease.attempt() >= 10);
    }
  }
}
