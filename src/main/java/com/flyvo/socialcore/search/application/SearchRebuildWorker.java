package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
public class SearchRebuildWorker implements JobHandler {
  public record Pass(UUID id, long version) {}

  private final SearchIndex index;
  private final SearchSources sources;
  private final DurableWork work;
  private final ObjectMapper json;
  private final Clock clock;

  public SearchRebuildWorker(
      SearchIndex index, SearchSources sources, DurableWork work, ObjectMapper json, Clock clock) {
    this.index = index;
    this.sources = sources;
    this.work = work;
    this.json = json;
    this.clock = clock;
  }

  public String kind() {
    return "SEARCH_REBUILD";
  }

  public void enqueue(SearchIndex.Rebuild r, String key) {
    work.job(
        kind(),
        r.id() + ":" + key,
        json.writeValueAsString(new Pass(r.id(), r.version())),
        clock.instant());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(String payload) {
    var p = json.readValue(payload, Pass.class);
    var r = index.rebuild(p.id(), false);
    if (r == null || r.version() != p.version() || "DONE".equals(r.stage())) return;
    var ids =
        "EXPORT".equals(r.stage())
            ? sources.scan(r.kind(), r.after())
            : index.scan(r.kind(), r.after(), 100);
    var captured = sources.guard(r.kind(), ids);
    var locked = index.rebuild(p.id(), true);
    if (locked == null || !locked.equals(r)) return;
    int changed = sources.materialize(captured);
    String next = ids.size() == 100 ? r.stage() : "EXPORT".equals(r.stage()) ? "RECONCILE" : "DONE";
    UUID after = r.stage().equals(next) ? ids.getLast() : null;
    index.checkpoint(r, next, after, ids.size(), changed, clock.instant());
    var updated = index.rebuild(r.id(), false);
    if (!"DONE".equals(next)) enqueue(updated, Long.toString(updated.version()));
    else
      work.audit(
          r.requester(),
          "SEARCH_REBUILD_COMPLETED",
          "search_rebuild",
          r.id(),
          "SUCCESS",
          r.kind().name());
  }
}
