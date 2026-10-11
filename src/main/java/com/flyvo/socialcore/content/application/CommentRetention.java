package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.content.application.port.InteractionRepository;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CommentRetention implements JobHandler {
  private final InteractionRepository store;
  private final AccountQueries accounts;
  private final Clock clock;

  public CommentRetention(InteractionRepository store, AccountQueries accounts, Clock clock) {
    this.store = store;
    this.accounts = accounts;
    this.clock = clock;
  }

  public String kind() {
    return "COMMENT_RETENTION";
  }

  public void apply(String payload) {
    var id = UUID.fromString(payload);
    var first = store.find(id, false);
    if (first == null) return;
    accounts.guard(first.author(), false);
    store.lockPost(first.post(), false);
    store.lockComment(id, true);
    store.redactDeleted(id, clock.instant().minusSeconds(30L * 86400), clock.instant());
  }
}
