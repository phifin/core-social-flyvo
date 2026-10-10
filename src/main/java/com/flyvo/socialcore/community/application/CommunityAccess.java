package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.application.port.CommunityRepository;
import com.flyvo.socialcore.community.domain.CommunityState.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class CommunityAccess {
  private final CommunityRepository store;
  private final AccountQueries accounts;
  private final Clock clock;
  private final DurableWork work;
  private final tools.jackson.databind.ObjectMapper json;

  public CommunityAccess(
      CommunityRepository store,
      AccountQueries accounts,
      Clock clock,
      DurableWork work,
      tools.jackson.databind.ObjectMapper json) {
    this.store = store;
    this.accounts = accounts;
    this.clock = clock;
    this.work = work;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void guardAccounts(ActorContext actor, UUID... users) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var ids =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(actor.userId()), java.util.Arrays.stream(users))
            .distinct()
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    for (var id : ids) {
      AccountQueries.Account account;
      try {
        account = accounts.guard(id, false);
      } catch (Failure failure) {
        if (!id.equals(actor.userId()) && failure.status() == 401)
          throw new Failure(404, "NOT_FOUND");
        throw failure;
      }
      if (id.equals(actor.userId()) && !account.active()) throw Failure.unauthorized();
    }
  }

  public CommunityRepository store() {
    return store;
  }

  public Clock clock() {
    return clock;
  }

  public DurableWork work() {
    return work;
  }

  public String fingerprint(Object value) {
    return json.writeValueAsString(value);
  }

  public void actor(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
  }

  public void activeAccount(UUID id) {
    var account = accounts.find(id);
    if (account == null || !account.active()) throw new Failure(409, "ACCOUNT_INACTIVE");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Community guard(UUID id, boolean exclusive) {
    var c = store.community(id, true, exclusive);
    if (c == null) throw new Failure(404, "NOT_FOUND");
    return c;
  }

  public void version(long actual, long expected) {
    if (actual != expected) throw new Failure(412, "STALE_VERSION");
  }

  public static void page(int size, int offset) {
    if (size < 1 || size > 100 || offset < 0 || offset > 10000) throw Failure.invalid();
  }

  void communityChanged(ActorContext actor, Community c, String type, UUID user) {
    var current = store.community(c.id(), false, false);
    changed(actor, current, type, user, c.id(), current.status(), store.member(c.id(), user));
  }

  void membershipChanged(ActorContext actor, Community c, String type, UUID user) {
    var current = store.community(c.id(), false, false);
    var member = store.member(c.id(), user);
    changed(actor, current, type, user, c.id(), member.status(), member);
  }

  void admissionChanged(
      ActorContext actor, Community c, String type, UUID user, UUID source, String status) {
    var current = store.community(c.id(), false, false);
    changed(actor, current, type, user, source, status, store.member(c.id(), user));
  }

  private void changed(
      ActorContext actor,
      Community current,
      String type,
      UUID user,
      UUID source,
      String status,
      Member member) {
    // Envelope version sequences all transitions; status belongs to the event's source,
    // never to whichever membership the initiating actor happens to have.
    work.event(
        type,
        "community",
        current.id(),
        current.version(),
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.community.api.event.CommunityTransition(
                current.id(),
                user,
                source,
                status,
                member == null ? null : member.effectiveRole(current),
                member == null ? null : member.suspendedUntil(),
                member == null ? null : member.version())));
    work.auditScoped(
        actor.userId(),
        type.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT),
        "community",
        current.id(),
        current.id(),
        "SUCCESS",
        null);
  }

  void bump(Community c) {
    store.settings(
        c.id(), c.name(), c.description(), c.visibility(), c.joinMode(), clock.instant());
  }
}
