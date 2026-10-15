package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.search.api.*;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchRebuilds implements SearchMaintenance {
  private final AccountQueries accounts;
  private final SearchIndex index;
  private final SearchRebuildWorker worker;
  private final DurableWork work;
  private final Clock clock;

  public SearchRebuilds(
      AccountQueries accounts,
      SearchIndex index,
      SearchRebuildWorker worker,
      DurableWork work,
      Clock clock) {
    this.accounts = accounts;
    this.index = index;
    this.worker = worker;
    this.work = work;
    this.clock = clock;
  }

  private void admin(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.guard(actor.userId(), false);
    if (!a.active() || !"ADMIN".equals(a.platformRole())) throw new Failure(403, "ACCESS_DENIED");
  }

  private Rebuild info(SearchIndex.Rebuild r) {
    return new Rebuild(
        r.id(),
        r.kind(),
        r.stage(),
        r.version(),
        r.examined(),
        r.changed(),
        r.createdAt(),
        r.completedAt());
  }

  @Transactional
  public Rebuild start(ActorContext actor, SearchQueries.Kind kind, UUID resume, Long expected) {
    admin(actor);
    if (kind == null) throw Failure.invalid();
    SearchIndex.Rebuild r;
    if (resume == null) {
      if (expected != null) throw Failure.invalid();
      r = index.start(UUID.randomUUID(), actor.userId(), kind, clock.instant());
    } else {
      r = index.rebuild(resume, true);
      if (r == null || r.kind() != kind) throw new Failure(404, "NOT_FOUND");
      if (expected == null || r.version() != expected) throw new Failure(412, "STALE_VERSION");
      if ("DONE".equals(r.stage())) throw Failure.conflict();
    }
    // Unique resume job key: never wait on a running platform job row while
    // holding its rebuild state (worker takes job row before rebuild state).
    worker.enqueue(r, "operator:" + UUID.randomUUID());
    work.audit(
        actor.userId(),
        resume == null ? "SEARCH_REBUILD_STARTED" : "SEARCH_REBUILD_RESUMED",
        "search_rebuild",
        r.id(),
        "SUCCESS",
        kind.name());
    return info(r);
  }

  @Transactional
  public Rebuild read(ActorContext actor, UUID id) {
    admin(actor);
    var r = index.rebuild(id, false);
    if (r == null) throw new Failure(404, "NOT_FOUND");
    work.audit(actor.userId(), "SEARCH_REBUILD_READ", "search_rebuild", id, "SUCCESS", null);
    return info(r);
  }
}
