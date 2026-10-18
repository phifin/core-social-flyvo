package com.flyvo.socialcore.account.application;

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

  public String prepare(String payload, Lease lease) {
    policy.requireEnabled();
    var e = store.export(UUID.fromString(payload));
    if (e == null) throw new IllegalStateException("ERASURE_EXPORT_MISSING");
    if (!policy.lineage().equals(store.intent(e.intent()).lineage()))
      throw new Failure(503, "RECOVERY_LINEAGE_MISMATCH");
    return json.writeValueAsString(e);
  }

  public String execute(String prepared) {
    var e = json.readValue(prepared, ErasureStore.Export.class);
    var receipt = archive.append(e.id(), e.digest(), e.record().getBytes(StandardCharsets.UTF_8));
    archive.verify(receipt);
    return json.writeValueAsString(receipt);
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    var e = json.readValue(prepared, ErasureStore.Export.class);
    var receipt = json.readValue(result, RecoveryArchive.Receipt.class);
    if (!e.id().equals(receipt.recordId()) || !e.digest().equals(receipt.digest()))
      throw new IllegalStateException("ARCHIVE_RECEIPT_MISMATCH");
    accounts.guard(store.intent(e.intent()).user(), false);
    store.receipt(e.id(), receipt.digest());
    if ("PREPARED".equals(e.operation())) erasure.preparedRecovered(e.intent());
    if ("COMMITTED".equals(e.operation())) {
      var user = store.intent(e.intent()).user();
      var s = store.step(user, Step.RECOVERY_INTENT, false);
      var context = erasure.begin(user, Step.RECOVERY_INTENT, s.view().version());
      if (context != null) erasure.advance(context, "VERIFIED", 0, "DONE", null, null, e.digest());
    }
  }

  public void failed(String payload, Lease lease) {
    var e = store.export(UUID.fromString(payload));
    var intent = store.intent(e.intent());
    accounts.guard(intent.user(), false);
    store.exportFailed(e.id());
    if (store.request(intent.user()) != null && "COMMITTED".equals(e.operation()))
      store.failed(intent.user(), Step.RECOVERY_INTENT, lease.attempt() >= 10);
  }
}
