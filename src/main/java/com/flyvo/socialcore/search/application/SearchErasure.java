package com.flyvo.socialcore.search.application;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.content.api.ContentSearchSources;
import com.flyvo.socialcore.platform.api.JobHandler;
import com.flyvo.socialcore.search.api.SearchQueries.Kind;
import com.flyvo.socialcore.search.application.port.SearchIndex;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class SearchErasure implements JobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final SearchIndex index;
  private final ContentSearchSources content;
  private final ObjectMapper json;

  public SearchErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      SearchIndex index,
      ContentSearchSources content,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.index = index;
    this.content = content;
    this.json = json;
  }

  public String kind() {
    return "SEARCH_ERASURE";
  }

  public void apply(String payload) {
    var p = json.readValue(payload, Pass.class);
    if (p.step() != Step.SEARCH) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    String stage = erasure.checkpoint(p);
    if (stage == null) return;
    accounts.guard(p.userId(), false);
    var c = erasure.begin(p.userId(), Step.SEARCH, p.version());
    if (c == null) return;
    if (stage.equals("START")) {
      index.erase(c.userId(), Kind.USER, c.userId());
      erasure.advance(c, "POST:START", 1, "RUNNABLE", null, null, null);
      return;
    }
    var fields = stage.split(":");
    if (fields[0].equals("POST")) {
      UUID after = fields[1].equals("START") ? null : UUID.fromString(fields[1]);
      var ids = content.scanByAuthor(c.userId(), after, 100);
      for (var id : ids) index.erase(c.userId(), Kind.POST, id);
      erasure.advance(
          c,
          ids.isEmpty() ? "INDEX:START" : "POST:" + ids.getLast(),
          ids.size(),
          "RUNNABLE",
          null,
          null,
          null);
      return;
    }
    if (fields[0].equals("INDEX")) {
      SearchIndex.Owned after =
          fields[1].equals("START")
              ? null
              : new SearchIndex.Owned(Kind.valueOf(fields[1]), UUID.fromString(fields[2]));
      var ids = index.owned(c.userId(), after, 100);
      for (var id : ids) index.erase(c.userId(), id.kind(), id.id());
      if (ids.isEmpty()) erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "SEARCH_ABSENCE_V1");
      else {
        var last = ids.getLast();
        erasure.advance(
            c, "INDEX:" + last.kind() + ":" + last.id(), ids.size(), "RUNNABLE", null, null, null);
      }
      return;
    }
    throw new IllegalStateException("SEARCH_ERASURE_CHECKPOINT_INVALID");
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
