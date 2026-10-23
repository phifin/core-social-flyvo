package com.flyvo.socialcore.graph.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.graph.api.event.RelationshipChanged;
import com.flyvo.socialcore.graph.application.port.GraphErasureStore;
import com.flyvo.socialcore.graph.application.port.GraphRepository;
import com.flyvo.socialcore.graph.domain.UserPair;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class GraphErasure implements JobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final GraphRepository graph;
  private final GraphErasureStore store;
  private final DurableWork work;
  private final ObjectMapper json;

  public GraphErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      GraphRepository graph,
      GraphErasureStore store,
      DurableWork work,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.graph = graph;
    this.store = store;
    this.work = work;
    this.json = json;
  }

  public String kind() {
    return "GRAPH_ERASURE";
  }

  public void apply(String payload) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.GRAPH) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    // A pair contains at most six directed edges, one friendship and one pending request.
    var others = store.counterparts(pass.userId(), 12);
    java.util.stream.Stream.concat(java.util.stream.Stream.of(pass.userId()), others.stream())
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(id -> accounts.guard(id, false));
    var c = erasure.begin(pass.userId(), Step.GRAPH, pass.version());
    if (c == null) return;
    var pairs =
        others.stream()
            .map(id -> UserPair.of(c.userId(), id))
            .sorted(Comparator.comparingLong(UserPair::lockKey))
            .toList();
    for (var p : pairs) graph.guard(p.lockKey(), true);
    var now = erasure.databaseTime();
    int examined = 0;
    for (var p : pairs) {
      boolean changed = false;
      for (var kind : java.util.List.of("following", "blocks", "mutes")) {
        if (graph.edge(kind, p.low(), p.high(), false, now)) {
          changed = true;
          examined++;
        }
        if (graph.edge(kind, p.high(), p.low(), false, now)) {
          changed = true;
          examined++;
        }
      }
      if (graph.unfriend(p)) {
        changed = true;
        examined++;
      }
      var request = graph.pending(p);
      if (request != null) {
        graph.resolve(request.id(), "CANCELLED", now);
        changed = true;
        examined++;
      }
      if (changed) {
        var revision = graph.bump(p);
        work.event(
            "GraphErased",
            "graph",
            revision.id(),
            revision.version(),
            0,
            json.writeValueAsString(
                new RelationshipChanged(
                    p.low(),
                    p.high(),
                    c.userId(),
                    request == null ? null : request.id(),
                    request == null ? null : "CANCELLED",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)));
      }
    }
    if (!store.counterparts(c.userId(), 1).isEmpty())
      erasure.advance(c, "START", examined, "RUNNABLE", null, null, null);
    else erasure.advance(c, "VERIFIED", examined, "DONE", null, null, "GRAPH_ABSENCE_V1");
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
