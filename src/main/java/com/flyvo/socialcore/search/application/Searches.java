package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.CommunitySearchSources;
import com.flyvo.socialcore.content.api.ContentSearchSources;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.api.ProfileSearchSources;
import com.flyvo.socialcore.search.api.SearchQueries;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import com.flyvo.socialcore.search.domain.SearchTerms;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class Searches implements SearchQueries {
  private final SearchIndex index;
  private final AccountQueries accounts;
  private final ProfileSearchSources profiles;
  private final CommunitySearchSources communities;
  private final ContentSearchSources content;
  private final Secrets secrets;
  private final ObjectMapper json;
  private final Clock clock;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public Searches(
      SearchIndex index,
      AccountQueries accounts,
      ProfileSearchSources profiles,
      CommunitySearchSources communities,
      ContentSearchSources content,
      Secrets secrets,
      ObjectMapper json,
      Clock clock,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.index = index;
    this.accounts = accounts;
    this.profiles = profiles;
    this.communities = communities;
    this.content = content;
    this.secrets = secrets;
    this.json = json;
    this.clock = clock;
    this.metrics = metrics;
  }

  public record Cursor(Instant cutoff, Instant expires, SearchIndex.Key after) {}

  private Cursor cursor(String purpose, String token) {
    if (token == null) return new Cursor(index.cutoff(), clock.instant().plusSeconds(1800), null);
    try {
      if (token.length() > 4096) throw new IllegalArgumentException();
      var c =
          json.readValue(
              secrets.decrypt(purpose, Base64.getUrlDecoder().decode(token)), Cursor.class);
      if (c.cutoff() == null
          || c.expires() == null
          || c.after() == null
          || c.after().id() == null
          || c.after().rank() < 0
          || !clock.instant().isBefore(c.expires())) throw new IllegalArgumentException();
      return c;
    } catch (RuntimeException e) {
      throw new Failure(400, "INVALID_CURSOR");
    }
  }

  private static String snippet(String body) {
    if (body == null) return "";
    return body.substring(
        0, body.offsetByCodePoints(0, Math.min(240, body.codePointCount(0, body.length()))));
  }

  // No orchestration transaction: release owner connections before crypto/refill,
  // and keep current source/body coherence inside each owner hydration statement.
  private <T> Page<T> page(
      ActorContext actor,
      Kind kind,
      String query,
      UUID scope,
      int size,
      String token,
      Function<List<UUID>, Map<UUID, T>> hydrate) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
    if (size < 1 || size > 50) throw Failure.invalid();
    SearchTerms terms;
    try {
      terms = SearchTerms.parse(query);
    } catch (IllegalArgumentException e) {
      throw Failure.invalid();
    }
    if (scope != null) content.authorize(actor, scope);
    String purpose =
        "search-page:v1:"
            + actor.userId()
            + ":"
            + kind
            + ":"
            + scope
            + ":"
            + size
            + ":"
            + terms.normalized();
    var c = cursor(purpose, token);
    var last = c.after();
    var items = new ArrayList<T>();
    int examined = 0;
    boolean more = false;
    long started = System.nanoTime();
    try {
      do {
        var batch = index.candidates(kind, terms, scope, c.cutoff(), last, 100);
        if (batch.isEmpty()) {
          more = false;
          break;
        }
        var authorized = hydrate.apply(batch.stream().map(SearchIndex.Key::id).toList());
        int consumed = 0;
        for (var key : batch) {
          examined++;
          consumed++;
          last = key;
          var item = authorized.get(key.id());
          if (item != null) items.add(item);
          if (items.size() == size || examined == 1000) break;
        }
        more = consumed < batch.size() || batch.size() == 100;
        if (!more || items.size() == size) break;
      } while (examined < 1000 && System.nanoTime() - started < 200_000_000L);
      String after =
          more
              ? Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      secrets.encrypt(
                          purpose,
                          json.writeValueAsString(new Cursor(c.cutoff(), c.expires(), last))))
              : null;
      return new Page<>(List.copyOf(items), after);
    } finally {
      metrics.summary("social.search.examined", "kind", kind.name()).record(examined);
      metrics.summary("social.search.returned", "kind", kind.name()).record(items.size());
      metrics
          .timer("social.search.query.duration", "kind", kind.name())
          .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  public Page<User> users(ActorContext actor, String query, int size, String after) {
    return page(
        actor,
        Kind.USER,
        query,
        null,
        size,
        after,
        ids ->
            profiles.hydrate(actor, ids).stream()
                .collect(
                    Collectors.toMap(
                        ProfileSearchSources.Card::id,
                        p -> new User(p.id(), p.handle(), p.name(), p.version()))));
  }

  public Page<Community> communities(ActorContext actor, String query, int size, String after) {
    return page(
        actor,
        Kind.COMMUNITY,
        query,
        null,
        size,
        after,
        ids ->
            communities.hydrate(actor, ids).stream()
                .collect(
                    Collectors.toMap(
                        CommunitySearchSources.Card::id,
                        c ->
                            new Community(
                                c.id(),
                                c.slug(),
                                c.name(),
                                snippet(c.description()),
                                c.visibility(),
                                c.version()))));
  }

  public Page<Post> posts(
      ActorContext actor, String query, UUID community, int size, String after) {
    return page(
        actor,
        Kind.POST,
        query,
        community,
        size,
        after,
        ids ->
            content.hydrate(actor, ids, community).stream()
                .collect(
                    Collectors.toMap(
                        ContentSearchSources.Card::id,
                        p ->
                            new Post(
                                p.id(),
                                p.authorId(),
                                p.displayName(),
                                p.communityId(),
                                snippet(p.body()),
                                p.publishedAt(),
                                p.version()))));
  }
}
