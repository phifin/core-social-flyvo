package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class SearchProjector implements EventConsumer {
  private final SearchSources sources;
  private final SearchAccountRepair repair;
  private final Clock clock;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public SearchProjector(
      SearchSources sources,
      SearchAccountRepair repair,
      Clock clock,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.sources = sources;
    this.repair = repair;
    this.clock = clock;
    this.metrics = metrics;
  }

  public String name() {
    return "search-documents-v1";
  }

  public boolean accepts(String type) {
    return Set.of(
            "UserRegistered",
            "AccountStatusChanged",
            "AccountDeletionRequested",
            "ProfileUpdated",
            "CommunityCreated",
            "CommunityUpdated",
            "PostCreated",
            "PostUpdated",
            "PostAudienceChanged",
            "PostDeleted",
            "PostRemoved",
            "PostRestored")
        .contains(type);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(EventEnvelope event) {
    if (event.schemaVersion() != 1) throw new IllegalStateException("UNSUPPORTED_EVENT_SCHEMA");
    Kind kind =
        switch (event.aggregateType()) {
          case "account", "profile" -> Kind.USER;
          case "community" -> Kind.COMMUNITY;
          case "post" -> Kind.POST;
          default -> throw new IllegalStateException("INVALID_SEARCH_AGGREGATE");
        };
    sources.refresh(kind, List.of(event.aggregateId()));
    if (event.aggregateType().equals("account") && !event.type().equals("UserRegistered"))
      repair.enqueue(event.aggregateId(), event.eventId());
    var duration = Duration.between(event.occurredAt(), clock.instant());
    metrics
        .timer("social.search.projection.lag", "kind", kind.name())
        .record(duration.isNegative() ? Duration.ZERO : duration);
  }
}
