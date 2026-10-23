package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.CommunitySearchSources;
import com.flyvo.socialcore.content.api.ContentSearchSources;
import com.flyvo.socialcore.profile.api.ProfileSearchSources;
import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import java.util.*;
import org.springframework.stereotype.Component;

/** Owner-confirmed snapshots only; neither events nor backfill can manufacture source versions. */
@Component
public class SearchSources {
  private final ProfileSearchSources profiles;
  private final CommunitySearchSources communities;
  private final ContentSearchSources content;
  private final SearchIndex index;
  private final AccountQueries accounts;

  public SearchSources(
      ProfileSearchSources profiles,
      CommunitySearchSources communities,
      ContentSearchSources content,
      SearchIndex index,
      AccountQueries accounts) {
    this.profiles = profiles;
    this.communities = communities;
    this.content = content;
    this.index = index;
    this.accounts = accounts;
  }

  public List<UUID> scan(Kind kind, UUID after) {
    return switch (kind) {
      case USER -> profiles.scan(after, 100);
      case COMMUNITY -> communities.scan(after, 100);
      case POST -> content.scan(after, 100);
    };
  }

  public List<SearchIndex.Document> inspect(Kind kind, List<UUID> ids) {
    var snapshots =
        switch (kind) {
          case USER ->
              profiles.inspect(ids).stream()
                  .map(
                      p ->
                          new SearchIndex.Document(
                              kind,
                              p.id(),
                              p.version(),
                              p.id(),
                              null,
                              p.accountVersion(),
                              !p.active(),
                              p.accountVersion() == null,
                              p.handle(),
                              p.name(),
                              ""))
                  .toList();
          case COMMUNITY ->
              communities.inspect(ids).stream()
                  .map(
                      c ->
                          new SearchIndex.Document(
                              kind,
                              c.id(),
                              c.version(),
                              null,
                              null,
                              null,
                              !c.active(),
                              false,
                              c.slug(),
                              c.name(),
                              Objects.requireNonNullElse(c.description(), "")))
                  .toList();
          case POST ->
              content.inspect(ids).stream()
                  .map(
                      p ->
                          new SearchIndex.Document(
                              kind,
                              p.id(),
                              p.version(),
                              p.authorId(),
                              p.communityId(),
                              p.accountVersion(),
                              !p.active(),
                              p.accountVersion() == null,
                              "",
                              "",
                              Objects.requireNonNullElse(p.body(), "")))
                  .toList();
        };
    var found = new HashMap<UUID, SearchIndex.Document>();
    for (var d : snapshots) found.put(d.id(), d);
    return ids.stream()
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .map(id -> found.getOrDefault(id, SearchIndex.Document.absent(kind, id)))
        .toList();
  }

  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public int refresh(Kind kind, List<UUID> ids) {
    return materialize(guard(kind, ids));
  }

  public record Prepared(List<SearchIndex.Document> documents, Set<UUID> terminal) {}

  /** Called before any rebuild/document lock, including an absent USER source. */
  public Prepared guard(Kind kind, List<UUID> ids) {
    var first = inspect(kind, ids);
    var owners = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
    if (kind == Kind.USER) owners.addAll(ids);
    first.stream().map(SearchIndex.Document::owner).filter(Objects::nonNull).forEach(owners::add);
    var terminal = new HashSet<UUID>();
    for (var id : owners) {
      var account = accounts.guard(id, false);
      if (account != null && Set.of("DELETION_PENDING", "DELETED").contains(account.status()))
        terminal.add(id);
    }
    var current = inspect(kind, ids);
    if (current.stream()
        .map(SearchIndex.Document::owner)
        .filter(Objects::nonNull)
        .anyMatch(id -> !owners.contains(id)))
      throw new IllegalStateException("SEARCH_SOURCE_OWNER_CHANGED");
    return new Prepared(current, Set.copyOf(terminal));
  }

  public int materialize(Prepared prepared) {
    int changed = 0;
    var documents = prepared.documents();
    for (var d : documents) {
      UUID owner = d.kind() == Kind.USER ? d.id() : d.owner();
      if (owner != null && prepared.terminal().contains(owner)) {
        index.erase(owner, d.kind(), d.id());
        changed++;
      } else if (index.upsert(d)) changed++;
    }
    index.verifySnapshots(documents);
    return changed;
  }
}
