package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.content.application.port.InteractionRepository;
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
  private final InteractionRepository interactions;
  private final tools.jackson.databind.ObjectMapper json;

  public PostRetention(
      PostRepository store,
      AccountQueries accounts,
      MediaCommands media,
      Clock clock,
      DurableWork work,
      InteractionRepository interactions,
      tools.jackson.databind.ObjectMapper json) {
    this.store = store;
    this.accounts = accounts;
    this.media = media;
    this.clock = clock;
    this.work = work;
    this.interactions = interactions;
    this.json = json;
  }

  public String kind() {
    return "POST_RETENTION";
  }

  public void apply(String payload) {
    UUID id = UUID.fromString(payload);
    var first = store.find(id, false);
    if (first == null) return;
    var author = accounts.guard(first.author(), false);
    // Retention is owner-module maintenance, not a user membership command; no earlier scope
    // mutation is performed.
    var p = store.find(id, true);
    // Closure can win before the content owner has marked this root. Ordinary parent
    // retention must then leave other authors' descendants for the erasure branch.
    if (!author.active() || store.erasureOrigin(id)) return;
    var cutoff = clock.instant().minusSeconds(30L * 86400);
    boolean more = store.pruneRevisions(id, cutoff);
    if (p.deleted() != null && !p.deleted().isAfter(cutoff)) {
      store.redactDeleted(id, clock.instant());
      var redacted = interactions.redactForDeletedPost(id, cutoff, clock.instant());
      for (var c : redacted) {
        work.event(
            "CommentDeleted",
            "comment",
            c.id(),
            c.version(),
            0,
            json.writeValueAsString(
                java.util.Map.of(
                    "commentId",
                    c.id(),
                    "postId",
                    c.post(),
                    "authorId",
                    c.author(),
                    "status",
                    c.status())));
        work.auditScoped(
            p.author(),
            "COMMENT_PARENT_RETENTION",
            "comment",
            c.id(),
            p.community(),
            "SUCCESS",
            null);
      }
      more |= redacted.size() == 500;
    }
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
