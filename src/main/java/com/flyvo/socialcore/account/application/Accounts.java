package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountCommands;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.account.application.port.AccountRepository;
import com.flyvo.socialcore.account.domain.UserAccount;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.Failure;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts implements AccountCommands, AccountQueries {
  private final AccountRepository entities;
  private final Clock clock;
  private final DurableWork work;
  private final tools.jackson.databind.ObjectMapper json;

  public Accounts(
      AccountRepository entities,
      Clock clock,
      DurableWork work,
      tools.jackson.databind.ObjectMapper json) {
    this.entities = entities;
    this.clock = clock;
    this.work = work;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public Account find(UUID id) {
    var value = entities.find(id);
    return value == null ? null : facts(value);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Account guard(UUID id, boolean exclusive) {
    var value = entities.guard(id, exclusive);
    if (value == null) throw Failure.unauthorized();
    return facts(value);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID createVerified() {
    var account = new UserAccount(UUID.randomUUID(), clock.instant());
    entities.add(account);
    entities.flush();
    work.event(
        "UserRegistered",
        "account",
        account.id(),
        0,
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.account.api.event.UserRegistered(account.id())));
    return account.id();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requestDeletion(UUID id) {
    if (!guard(id, true).active()) throw Failure.conflict();
    var value = entities.find(id);
    value.requestDeletion(clock.instant());
    entities.flush();
    work.audit(id, "ACCOUNT_DELETION_REQUESTED", "account", id, "SUCCESS", null);
    work.event(
        "AccountDeletionRequested",
        "account",
        id,
        value.version(),
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.account.api.event.AccountDeletionRequested(id)));
  }

  @Transactional
  public void suspend(ActorContext actor, UUID id, String reason) {
    if (!actor.authenticated() || reason == null || !reason.matches("[A-Z_]{1,64}"))
      throw Failure.invalid();
    var ids =
        java.util.stream.Stream.of(actor.userId(), id)
            .distinct()
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    for (var user : ids) guard(user, true);
    var admin = find(actor.userId());
    if (!admin.active() || !"ADMIN".equals(admin.platformRole()))
      throw new Failure(403, "ACCESS_DENIED");
    var value = entities.find(id);
    if (!"ACTIVE".equals(value.status())) throw Failure.conflict();
    value.suspend(clock.instant());
    entities.flush();
    work.audit(actor.userId(), "ACCOUNT_SUSPENDED", "account", id, "SUCCESS", reason);
    work.event(
        "AccountStatusChanged",
        "account",
        id,
        value.version(),
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.account.api.event.AccountStatusChanged("SUSPENDED")));
  }

  private Account facts(UserAccount value) {
    return new Account(value.id(), value.status(), value.platformRole(), value.version());
  }
}
