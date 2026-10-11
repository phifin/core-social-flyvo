package com.flyvo.socialcore.feed;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.content.api.ContentQueries.Candidate;
import com.flyvo.socialcore.feed.application.ChronologicalRanker;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChronologicalRankerTest {
  @Test
  void immutableTimeAndPostgresUnsignedUuidOrderAreDescending() {
    var time = Instant.parse("2026-10-11T00:00:00Z");
    var first = new Candidate(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), time);
    var second = new Candidate(UUID.fromString("00000000-0000-0000-0000-000000000001"), time);
    var newest = new Candidate(second.id(), time.plusSeconds(1));
    assertThat(new ChronologicalRanker().rank(List.of(second, first, newest)))
        .containsExactly(newest, first, second);
  }
}
