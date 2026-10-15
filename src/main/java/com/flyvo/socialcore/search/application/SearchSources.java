package com.flyvo.socialcore.search.application;

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

  public SearchSources(
      ProfileSearchSources profiles,
      CommunitySearchSources communities,
      ContentSearchSources content,
      SearchIndex index) {
    this.profiles = profiles;
    this.communities = communities;
    this.content = content;
    this.index = index;
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
    int changed = 0;
    var documents = inspect(kind, ids);
    for (var d : documents) if (index.upsert(d)) changed++;
    index.verifySnapshots(documents);
    return changed;
  }
}
