package com.flyvo.socialcore.feed.application;

import com.flyvo.socialcore.content.api.ContentQueries.Candidate;
import java.util.Comparator;
import java.util.List;

/** Concrete chronological ordering; no speculative scoring interface. */
public final class ChronologicalRanker {
  public static final Comparator<Candidate> ORDER =
      Comparator.comparing(Candidate::publishedAt).thenComparing(c -> c.id().toString()).reversed();

  public List<Candidate> rank(List<Candidate> candidates) {
    return candidates.stream().sorted(ORDER).toList();
  }
}
