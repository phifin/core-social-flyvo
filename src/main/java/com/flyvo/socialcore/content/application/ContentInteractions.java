package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.MembershipQueries;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.application.port.*;
import com.flyvo.socialcore.content.domain.comment.Comment;
import com.flyvo.socialcore.content.domain.post.Post;
import com.flyvo.socialcore.graph.api.RelationshipQueries;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContentInteractions implements Interactions, CommentModeration, ContentReportProof {
  private final InteractionRepository store;
  private final PostRepository posts;
  private final ContentQueries content;
  private final AccountQueries accounts;
  private final MembershipQueries communities;
  private final RelationshipQueries graph;
  private final ProfileQueries profiles;
  private final DurableWork work;
  private final Idempotency keys;
  private final Secrets secrets;
  private final tools.jackson.databind.ObjectMapper json;
  private final Clock clock;

  public ContentInteractions(
      InteractionRepository store,
      PostRepository posts,
      ContentQueries content,
      AccountQueries accounts,
      MembershipQueries communities,
      RelationshipQueries graph,
      ProfileQueries profiles,
      DurableWork work,
      Idempotency keys,
      Secrets secrets,
      tools.jackson.databind.ObjectMapper json,
      Clock clock) {
    this.store = store;
    this.posts = posts;
    this.content = content;
    this.accounts = accounts;
    this.communities = communities;
    this.graph = graph;
    this.profiles = profiles;
    this.work = work;
    this.keys = keys;
    this.secrets = secrets;
    this.json = json;
    this.clock = clock;
  }

  private UUID viewer(ActorContext actor) {
    if (actor == null || !actor.authenticated()) return null;
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
    return actor.userId();
  }

  private Comment existing(UUID id) {
    var c = store.find(id, false);
    if (c == null) throw new Failure(404, "NOT_FOUND");
    return c;
  }

  private Post post(UUID id) {
    var p = posts.find(id, false);
    if (p == null) throw new Failure(404, "NOT_FOUND");
    return p;
  }

  private void body(String text) {
    try {
      Comment.validateBody(text);
    } catch (IllegalArgumentException e) {
      throw Failure.invalid();
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Proof reportProof(ActorContext actor, UUID postId, UUID commentId) {
    if ((postId == null) == (commentId == null)) throw Failure.invalid();
    var c = commentId == null ? null : existing(commentId);
    var p = post(c == null ? postId : c.post());
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var ids = new HashSet<UUID>(List.of(actor.userId(), p.author()));
    if (c != null) ids.add(c.author());
    for (var id : ids.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
      if (!accounts.guard(id, false).active()) {
        if (id.equals(actor.userId())) throw Failure.unauthorized();
        throw new Failure(404, "NOT_FOUND");
      }
    }
    if (p.community() != null) communities.guardScope(actor, p.community(), false, false);
    graph.guardInteractions(actor, ids.stream().filter(id -> !id.equals(actor.userId())).toList());
    store.lockPost(p.id(), false);
    if (c != null) store.lockComment(c.id(), false);
    var source = content.read(actor, p.id());
    if (c == null)
      return new Proof(
          source.communityId(),
          source.authorId(),
          source.authorId(),
          source.body(),
          source.version());
    var comment = readComment(actor, c.id());
    if (comment.tombstone()) throw new Failure(404, "NOT_FOUND");
    return new Proof(
        source.communityId(),
        comment.authorId(),
        source.authorId(),
        comment.body(),
        comment.version());
  }

  private void guard(ActorContext actor, Post p, List<UUID> authors, boolean staff) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var ids = new HashSet<>(authors);
    ids.add(p.author());
    ids.add(actor.userId());
    for (var id : ids.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
      var a = accounts.guard(id, false);
      if (id.equals(actor.userId()) && !a.active()) throw Failure.unauthorized();
    }
    if (p.community() != null) {
      if (staff) communities.guardScope(actor, p.community(), true, true);
      else communities.guardWrite(actor, p.community());
    }
    if (!staff)
      graph.guardInteractions(
          actor, ids.stream().filter(id -> !id.equals(actor.userId())).toList());
  }

  private void expected(long expected, long actual) {
    if (expected != actual) throw new Failure(412, "STALE_VERSION");
  }

  private Map<UUID, String> names(List<UUID> ids) {
    return profiles.attributionSummaries(ids.stream().distinct().toList()).stream()
        .collect(
            Collectors.toMap(
                ProfileQueries.Attribution::userId, ProfileQueries.Attribution::displayName));
  }

  private CommentView view(InteractionRepository.Row row, Map<UUID, String> names) {
    var c = row.comment();
    if (!row.visible())
      return new CommentView(
          c.id(), c.post(), null, null, null, null, null, true, c.created(), null, null);
    return new CommentView(
        c.id(),
        c.post(),
        c.root(),
        c.author(),
        names.get(c.author()),
        row.replyVisible() ? c.replyTo() : null,
        c.body(),
        false,
        c.created(),
        c.edited(),
        c.version());
  }

  private List<CommentView> views(List<InteractionRepository.Row> rows) {
    var eligible =
        rows.stream()
            .filter(r -> r.visible() || (r.comment().root() == null && r.children()))
            .toList();
    var names =
        names(
            eligible.stream()
                .filter(InteractionRepository.Row::visible)
                .map(r -> r.comment().author())
                .toList());
    return eligible.stream().map(r -> view(r, names)).toList();
  }

  @Transactional
  public CommentView create(
      ActorContext actor, UUID postId, UUID replyTo, String text, String key) {
    body(text);
    var p = post(postId);
    var target = replyTo == null ? null : existing(replyTo);
    if (target != null && !target.post().equals(postId)) throw new Failure(404, "NOT_FOUND");
    var root = target == null ? null : target.root() == null ? target : existing(target.root());
    guard(actor, p, target == null ? List.of() : List.of(target.author(), root.author()), false);
    store.lockPost(postId, false);
    if (target != null)
      for (var id :
          java.util.stream.Stream.of(target.id(), root.id())
              .distinct()
              .sorted(Comparator.comparing(UUID::toString))
              .toList()) store.lockComment(id, false);
    content.read(actor, postId);
    if (target != null) {
      if (readComment(actor, target.id()).tombstone() || readComment(actor, root.id()).tombstone())
        throw new Failure(404, "NOT_FOUND");
    }
    var fingerprint = Arrays.asList(postId, replyTo, text);
    UUID previous =
        keys.claim(
            actor.userId(),
            "POST /posts/" + postId + "/comments",
            key,
            json.writeValueAsString(fingerprint));
    if (previous != null) return readComment(actor, previous);
    var c =
        new Comment(
            UUID.randomUUID(),
            postId,
            actor.userId(),
            root == null ? null : root.id(),
            target == null ? null : target.author(),
            text,
            "VISIBLE",
            clock.instant(),
            null,
            null,
            null,
            null,
            0);
    store.insert(c);
    changed(actor, c, "CommentCreated");
    keys.complete(actor.userId(), "POST /posts/" + postId + "/comments", key, c.id());
    return readComment(actor, c.id());
  }

  @Transactional(readOnly = true)
  public CommentView readComment(ActorContext actor, UUID id) {
    var c = existing(id);
    content.read(actor, c.post());
    var rows =
        store.comments(
            viewer(actor), c.post(), c.root(), id, 1, clock.instant(), null, clock.instant());
    var views = views(rows);
    if (views.isEmpty()) throw new Failure(404, "NOT_FOUND");
    return views.getFirst();
  }

  public record Cursor(int version, Instant asOf, Instant expires, UUID last, Instant time) {}

  private Cursor cursor(String purpose, String after) {
    if (after == null)
      return new Cursor(1, clock.instant(), clock.instant().plusSeconds(1800), null, null);
    try {
      if (after.length() > 4096) throw new IllegalArgumentException();
      var c =
          json.readValue(
              secrets.decrypt(purpose, Base64.getUrlDecoder().decode(after)), Cursor.class);
      if (c.version() != 1
          || c.last() == null
          || c.time() == null
          || c.asOf() == null
          || !clock.instant().isBefore(c.expires())) throw new IllegalArgumentException();
      return c;
    } catch (RuntimeException e) {
      throw new Failure(400, "INVALID_CURSOR");
    }
  }

  private String encode(String purpose, Cursor c) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(secrets.encrypt(purpose, json.writeValueAsString(c)));
  }

  @Transactional(readOnly = true)
  public ContentQueries.Page<CommentView> comments(
      ActorContext actor, UUID postId, UUID rootId, int size, String after) {
    if (size < 1 || size > 100) throw Failure.invalid();
    content.read(actor, postId);
    if (rootId != null) {
      var root = existing(rootId);
      if (!root.post().equals(postId) || root.root() != null) throw new Failure(404, "NOT_FOUND");
      readComment(actor, rootId);
    }
    var purpose = "comment-cursor:v1:" + viewer(actor) + ":" + postId + ":" + rootId;
    var c = cursor(purpose, after);
    var rows =
        store.comments(
            viewer(actor),
            postId,
            rootId,
            null,
            size,
            c.asOf(),
            c.last() == null ? null : new InteractionRepository.Key(c.last(), c.time()),
            clock.instant());
    String next = null;
    if (rows.size() == size) {
      var last = rows.getLast().comment();
      next = encode(purpose, new Cursor(1, c.asOf(), c.expires(), last.id(), last.created()));
    }
    return new ContentQueries.Page<>(views(rows), next);
  }

  @Transactional
  public CommentView edit(ActorContext actor, UUID id, long version, String text) {
    body(text);
    var first = existing(id);
    var p = post(first.post());
    guard(actor, p, List.of(first.author()), false);
    store.lockPost(p.id(), false);
    var c = store.find(id, true);
    content.read(actor, p.id());
    if (!actor.userId().equals(c.author())) {
      readComment(actor, id);
      throw new Failure(403, "FORBIDDEN");
    }
    if (!c.status().equals("VISIBLE")) throw new Failure(404, "NOT_FOUND");
    expected(version, c.version());
    if (c.body().equals(text)) return readComment(actor, id);
    var next = c.edit(text, clock.instant());
    store.save(next, clock.instant());
    changed(actor, next, "CommentUpdated");
    return readComment(actor, id);
  }

  @Transactional
  public void delete(ActorContext actor, UUID id, long version) {
    var first = existing(id);
    var p = post(first.post());
    guard(actor, p, List.of(first.author()), false);
    store.lockPost(p.id(), false);
    var c = store.find(id, true);
    content.read(actor, p.id());
    if (!actor.userId().equals(c.author())) {
      readComment(actor, id);
      throw new Failure(403, "FORBIDDEN");
    }
    expected(version, c.version());
    if (c.deleted() != null) return;
    var next = c.delete(clock.instant());
    store.save(next, clock.instant());
    changed(actor, next, "CommentDeleted");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public PostModeration.Transition applyComment(
      ActorContext actor, UUID community, UUID id, long version, String action, UUID actionId) {
    var first = existing(id);
    var p = post(first.post());
    if (community == null || !community.equals(p.community())) throw new Failure(404, "NOT_FOUND");
    if (!Set.of("REMOVE_CONTENT", "RESTORE_CONTENT").contains(action)) throw Failure.invalid();
    guard(actor, p, List.of(first.author()), true);
    store.lockPost(p.id(), false);
    var c = store.find(id, true);
    expected(version, c.version());
    if (c.deleted() != null) throw new Failure(409, "AUTHOR_DELETED");
    if (!post(p.id()).status().equals("PUBLISHED")) throw new Failure(409, "PARENT_UNAVAILABLE");
    Comment next;
    try {
      next = c.moderate(action.equals("RESTORE_CONTENT"), actionId, clock.instant());
    } catch (IllegalStateException invalid) {
      throw Failure.conflict();
    }
    store.save(next, clock.instant());
    changed(actor, next, action.equals("RESTORE_CONTENT") ? "CommentRestored" : "CommentRemoved");
    return new PostModeration.Transition(c.author(), c.status(), next.status(), next.version());
  }

  private Post reactionGuard(ActorContext actor, UUID postId, UUID commentId) {
    if ((postId == null) == (commentId == null)) throw Failure.invalid();
    var c = commentId == null ? null : existing(commentId);
    var p = post(c == null ? postId : c.post());
    guard(actor, p, c == null ? List.of() : List.of(c.author()), false);
    store.lockPost(p.id(), c == null);
    if (c != null) store.lockComment(c.id(), true);
    content.read(actor, p.id());
    if (c != null && readComment(actor, c.id()).tombstone()) throw new Failure(404, "NOT_FOUND");
    return p;
  }

  @Transactional
  public ReactionView react(ActorContext actor, UUID post, UUID comment, ReactionType type) {
    if (type == null) throw Failure.invalid();
    reactionGuard(actor, post, comment);
    var previous = store.reaction(actor.userId(), post, comment);
    if (previous != null && previous.active() && previous.type() == type)
      return new ReactionView(previous.id(), type, previous.version());
    var next = store.setReaction(actor.userId(), post, comment, type, true, clock.instant());
    reactionChanged(actor, post, comment, previous, next);
    return new ReactionView(next.id(), type, next.version());
  }

  @Transactional
  public void unreact(ActorContext actor, UUID post, UUID comment) {
    reactionGuard(actor, post, comment);
    var previous = store.reaction(actor.userId(), post, comment);
    if (previous == null || !previous.active()) return;
    var next =
        store.setReaction(actor.userId(), post, comment, previous.type(), false, clock.instant());
    reactionChanged(actor, post, comment, previous, next);
  }

  private void reactionChanged(
      ActorContext actor,
      UUID post,
      UUID comment,
      InteractionRepository.Reaction before,
      InteractionRepository.Reaction after) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("reactionId", after.id());
    payload.put("actorId", actor.userId());
    payload.put("targetType", comment == null ? "POST" : "COMMENT");
    payload.put("targetId", comment == null ? post : comment);
    payload.put("previousType", before != null && before.active() ? before.type() : null);
    payload.put("type", after.active() ? after.type() : null);
    work.event(
        "ReactionChanged",
        "reaction",
        after.id(),
        after.version(),
        0,
        json.writeValueAsString(payload));
  }

  private void changed(ActorContext actor, Comment c, String type) {
    work.event(
        type,
        "comment",
        c.id(),
        c.version(),
        0,
        json.writeValueAsString(
            Map.of(
                "commentId",
                c.id(),
                "postId",
                c.post(),
                "authorId",
                c.author(),
                "status",
                c.status())));
    work.auditScoped(
        actor.userId(),
        type.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT),
        "comment",
        c.id(),
        post(c.post()).community(),
        "SUCCESS",
        null);
    if (c.deleted() != null)
      work.job(
          "COMMENT_RETENTION",
          c.id() + ":" + c.version(),
          c.id().toString(),
          c.deleted().plusSeconds(30L * 86400));
  }

  @Transactional(readOnly = true)
  public ReactionPage reactions(
      ActorContext actor, UUID post, UUID comment, int size, String after) {
    if (size < 1 || size > 100 || (post == null) == (comment == null)) throw Failure.invalid();
    if (comment == null) content.read(actor, post);
    else if (readComment(actor, comment).tombstone()) throw new Failure(404, "NOT_FOUND");
    var purpose = "reaction-cursor:v1:" + viewer(actor) + ":" + post + ":" + comment;
    var cursor = cursor(purpose, after);
    var rows = store.actors(viewer(actor), post, comment, size, cursor.last(), clock.instant());
    var names = names(rows.stream().map(InteractionRepository.Reaction::actor).toList());
    String next =
        rows.size() == size
            ? encode(
                purpose,
                new Cursor(
                    1, cursor.asOf(), cursor.expires(), rows.getLast().actor(), clock.instant()))
            : null;
    return new ReactionPage(
        store.counts(viewer(actor), post, comment, clock.instant()),
        rows.stream()
            .map(r -> new ActorReaction(r.actor(), names.get(r.actor()), r.type()))
            .toList(),
        next);
  }

  @Transactional(readOnly = true)
  public Map<UUID, Engagement> engagement(ActorContext actor, List<UUID> posts) {
    if (posts == null || posts.size() > 100 || posts.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
    return store.engagement(viewer(actor), posts.stream().distinct().toList(), clock.instant());
  }
}
