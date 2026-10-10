package com.flyvo.socialcore.media.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.media.application.port.MediaRepository;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class QuotaReconciliation implements JobHandler {
  private final MediaRepository store;
  private final AccountQueries accounts;
  private final DurableWork work;
  private final Clock clock;

  public QuotaReconciliation(
      MediaRepository store, AccountQueries accounts, DurableWork work, Clock clock) {
    this.store = store;
    this.accounts = accounts;
    this.work = work;
    this.clock = clock;
  }

  public String kind() {
    return "MEDIA_QUOTA_RECONCILE";
  }

  public void apply(String payload) {
    var owners = store.owners(payload.isEmpty() ? null : UUID.fromString(payload), 20);
    for (var owner : owners) accounts.guard(owner, false);
    for (var owner : owners) {
      var q = store.quota(owner, clock.instant());
      var actual = store.actualQuota(owner);
      if (q.reserved() != actual.reserved() || q.retained() != actual.retained()) {
        store.charge(owner, actual.reserved() - q.reserved(), actual.retained() - q.retained());
        work.audit(null, "MEDIA_QUOTA_RECONCILED", "account", owner, "SUCCESS", "ACCOUNTING_DRIFT");
      }
    }
    if (owners.size() == 20)
      work.job(
          kind(),
          clock.instant().getEpochSecond() / 3600 + ":" + owners.getLast(),
          owners.getLast().toString(),
          clock.instant());
  }
}
