package com.flyvo.socialcore.notification.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.notification.api.NotificationCommands;
import com.flyvo.socialcore.notification.api.NotificationQueries;
import com.flyvo.socialcore.notification.application.port.InboxRepository;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.api.Secrets;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class Inbox implements NotificationQueries, NotificationCommands {
  private static final int MAX_GROUPS = 10_000, MAX_CONTRIBUTIONS = 10_000;
  private final InboxRepository store;
  private final AccountQueries accounts;
  private final NotificationEligibility eligibility;
  private final ProfileQueries profiles;
  private final Clock clock;
  private final Secrets secrets;
  private final ObjectMapper json;

  public Inbox(
      InboxRepository store,
      AccountQueries accounts,
      NotificationEligibility eligibility,
      ProfileQueries profiles,
      Clock clock,
      Secrets secrets,
      ObjectMapper json) {
    this.store = store;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.profiles = profiles;
    this.clock = clock;
    this.secrets = secrets;
    this.json = json;
  }

  private UUID require(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
    return actor.userId();
  }

  private UUID guard(ActorContext actor) {
    UUID id = require(actor);
    if (!accounts.guard(id, false).active()) throw Failure.unauthorized();
    return id;
  }

  private static Failure scanLimit() {
    return new Failure(503, "NOTIFICATION_SCAN_LIMIT");
  }

  public record Cursor(Instant cutoff, Instant expires, InboxRepository.Key after) {}

  private Cursor cursor(UUID recipient, int size, String after) {
    if (after == null) return new Cursor(clock.instant(), clock.instant().plusSeconds(1800), null);
    try {
      if (after.length() > 2048) throw new IllegalArgumentException();
      var c =
          json.readValue(
              secrets.decrypt(
                  "notification-page:v1:" + recipient + ":" + size,
                  Base64.getUrlDecoder().decode(after)),
              Cursor.class);
      if (c.cutoff() == null
          || c.expires() == null
          || c.after() == null
          || !clock.instant().isBefore(c.expires())) throw new IllegalArgumentException();
      return c;
    } catch (RuntimeException e) {
      throw new Failure(400, "INVALID_CURSOR");
    }
  }

  private String encode(UUID recipient, int size, Cursor c) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            secrets.encrypt(
                "notification-page:v1:" + recipient + ":" + size, json.writeValueAsString(c)));
  }

  private static class Work {
    int contributions;
  }

  private Map<UUID, Set<UUID>> actors(
      UUID recipient, List<InboxRepository.Group> groups, Map<Kind, Boolean> prefs, Work work) {
    var groupMap = new HashMap<UUID, InboxRepository.Group>();
    for (var g : groups) if (prefs.get(g.kind())) groupMap.put(g.id(), g);
    var result = new HashMap<UUID, Set<UUID>>();
    UUID after = null;
    while (!groupMap.isEmpty()) {
      var batch = store.contributions(new ArrayList<>(groupMap.keySet()), 100, after);
      if (batch.isEmpty()) break;
      work.contributions += batch.size();
      if (work.contributions > MAX_CONTRIBUTIONS) throw scanLimit();
      for (var kind : Kind.values()) {
        var sameKind =
            batch.stream().filter(c -> groupMap.get(c.notification()).kind() == kind).toList();
        for (var c : eligibility.filter(recipient, kind, sameKind))
          result.computeIfAbsent(c.notification(), k -> new LinkedHashSet<>()).add(c.actor());
      }
      after = batch.getLast().id();
      if (batch.size() < 100) break;
    }
    return result;
  }

  private List<Item> representations(
      List<InboxRepository.Group> groups, Map<UUID, Set<UUID>> eligible) {
    var selected = new ArrayList<UUID>();
    for (var g : groups)
      for (var id : eligible.getOrDefault(g.id(), Set.of()).stream().limit(3).toList())
        if (!selected.contains(id)) selected.add(id);
    var names = new HashMap<UUID, String>();
    for (int i = 0; i < selected.size(); i += 100)
      for (var p :
          profiles.attributionSummaries(selected.subList(i, Math.min(i + 100, selected.size()))))
        names.put(p.userId(), p.displayName());
    var result = new ArrayList<Item>();
    for (var g : groups) {
      var actors = eligible.getOrDefault(g.id(), Set.of());
      if (actors.isEmpty()) continue;
      var summaries =
          actors.stream()
              .limit(3)
              .filter(names::containsKey)
              .map(id -> new Actor(id, names.get(id)))
              .toList();
      result.add(
          new Item(
              g.id(),
              g.kind(),
              g.targetType(),
              g.targetId(),
              g.activityVersion(),
              g.readVersion(),
              g.lastActivity(),
              actors.size(),
              summaries));
    }
    return result;
  }

  // Orchestration does not retain a connection while filtering/ranking/cursor work;
  // each owner proof and repository read uses a short statement/owner transaction.
  public Page inbox(ActorContext actor, int size, String after) {
    UUID recipient = require(actor);
    if (size < 1 || size > 100) throw Failure.invalid();
    var c = cursor(recipient, size, after);
    var prefs = store.preferences(recipient);
    var work = new Work();
    var selected = new ArrayList<InboxRepository.Group>();
    var eligible = new HashMap<UUID, Set<UUID>>();
    var last = c.after();
    boolean more = false;
    for (int scanned = 0; scanned < 1000 && selected.size() < size; ) {
      var batch = store.scan(recipient, 100, c.cutoff(), last, false);
      if (batch.isEmpty()) {
        more = false;
        break;
      }
      var proven = actors(recipient, batch, prefs, work);
      for (var g : batch) {
        scanned++;
        last = new InboxRepository.Key(g.lastActivity(), g.id());
        if (!proven.getOrDefault(g.id(), Set.of()).isEmpty()) {
          selected.add(g);
          eligible.put(g.id(), proven.get(g.id()));
        }
        if (selected.size() == size || scanned == 1000) break;
      }
      more = batch.size() == 100 || selected.size() == size;
      if (batch.size() < 100 && selected.size() < size) break;
    }
    return new Page(
        representations(selected, eligible),
        more ? encode(recipient, size, new Cursor(c.cutoff(), c.expires(), last)) : null);
  }

  public long unread(ActorContext actor) {
    UUID recipient = require(actor);
    var snapshot = snapshot(recipient, true);
    return snapshot.size();
  }

  private List<InboxRepository.Group> snapshot(UUID recipient, boolean unread) {
    var cutoff = clock.instant();
    var prefs = store.preferences(recipient);
    var work = new Work();
    var observed = new ArrayList<InboxRepository.Group>();
    InboxRepository.Key after = null;
    int scanned = 0;
    while (true) {
      var batch = store.scan(recipient, 100, cutoff, after, unread);
      if (batch.isEmpty()) break;
      scanned += batch.size();
      if (scanned > MAX_GROUPS) throw scanLimit();
      var eligible = actors(recipient, batch, prefs, work);
      for (var g : batch) if (!eligible.getOrDefault(g.id(), Set.of()).isEmpty()) observed.add(g);
      var last = batch.getLast();
      after = new InboxRepository.Key(last.lastActivity(), last.id());
      if (batch.size() < 100) break;
    }
    return observed;
  }

  @Transactional
  public void read(ActorContext actor, UUID id, long observed) {
    UUID recipient = guard(actor);
    if (id == null || observed < 0) throw Failure.invalid();
    var g = store.find(recipient, id);
    if (g == null
        || !g.lastActivity().isAfter(clock.instant().minusSeconds(90L * 86400))
        || actors(recipient, List.of(g), store.preferences(recipient), new Work()).isEmpty())
      throw new Failure(404, "NOT_FOUND");
    if (observed > g.activityVersion()) throw Failure.invalid();
    store.guard(recipient, clock.instant());
    store.read(recipient, id, observed);
  }

  @Transactional
  public void readAll(ActorContext actor) {
    UUID recipient = guard(actor);
    var observed = snapshot(recipient, true);
    store.guard(recipient, clock.instant());
    for (var g : observed.stream().sorted(Comparator.comparing(g -> g.id().toString())).toList())
      store.read(recipient, g.id(), g.activityVersion());
  }

  public Map<Kind, Boolean> preferences(ActorContext actor) {
    return store.preferences(require(actor));
  }

  @Transactional
  public Map<Kind, Boolean> preferences(ActorContext actor, Map<Kind, Boolean> changes) {
    UUID recipient = guard(actor);
    if (changes == null
        || changes.isEmpty()
        || changes.size() > Kind.values().length
        || changes.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
      throw Failure.invalid();
    store.guard(recipient, clock.instant());
    store.preferences(recipient, changes, clock.instant());
    return store.preferences(recipient);
  }
}
