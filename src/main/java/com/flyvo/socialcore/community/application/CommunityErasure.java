package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.application.port.CommunityErasureStore;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class CommunityErasure implements JobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final CommunityErasureStore store;
  private final ObjectMapper json;

  public CommunityErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      CommunityErasureStore store,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.store = store;
    this.json = json;
  }

  public String kind() {
    return "COMMUNITY_ERASURE";
  }

  public void apply(String payload) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.COMMUNITY) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    var items = store.pending(pass.userId(), 100);
    java.util.stream.Stream.concat(
            java.util.stream.Stream.of(pass.userId()),
            items.stream()
                .map(CommunityErasureStore.Item::otherUser)
                .filter(java.util.Objects::nonNull))
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(id -> accounts.guard(id, false));
    var c = erasure.begin(pass.userId(), Step.COMMUNITY, pass.version());
    if (c == null) return;
    if (store.ownsActive(c.userId())) {
      erasure.advance(
          c,
          "START",
          0,
          "WAITING_DEPENDENCY",
          "ACTIVE_OWNERSHIP",
          erasure.databaseTime().plusSeconds(60),
          null);
      return;
    }
    store.guardCommunities(
        items.stream()
            .map(CommunityErasureStore.Item::communityId)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList());
    int changed = 0;
    for (var item : items) if (store.erase(c.userId(), item, erasure.databaseTime())) changed++;
    if (changed > 0 || !store.pending(c.userId(), 1).isEmpty())
      erasure.advance(c, "START", items.size(), "RUNNABLE", null, null, null);
    else erasure.advance(c, "VERIFIED", items.size(), "DONE", null, null, "COMMUNITY_ABSENCE_V1");
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
