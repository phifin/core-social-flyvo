package com.flyvo.socialcore.feed.application;

import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.api.ContentQueries.*;
import com.flyvo.socialcore.feed.api.FeedQueries;
import com.flyvo.socialcore.platform.api.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class Feeds implements FeedQueries {
  public static final int BATCH = 100, MAX_EXAMINED = 1000;
  public static final long QUERY_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
  private final ContentQueries content;
  private final Interactions interactions;
  private final Secrets secrets;
  private final ObjectMapper json;
  private final Clock clock;
  private final MeterRegistry metrics;
  private final java.util.function.LongSupplier ticks;
  private final ChronologicalRanker ranker = new ChronologicalRanker();

  @org.springframework.beans.factory.annotation.Autowired
  public Feeds(
      ContentQueries content,
      Interactions interactions,
      Secrets secrets,
      ObjectMapper json,
      Clock clock,
      MeterRegistry metrics) {
    this(content, interactions, secrets, json, clock, metrics, System::nanoTime);
  }

  Feeds(
      ContentQueries content,
      Interactions interactions,
      Secrets secrets,
      ObjectMapper json,
      Clock clock,
      MeterRegistry metrics,
      java.util.function.LongSupplier ticks) {
    this.content = content;
    this.interactions = interactions;
    this.secrets = secrets;
    this.json = json;
    this.clock = clock;
    this.metrics = metrics;
    this.ticks = ticks;
  }

  public record Cursor(int version, Instant asOf, Instant expires, Candidate last) {}

  public Page home(ActorContext actor, int size, String after) {
    return page(actor, new FeedScope(FeedMode.HOME, null), size, after);
  }

  public Page community(ActorContext actor, UUID id, int size, String after) {
    return page(actor, new FeedScope(FeedMode.COMMUNITY, id), size, after);
  }

  public Page timeline(ActorContext actor, UUID id, int size, String after) {
    return page(actor, new FeedScope(FeedMode.TIMELINE, id), size, after);
  }

  public Page page(ActorContext actor, FeedScope scope, int size, String after) {
    // Owner query APIs each hold their short read transaction. Orchestration,
    // ranking, cursor crypto and metrics must not retain a pooled connection.
    // Each source/body statement remains coherent; pages were never snapshots.
    if (size < 1 || size > 50) throw Failure.invalid();
    if (actor == null) actor = ActorContext.anonymous();
    long started = ticks.getAsLong(), queryNanos = 0;
    int queries = 0, examined = 0;
    long q = ticks.getAsLong();
    content.authorizeFeed(actor, scope);
    queryNanos += ticks.getAsLong() - q;
    queries++;
    String purpose =
        "feed-cursor:v1:"
            + actor.userId()
            + ":"
            + scope.mode()
            + ":"
            + scope.id()
            + ":size="
            + size;
    Cursor cursor;
    if (after == null) {
      q = ticks.getAsLong();
      var cutoff = content.feedCutoff();
      queryNanos += ticks.getAsLong() - q;
      queries++;
      cursor = new Cursor(1, cutoff, clock.instant().plusSeconds(1800), null);
    } else {
      try {
        if (after.length() > 4096) throw new IllegalArgumentException();
        cursor =
            json.readValue(
                secrets.decrypt(purpose, Base64.getUrlDecoder().decode(after)), Cursor.class);
        if (cursor.version() != 1
            || cursor.asOf() == null
            || cursor.expires() == null
            || !clock.instant().isBefore(cursor.expires())
            || cursor.last() == null
            || cursor.last().id() == null
            || cursor.last().publishedAt() == null
            || cursor.last().publishedAt().isAfter(cursor.asOf()))
          throw new IllegalArgumentException();
      } catch (RuntimeException invalid) {
        throw new Failure(400, "INVALID_CURSOR");
      }
    }
    var results = new ArrayList<PostView>();
    var seen = new HashSet<UUID>();
    Candidate last = cursor.last();
    boolean exhausted = false;
    // Always allow a first scan: there must be an examined position to continue.
    while (results.size() < size
        && examined < MAX_EXAMINED
        && (examined == 0 || queryNanos < QUERY_BUDGET_NANOS)) {
      q = ticks.getAsLong();
      var candidates =
          ranker.rank(content.scanCandidates(actor, scope, BATCH, cursor.asOf(), last));
      queryNanos += ticks.getAsLong() - q;
      queries++;
      if (candidates.isEmpty()) {
        exhausted = true;
        break;
      }
      q = ticks.getAsLong();
      var hydrated =
          content.hydrateAuthorized(
              actor, scope, candidates.stream().map(Candidate::id).distinct().toList());
      queryNanos += ticks.getAsLong() - q;
      queries++;
      var current =
          hydrated.stream().collect(java.util.stream.Collectors.toMap(PostView::id, p -> p));
      int consumed = 0;
      for (var candidate : candidates) {
        if (last != null && ChronologicalRanker.ORDER.compare(candidate, last) <= 0)
          throw new Failure(503, "FEED_SOURCE_ORDER");
        last = candidate;
        examined++;
        consumed++;
        if (seen.add(candidate.id()) && current.containsKey(candidate.id()))
          results.add(current.get(candidate.id()));
        if (results.size() == size || examined == MAX_EXAMINED) break;
      }
      if (consumed == candidates.size() && candidates.size() < BATCH) {
        exhausted = true;
        break;
      }
    }
    q = ticks.getAsLong();
    var engagement = interactions.engagement(actor, results.stream().map(PostView::id).toList());
    queryNanos += ticks.getAsLong() - q;
    queries++;
    var items = results.stream().map(p -> new Item(p, engagement.get(p.id()))).toList();
    String next =
        exhausted || last == null
            ? null
            : Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    secrets.encrypt(
                        purpose,
                        json.writeValueAsString(
                            new Cursor(1, cursor.asOf(), cursor.expires(), last))));
    String mode = scope.mode().name().toLowerCase(Locale.ROOT);
    metrics.counter("feed.requests", "mode", mode).increment();
    metrics.counter("feed.candidates.examined", "mode", mode).increment(examined);
    metrics.counter("feed.items.returned", "mode", mode).increment(items.size());
    metrics
        .summary("feed.scan.ratio", "mode", mode)
        .record((double) examined / Math.max(1, items.size()));
    metrics.summary("feed.query.calls", "mode", mode).record(queries);
    metrics.timer("feed.query.duration", "mode", mode).record(queryNanos, TimeUnit.NANOSECONDS);
    metrics
        .timer("feed.request.duration", "mode", mode)
        .record(ticks.getAsLong() - started, TimeUnit.NANOSECONDS);
    if (!exhausted && queryNanos >= QUERY_BUDGET_NANOS)
      metrics.counter("feed.budget.exhausted", "mode", mode, "kind", "query").increment();
    if (examined == MAX_EXAMINED)
      metrics.counter("feed.budget.exhausted", "mode", mode, "kind", "candidates").increment();
    return new Page(items, next);
  }
}
