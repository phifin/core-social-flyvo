package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentSearch implements ContentSearchSources {
  private final PostRepository store;
  private final AccountQueries accounts;
  private final ProfileQueries profiles;
  private final ContentQueries content;
  private final Clock clock;

  public ContentSearch(
      PostRepository store,
      AccountQueries accounts,
      ProfileQueries profiles,
      ContentQueries content,
      Clock clock) {
    this.store = store;
    this.accounts = accounts;
    this.profiles = profiles;
    this.content = content;
    this.clock = clock;
  }

  private void batch(List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
  }

  @Transactional(readOnly = true)
  public List<UUID> scan(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw Failure.invalid();
    return store.searchScan(after, limit);
  }

  @Transactional(readOnly = true)
  public List<UUID> scanByAuthor(UUID author, UUID after, int limit) {
    if (author == null || limit < 1 || limit > 100) throw Failure.invalid();
    return store.searchScanByAuthor(author, after, limit);
  }

  @Transactional(readOnly = true)
  public List<Source> inspect(List<UUID> ids) {
    batch(ids);
    var sources = store.searchSources(ids);
    var statuses =
        accounts.snapshots(
            sources.stream().map(PostRepository.SearchSource::authorId).distinct().toList());
    return sources.stream()
        .map(
            p -> {
              var a = statuses.get(p.authorId());
              return new Source(
                  p.id(),
                  p.authorId(),
                  p.communityId(),
                  p.version(),
                  a == null ? null : a.version(),
                  "PUBLISHED".equals(p.status()) && a != null && a.active(),
                  p.body());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public void authorize(ActorContext actor, UUID community) {
    if (community != null)
      content.authorizeFeed(
          actor, new ContentQueries.FeedScope(ContentQueries.FeedMode.COMMUNITY, community));
  }

  @Transactional(readOnly = true)
  public List<Card> hydrate(ActorContext actor, List<UUID> ids, UUID community) {
    batch(ids);
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var account = accounts.find(actor.userId());
    if (account == null || !account.active()) throw Failure.unauthorized();
    authorize(actor, community);
    var source = store.searchLoad(actor.userId(), ids, community, clock.instant());
    var names =
        profiles
            .attributionSummaries(source.stream().map(p -> p.author()).distinct().toList())
            .stream()
            .collect(
                Collectors.toMap(
                    ProfileQueries.Attribution::userId, ProfileQueries.Attribution::displayName));
    return source.stream()
        .map(
            p ->
                new Card(
                    p.id(),
                    p.author(),
                    names.get(p.author()),
                    p.community(),
                    p.body().text(),
                    p.published(),
                    p.version()))
        .toList();
  }
}
