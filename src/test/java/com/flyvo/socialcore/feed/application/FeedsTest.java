package com.flyvo.socialcore.feed.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.api.ContentQueries.*;
import com.flyvo.socialcore.platform.api.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Orchestration tests only; real authorization/crypto/SQL are tested in FeedsIT. */
class FeedsTest {
  final ContentQueries content = mock(ContentQueries.class);
  final Interactions interactions = mock(Interactions.class);
  final Secrets secrets = mock(Secrets.class);
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final AtomicLong ticks = new AtomicLong();
  final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);
  final ActorContext actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
  final Feeds feeds =
      new Feeds(
          content,
          interactions,
          secrets,
          JsonMapper.builder().findAndAddModules().build(),
          clock,
          metrics,
          ticks::get);

  FeedsTest() {
    when(content.feedCutoff()).thenReturn(clock.instant());
    when(interactions.engagement(any(), anyList())).thenReturn(Map.of());
    when(secrets.encrypt(anyString(), anyString()))
        .thenAnswer(c -> ((String) c.getArgument(1)).getBytes(StandardCharsets.UTF_8));
    when(secrets.decrypt(anyString(), any()))
        .thenAnswer(c -> new String((byte[]) c.getArgument(1), StandardCharsets.UTF_8));
    when(content.hydrateAuthorized(any(), any(), anyList())).thenReturn(List.of());
  }

  List<Candidate> candidates(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new Candidate(new UUID(0, i + 1), clock.instant().minusSeconds(i + 1)))
        .toList();
  }

  void source(List<Candidate> rows) {
    when(content.scanCandidates(any(), any(), anyInt(), any(), any()))
        .thenAnswer(
            c -> {
              Candidate after = c.getArgument(4);
              return rows.stream()
                  .filter(k -> after == null || ChronologicalRanker.ORDER.compare(k, after) > 0)
                  .limit((Integer) c.getArgument(2))
                  .toList();
            });
  }

  @Test
  void thousandExaminedLimitProducesEmptyContinuationAndAdvancesWithoutLoops() {
    source(candidates(1100));
    var first = feeds.home(actor, 20, null);
    assertThat(first.items()).isEmpty();
    assertThat(first.nextCursor()).isNotNull();
    assertThat(metrics.get("feed.candidates.examined").tag("mode", "home").counter().count())
        .isEqualTo(1000);
    verify(content, times(10)).scanCandidates(any(), any(), eq(100), any(), any());
    var next = feeds.home(actor, 20, first.nextCursor());
    assertThat(next.items()).isEmpty();
    assertThat(next.nextCursor()).isNull();
    assertThat(metrics.get("feed.candidates.examined").tag("mode", "home").counter().count())
        .isEqualTo(1100);
  }

  @Test
  void measuredQueryBudgetStopsRefillAtLastExaminedNotPrefetchedEnd() {
    source(candidates(1100));
    when(content.hydrateAuthorized(any(), any(), anyList()))
        .thenAnswer(
            c -> {
              ticks.addAndGet(Feeds.QUERY_BUDGET_NANOS);
              return List.of();
            });
    var first = feeds.home(actor, 20, null);
    assertThat(first.nextCursor()).isNotNull();
    verify(content, times(1)).scanCandidates(any(), any(), anyInt(), any(), any());
    assertThat(metrics.get("feed.candidates.examined").tag("mode", "home").counter().count())
        .isEqualTo(100);
    var cursor =
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .readValue(
                new String(
                    Base64.getUrlDecoder().decode(first.nextCursor()), StandardCharsets.UTF_8),
                Feeds.Cursor.class);
    assertThat(cursor.last().id()).isEqualTo(new UUID(0, 100));
  }

  @Test
  void repeatedOrNonadvancingCandidateFailsClosedInsteadOfLooping() {
    var row = candidates(1).getFirst();
    when(content.scanCandidates(any(), any(), anyInt(), any(), any()))
        .thenReturn(Collections.nCopies(100, row));
    assertThatThrownBy(() -> feeds.home(actor, 20, null))
        .isInstanceOfSatisfying(
            Failure.class, f -> assertThat(f.code()).isEqualTo("FEED_SOURCE_ORDER"));
  }
}
