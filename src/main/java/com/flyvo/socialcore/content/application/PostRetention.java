package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.media.api.MediaCommands;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PostRetention implements JobHandler {
  private final PostRepository store;
  private final AccountQueries accounts;
  private final MediaCommands media;
  private final Clock clock;
  private final DurableWork work;

  public PostRetention(
      PostRepository store,
      AccountQueries accounts,
      MediaCommands media,
      Clock clock,
      DurableWork work) {
    this.store = store;
    this.accounts = accounts;
    this.media = media;
    this.clock = clock;
    this.work = work;
  }

  public String kind() {
    return "POST_RETENTION";
  }

  public void apply(String payload) {
    UUID id = UUID.fromString(payload);
    var first = store.find(id, false);
    if (first == null) return;
    accounts.guard(first.author(), false);
    // Retention is owner-module maintenance, not a user membership command; no earlier scope
    // mutation is performed.
    var p = store.find(id, true);
    var cutoff = clock.instant().minusSeconds(30L * 86400);
    boolean more = store.pruneRevisions(id, cutoff);
    if (p.deleted() != null && !p.deleted().isAfter(cutoff))
      store.redactDeleted(id, clock.instant());
    if (p.removed() != null && p.deleted() == null && !p.removed().isAfter(cutoff))
      media.release(
          p.author(),
          "POST",
          id,
          p.media().stream()
              .map(com.flyvo.socialcore.content.domain.post.Post.Attachment::id)
              .toList(),
          clock.instant());
    if (more) work.job(kind(), id + ":batch:" + UUID.randomUUID(), id.toString(), clock.instant());
  }
}
