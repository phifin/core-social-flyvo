package com.flyvo.socialcore.graph.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.graph.api.*;
import com.flyvo.socialcore.graph.api.event.RelationshipChanged;
import com.flyvo.socialcore.graph.application.port.GraphRepository;
import com.flyvo.socialcore.graph.domain.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Relationships implements GraphCommands, RelationshipQueries {
  private final GraphRepository store;
  private final AccountQueries accounts;
  private final DurableWork work;
  private final Clock clock;
  private final tools.jackson.databind.ObjectMapper json;
  private final GraphCursors cursors;

  public Relationships(
      GraphRepository store,
      AccountQueries accounts,
      DurableWork work,
      Clock clock,
      tools.jackson.databind.ObjectMapper json,
      GraphCursors cursors) {
    this.store = store;
    this.accounts = accounts;
    this.work = work;
    this.clock = clock;
    this.json = json;
    this.cursors = cursors;
  }

  private void actor(ActorContext a) {
    if (a == null || !a.authenticated()) throw Failure.unauthorized();
    var account = accounts.find(a.userId());
    if (account == null || !account.active()) throw Failure.unauthorized();
  }

  private UserPair pair(UUID a, UUID b) {
    try {
      return UserPair.of(a, b);
    } catch (IllegalArgumentException e) {
      throw Failure.invalid();
    }
  }

  private UserPair guard(ActorContext actor, UUID target, boolean activeTarget) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var p = pair(actor.userId(), target);
    for (var u : List.of(p.low(), p.high())) {
      AccountQueries.Account a;
      try {
        a = accounts.guard(u, false);
      } catch (Failure f) {
        if (!u.equals(actor.userId()) && f.status() == 401) throw new Failure(404, "NOT_FOUND");
        throw f;
      }
      if (!a.active() && u.equals(actor.userId())) throw Failure.unauthorized();
      if (!a.active() && activeTarget && u.equals(target)) throw new Failure(404, "NOT_FOUND");
    }
    store.guard(p.lockKey(), true);
    return p;
  }

  private void accessible(UUID a, UUID b) {
    if (store.state(a, b).blocked()) throw new Failure(404, "NOT_FOUND");
  }

  private void event(UserPair p, UUID actor, String type, FriendRequest request) {
    var revision = store.bump(p);
    var s = store.state(p.low(), p.high());
    work.event(
        type,
        "graph",
        revision.id(),
        revision.version(),
        0,
        json.writeValueAsString(
            new RelationshipChanged(
                p.low(),
                p.high(),
                actor,
                request == null ? null : request.id(),
                request == null ? null : request.status(),
                s.forwardFollow(),
                s.reverseFollow(),
                s.friends(),
                s.forwardBlock(),
                s.reverseBlock(),
                s.forwardMute(),
                s.reverseMute())));
  }

  @Transactional
  public void follow(ActorContext actor, UUID target, boolean enabled) {
    var p = guard(actor, target, enabled);
    if (enabled) accessible(actor.userId(), target);
    if (store.edge("following", actor.userId(), target, enabled, clock.instant()))
      event(p, actor.userId(), "FollowChanged", null);
  }

  @Transactional
  public void mute(ActorContext actor, UUID target, boolean enabled) {
    var p = guard(actor, target, enabled);
    if (enabled) accessible(actor.userId(), target);
    if (store.edge("mutes", actor.userId(), target, enabled, clock.instant()))
      event(p, actor.userId(), "MuteChanged", null);
  }

  @Transactional
  public void block(ActorContext actor, UUID target, boolean enabled) {
    var p = guard(actor, target, false);
    // Blocking a suspended account remains valid; terminal erasure cannot regain graph edges.
    if (enabled && Set.of("DELETION_PENDING", "DELETED").contains(accounts.find(target).status()))
      throw new Failure(404, "NOT_FOUND");
    if (!store.edge("blocks", actor.userId(), target, enabled, clock.instant())) return;
    FriendRequest resolved = null;
    if (enabled) {
      store.edge("following", p.low(), p.high(), false, clock.instant());
      store.edge("following", p.high(), p.low(), false, clock.instant());
      store.unfriend(p);
      var pending = store.pending(p);
      if (pending != null) {
        store.resolve(
            pending.id(), pending.live(clock.instant()) ? "CANCELLED" : "EXPIRED", clock.instant());
        resolved = store.request(pending.id());
      }
    }
    event(p, actor.userId(), "BlockChanged", resolved);
    work.audit(
        actor.userId(),
        enabled ? "USER_BLOCKED" : "USER_UNBLOCKED",
        "user",
        target,
        "SUCCESS",
        null);
  }

  @Transactional
  public void unfriend(ActorContext actor, UUID target) {
    var p = guard(actor, target, false);
    if (store.unfriend(p)) event(p, actor.userId(), "FriendshipChanged", null);
  }

  @Transactional
  public Request request(ActorContext actor, UUID target) {
    var p = guard(actor, target, true);
    accessible(actor.userId(), target);
    if (store.state(actor.userId(), target).friends()) throw Failure.conflict();
    var pending = store.pending(p);
    if (pending != null && pending.live(clock.instant())) {
      if (!pending.requester().equals(actor.userId()))
        throw new Failure(409, "OPPOSITE_REQUEST_PENDING");
      return info(pending);
    }
    if (pending != null) {
      store.resolve(pending.id(), "EXPIRED", clock.instant());
      event(p, actor.userId(), "FriendRequestExpired", store.request(pending.id()));
    }
    var r =
        new FriendRequest(
            UUID.randomUUID(),
            actor.userId(),
            target,
            "PENDING",
            clock.instant(),
            clock.instant().plusSeconds(30 * 86400L),
            0);
    store.insert(r);
    work.job("FRIEND_REQUEST_EXPIRY", r.id().toString(), r.id().toString(), r.expiresAt());
    event(p, actor.userId(), "FriendRequestCreated", r);
    return info(r);
  }

  @Transactional
  public Request respond(ActorContext actor, UUID id, String action) {
    if (action == null || !Set.of("accept", "decline", "cancel").contains(action))
      throw Failure.invalid();
    actor(actor);
    var observed = store.request(id);
    if (observed == null
        || !(action.equals("cancel") ? observed.requester() : observed.addressee())
            .equals(actor.userId())) throw new Failure(404, "NOT_FOUND");
    UUID other =
        observed.requester().equals(actor.userId()) ? observed.addressee() : observed.requester();
    var p = guard(actor, other, action.equals("accept"));
    accessible(actor.userId(), other);
    var r = store.request(id);
    String status =
        switch (action) {
          case "accept" -> "ACCEPTED";
          case "decline" -> "DECLINED";
          default -> "CANCELLED";
        };
    if (r.status().equals(status)) return info(r);
    if (!r.live(clock.instant())) throw new Failure(409, "FRIEND_REQUEST_UNAVAILABLE");
    store.resolve(id, status, clock.instant());
    if (action.equals("accept")) store.befriend(r, clock.instant());
    var result = store.request(id);
    event(
        p,
        actor.userId(),
        action.equals("accept") ? "FriendshipEstablished" : "FriendRequestResolved",
        result);
    return info(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void expire(UUID id) {
    var observed = store.request(id);
    if (observed == null) return;
    var p = observed.pair();
    store.guard(p.lockKey(), true);
    var r = store.request(id);
    if (r.status().equals("PENDING") && !r.live(clock.instant())) {
      store.resolve(id, "EXPIRED", clock.instant());
      event(p, null, "FriendRequestExpired", store.request(id));
    }
  }

  @Transactional(readOnly = true)
  public List<Facts> facts(UUID viewer, List<UUID> users) {
    if (viewer == null
        || users == null
        || users.size() > 100
        || users.stream().anyMatch(Objects::isNull)) throw Failure.invalid();
    return store.facts(viewer, users);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void guardInteractions(ActorContext actor, List<UUID> users) {
    actor(actor);
    if (users == null || users.size() > 121 || users.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
    var pairs =
        users.stream()
            .distinct()
            .filter(u -> !u.equals(actor.userId()))
            .map(u -> pair(actor.userId(), u))
            .toList();
    pairs.stream().map(UserPair::lockKey).distinct().sorted().forEach(k -> store.guard(k, false));
    for (var p : pairs) accessible(p.low(), p.high());
  }

  private void page(int size) {
    if (size < 1 || size > 100) throw Failure.invalid();
  }

  private Page<UUID> edges(ActorContext viewer, UUID owner, String kind, int size, String after) {
    page(size);
    UUID user = viewer == null ? null : viewer.userId();
    var rows = store.edges(user, owner, kind, size, cursors.decode(user, owner, kind, after));
    var active = accounts.activeIds(rows);
    return new Page<>(
        rows.stream().filter(active::contains).toList(),
        rows.size() == size ? cursors.encode(user, owner, kind, rows.getLast()) : null);
  }

  @Transactional(readOnly = true)
  public Page<UUID> ownEdges(ActorContext actor, String kind, int size, String after) {
    actor(actor);
    if (kind == null
        || !Set.of("following", "followers", "friends", "blocks", "mutes").contains(kind))
      throw Failure.invalid();
    return edges(actor, actor.userId(), kind, size, after);
  }

  @Transactional(readOnly = true)
  public Page<UUID> visibleEdges(
      ActorContext viewer, UUID owner, String kind, int size, String after) {
    if (kind == null || !Set.of("following", "followers", "friends").contains(kind))
      throw Failure.invalid();
    if (viewer != null && viewer.authenticated()) {
      actor(viewer);
      accessible(viewer.userId(), owner);
    }
    var a = accounts.find(owner);
    if (a == null || !a.active()) throw new Failure(404, "NOT_FOUND");
    return edges(viewer, owner, kind, size, after);
  }

  @Transactional(readOnly = true)
  public Page<Request> requests(ActorContext actor, String direction, int size, String after) {
    actor(actor);
    page(size);
    if (direction == null || !Set.of("incoming", "outgoing").contains(direction))
      throw Failure.invalid();
    var rows =
        store.requests(
            actor.userId(),
            direction,
            size,
            cursors.decode(actor.userId(), actor.userId(), "requests-" + direction, after),
            clock.instant());
    var active =
        accounts.activeIds(
            rows.stream()
                .map(r -> r.requester().equals(actor.userId()) ? r.addressee() : r.requester())
                .distinct()
                .toList());
    return new Page<>(
        rows.stream()
            .filter(
                r ->
                    active.contains(
                        r.requester().equals(actor.userId()) ? r.addressee() : r.requester()))
            .map(Relationships::info)
            .toList(),
        rows.size() == size
            ? cursors.encode(
                actor.userId(), actor.userId(), "requests-" + direction, rows.getLast().id())
            : null);
  }

  private static Request info(FriendRequest r) {
    return new Request(
        r.id(), r.requester(), r.addressee(), r.status(), r.expiresAt(), r.version());
  }
}
