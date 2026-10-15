package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
public class SearchAccountRepair implements JobHandler {
  public record Pass(UUID owner, UUID event, SearchIndex.Owned after) {}

  private final SearchIndex index;
  private final SearchSources sources;
  private final DurableWork work;
  private final ObjectMapper json;
  private final Clock clock;

  public SearchAccountRepair(
      SearchIndex index, SearchSources sources, DurableWork work, ObjectMapper json, Clock clock) {
    this.index = index;
    this.sources = sources;
    this.work = work;
    this.json = json;
    this.clock = clock;
  }

  public String kind() {
    return "SEARCH_ACCOUNT";
  }

  private void enqueue(Pass pass) {
    work.job(
        kind(), pass.event() + ":" + pass.after(), json.writeValueAsString(pass), clock.instant());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(UUID owner, UUID event) {
    enqueue(new Pass(owner, event, null));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(String payload) {
    var p = json.readValue(payload, Pass.class);
    var batch = index.owned(p.owner(), p.after(), 100);
    for (var kind : Kind.values()) {
      var ids = batch.stream().filter(k -> k.kind() == kind).map(SearchIndex.Owned::id).toList();
      if (!ids.isEmpty()) sources.refresh(kind, ids);
    }
    if (batch.size() == 100) enqueue(new Pass(p.owner(), p.event(), batch.getLast()));
  }
}
