package com.flyvo.socialcore.profile.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.graph.api.RelationshipQueries;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.api.ProfileSearchSources;
import com.flyvo.socialcore.profile.application.port.ProfileRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileSearch implements ProfileSearchSources {
  private final ProfileRepository store;
  private final AccountQueries accounts;
  private final RelationshipQueries graph;

  public ProfileSearch(
      ProfileRepository store, AccountQueries accounts, RelationshipQueries graph) {
    this.store = store;
    this.accounts = accounts;
    this.graph = graph;
  }

  private void batch(List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
  }

  @Transactional(readOnly = true)
  public List<UUID> scan(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw Failure.invalid();
    return store.scan(after, limit);
  }

  @Transactional(readOnly = true)
  public List<Source> inspect(List<UUID> ids) {
    batch(ids);
    var profiles = store.summaries(ids);
    var statuses = accounts.snapshots(ids);
    var result =
        new ArrayList<Source>(
            profiles.stream()
                .map(
                    p -> {
                      var a = statuses.get(p.userId());
                      return new Source(
                          p.userId(),
                          p.version(),
                          a == null ? null : a.version(),
                          a != null && a.active(),
                          p.handle(),
                          p.displayName());
                    })
                .toList());
    for (var e : store.tombstones(ids).entrySet()) {
      var a = statuses.get(e.getKey());
      result.add(
          new Source(e.getKey(), e.getValue(), a == null ? null : a.version(), false, "", ""));
    }
    return List.copyOf(result);
  }

  @Transactional(readOnly = true)
  public List<Card> hydrate(ActorContext actor, List<UUID> ids) {
    batch(ids);
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var account = accounts.find(actor.userId());
    if (account == null || !account.active()) throw Failure.unauthorized();
    // Text and visibility are loaded together; later facts only remove, never
    // load newer text under an earlier scope proof.
    var profiles = store.summaries(ids);
    var active = accounts.activeIds(ids);
    var others = ids.stream().distinct().filter(id -> !id.equals(actor.userId())).toList();
    var facts =
        graph.facts(actor.userId(), others).stream()
            .collect(Collectors.toMap(RelationshipQueries.Facts::userId, f -> f));
    return profiles.stream()
        .filter(
            p -> {
              if (!active.contains(p.userId())) return false;
              if (p.userId().equals(actor.userId())) return true;
              var f = facts.get(p.userId());
              return f != null
                  && !f.blocked()
                  && (Set.of("PUBLIC", "AUTHENTICATED").contains(p.visibility())
                      || "FRIENDS".equals(p.visibility()) && f.friends());
            })
        .map(p -> new Card(p.userId(), p.handle(), p.displayName(), p.version()))
        .toList();
  }
}
