package com.flyvo.socialcore.identity.infrastructure.security;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.identity.api.SessionQueries;
import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.Failure;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class CurrentSessions implements SessionQueries {
  private final IdentityRepository store;
  private final AccountQueries accounts;
  private final Clock clock;

  CurrentSessions(IdentityRepository store, AccountQueries accounts, Clock clock) {
    this.store = store;
    this.accounts = accounts;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public void requireCurrent(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var account = accounts.find(actor.userId());
    var session = store.session(actor.sessionId(), false);
    if (account == null
        || !account.active()
        || session == null
        || !session.userId().equals(actor.userId())
        || session.authenticationEpoch() != account.authenticationEpoch()
        || !session.active(clock.instant())) throw Failure.unauthorized();
  }
}
