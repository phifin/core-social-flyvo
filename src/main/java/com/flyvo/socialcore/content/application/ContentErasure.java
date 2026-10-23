package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.content.application.port.ContentErasureStore;
import com.flyvo.socialcore.media.api.MediaCommands;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ContentErasure implements JobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final ContentErasureStore store;
  private final MediaCommands media;
  private final DurableWork work;
  private final ObjectMapper json;

  public ContentErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      ContentErasureStore store,
      MediaCommands media,
      DurableWork work,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.store = store;
    this.media = media;
    this.work = work;
    this.json = json;
  }

  public String kind() {
    return "CONTENT_ERASURE";
  }

  private void guards(UUID subject, Collection<UUID> related) {
    java.util.stream.Stream.concat(java.util.stream.Stream.of(subject), related.stream())
        .filter(Objects::nonNull)
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(id -> accounts.guard(id, false));
  }

  private void next(Context c, String stage, int examined) {
    erasure.advance(c, stage, examined, "RUNNABLE", null, null, null);
  }

  private void postEvent(Context c, ContentErasureStore.Changed value, String type) {
    work.event(
        type,
        "post",
        value.id(),
        value.version(),
        0,
        json.writeValueAsString(
            Map.of(
                "postId",
                value.id(),
                "authorId",
                c.userId(),
                "revision",
                value.revision(),
                "status",
                value.status())));
  }

  public void apply(String payload) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.CONTENT) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    if (pass.version() < 0) throw new IllegalStateException("ERASURE_PASS_INVALID");
    var stage = erasure.checkpoint(pass);
    if (stage == null) return;
    if (stage.startsWith("MEDIA_EXPORT:")) {
      exportedMedia(pass);
      return;
    }
    switch (stage) {
      case "START", "REFERENCES" -> references(pass);
      case "SOFT_POSTS" -> softPosts(pass);
      case "SOFT_COMMENTS" -> softComments(pass);
      case "REACTIONS" -> reactions(pass);
      case "WAIT30" -> wait30(pass);
      case "REDACT_POSTS" -> redactPosts(pass);
      case "REDACT_COMMENTS" -> redactComments(pass);
      case "VERIFY" -> verify(pass);
      default -> throw new IllegalStateException("UNKNOWN_CONTENT_ERASURE_STAGE");
    }
  }

  private void references(Pass pass) {
    var root = store.reference(pass.userId());
    guards(pass.userId(), root == null ? List.of() : List.of(root.author()));
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (root == null) {
      next(c, "SOFT_POSTS", 0);
      return;
    }
    store.guardPosts(List.of(root.id()));
    var repair = store.repairReference(c.userId(), root.id(), erasure.databaseTime());
    if (repair != null) {
      var changed = repair.value();
      work.event(
          "PostAudienceChanged",
          "post",
          changed.id(),
          changed.version(),
          0,
          json.writeValueAsString(
              Map.of(
                  "postId",
                  changed.id(),
                  "authorId",
                  root.author(),
                  "revision",
                  changed.revision(),
                  "status",
                  changed.status())));
    }
    next(c, "REFERENCES", repair == null ? 0 : repair.examined());
  }

  private void softPosts(Pass pass) {
    // Schema position0..9/unique(post,position) bounds each root to ten media IDs.
    // Nine roots keep the entire fetched collection (roots plus media) below100.
    var candidates = store.unmarkedPosts(pass.userId(), 9);
    var selected = new ArrayList<ContentErasureStore.Root>();
    int budget = 0;
    for (var root : candidates) {
      int cost = 1 + root.media().size();
      if (budget + cost > 100) break;
      selected.add(root);
      budget += cost;
    }
    guards(pass.userId(), List.of());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (selected.isEmpty()) {
      next(c, "SOFT_COMMENTS", 0);
      return;
    }
    store.guardPosts(selected.stream().map(ContentErasureStore.Root::id).toList());
    var now = erasure.databaseTime();
    for (var root : selected) {
      media.release(
          c.userId(), "POST", root.id(), root.media(), c.requestedAt().plusSeconds(30L * 86400));
      var changed = store.markPost(c.userId(), root.id(), c.requestedAt(), now);
      if (changed != null) postEvent(c, changed, "PostDeleted");
    }
    next(c, "SOFT_POSTS", budget);
  }

  private void softComments(Pass pass) {
    var rows = store.unmarkedComments(pass.userId(), 100);
    guards(pass.userId(), rows.stream().map(ContentErasureStore.CommentRoot::postAuthor).toList());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (rows.isEmpty()) {
      next(c, "REACTIONS", 0);
      return;
    }
    store.guardPosts(
        rows.stream()
            .map(ContentErasureStore.CommentRoot::post)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList());
    var changed =
        store.markComments(
            c.userId(),
            rows.stream().map(ContentErasureStore.CommentRoot::id).toList(),
            erasure.databaseTime());
    var posts = new HashMap<UUID, UUID>();
    for (var row : rows) posts.put(row.id(), row.post());
    for (var value : changed)
      work.event(
          "CommentDeleted",
          "comment",
          value.id(),
          value.version(),
          0,
          json.writeValueAsString(
              Map.of(
                  "commentId",
                  value.id(),
                  "postId",
                  posts.get(value.id()),
                  "authorId",
                  c.userId(),
                  "status",
                  "DELETED")));
    next(c, "SOFT_COMMENTS", rows.size());
  }

  private void reactions(Pass pass) {
    var ids = store.unerasedReactions(pass.userId(), 100);
    guards(pass.userId(), List.of());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (ids.isEmpty()) {
      next(c, "WAIT30", 0);
      return;
    }
    for (var value : store.eraseReactions(c.userId(), ids, erasure.databaseTime())) {
      var payload = new LinkedHashMap<String, Object>();
      payload.put("reactionId", value.id());
      payload.put("actorId", c.userId());
      payload.put("previousType", null);
      payload.put("type", null);
      work.event(
          "ReactionChanged",
          "reaction",
          value.id(),
          value.version(),
          0,
          json.writeValueAsString(payload));
    }
    next(c, "REACTIONS", ids.size());
  }

  private void wait30(Pass pass) {
    guards(pass.userId(), List.of());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    Instant due = c.requestedAt().plusSeconds(30L * 86400);
    if (erasure.databaseTime().isBefore(due))
      erasure.advance(c, "WAIT30", 0, "WAITING_RETENTION", "CONTENT_RETENTION_PENDING", due, null);
    else next(c, "REDACT_POSTS", 0);
  }

  private void redactPosts(Pass pass) {
    var post = store.nextRedactionPost(pass.userId());
    guards(pass.userId(), List.of());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (post == null) {
      next(c, "REDACT_COMMENTS", 0);
      return;
    }
    store.guardPosts(List.of(post));
    int revisions = store.pruneRevisions(post, 100);
    if (revisions > 0) {
      next(c, "REDACT_POSTS", revisions);
      return;
    }
    var mediaIds = store.postMedia(post);
    if (!mediaIds.isEmpty()) {
      var chunk = erasure.recordResources(c, "MEDIA_ASSET", mediaIds);
      erasure.advance(
          c,
          "MEDIA_EXPORT:" + post + ":" + chunk,
          mediaIds.size(),
          "WAITING_EXTERNAL",
          "RESOURCE_EXPORT_PENDING",
          erasure.databaseTime().plusSeconds(60),
          null);
      return;
    }
    int recipients = store.removeRecipients(post, erasure.databaseTime());
    if (recipients > 0) {
      next(c, "REDACT_POSTS", recipients);
      return;
    }
    int mentions = store.removeMentions(post, 100);
    if (mentions > 0) {
      next(c, "REDACT_POSTS", mentions);
      return;
    }
    int examined = store.finishPost(c.userId(), post, erasure.databaseTime());
    next(c, "REDACT_POSTS", examined);
  }

  private void exportedMedia(Pass pass) {
    guards(pass.userId(), List.of());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    var fields = c.checkpoint().split(":");
    if (fields.length != 3) throw new IllegalStateException("CONTENT_MEDIA_CHECKPOINT_INVALID");
    UUID post = UUID.fromString(fields[1]), chunk = UUID.fromString(fields[2]);
    if (!erasure.resourcesExported(c, chunk)) {
      erasure.advance(
          c,
          c.checkpoint(),
          0,
          "WAITING_EXTERNAL",
          "RESOURCE_EXPORT_PENDING",
          erasure.databaseTime().plusSeconds(60),
          null);
      return;
    }
    store.guardPosts(List.of(post));
    var ids = erasure.resourceIds(c, chunk);
    store.removeMedia(post, ids);
    next(c, "REDACT_POSTS", ids.size());
  }

  private void redactComments(Pass pass) {
    var rows = store.unredactedComments(pass.userId(), 100);
    guards(pass.userId(), rows.stream().map(ContentErasureStore.CommentRoot::postAuthor).toList());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (rows.isEmpty()) {
      next(c, "VERIFY", 0);
      return;
    }
    store.guardPosts(
        rows.stream()
            .map(ContentErasureStore.CommentRoot::post)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList());
    int count =
        store.redactComments(
            c.userId(),
            rows.stream().map(ContentErasureStore.CommentRoot::id).toList(),
            erasure.databaseTime());
    if (count == 0) throw new IllegalStateException("CONTENT_COMMENT_REDACTION_STALLED");
    next(c, "REDACT_COMMENTS", count);
  }

  private void verify(Pass pass) {
    guards(pass.userId(), List.of());
    var c = erasure.begin(pass.userId(), Step.CONTENT, pass.version());
    if (c == null) return;
    if (store.residual(c.userId())) throw new IllegalStateException("CONTENT_ERASURE_RESIDUAL");
    erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "CONTENT_ABSENCE_V1");
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
