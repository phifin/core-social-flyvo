package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.community.api.MembershipQueries;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy;
import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy.*;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.content.domain.post.Post;
import com.flyvo.socialcore.content.domain.post.PostAudience;
import com.flyvo.socialcore.content.domain.post.PostBody;
import com.flyvo.socialcore.graph.api.RelationshipQueries;
import com.flyvo.socialcore.media.api.*;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Posts implements ContentCommands, ContentQueries, PostModeration {
  private final PostRepository store;
  private final AccountQueries accounts;
  private final MembershipQueries communities;
  private final RelationshipQueries graph;
  private final ProfileQueries profiles;
  private final MediaCommands media;
  private final MediaQueries variants;
  private final Clock clock;
  private final DurableWork work;
  private final Idempotency keys;
  private final Secrets secrets;
  private final tools.jackson.databind.ObjectMapper json;

  public Posts(
      PostRepository store,
      AccountQueries accounts,
      MembershipQueries communities,
      RelationshipQueries graph,
      ProfileQueries profiles,
      MediaCommands media,
      MediaQueries variants,
      Clock clock,
      DurableWork work,
      Idempotency keys,
      Secrets secrets,
      tools.jackson.databind.ObjectMapper json) {
    this.store = store;
    this.accounts = accounts;
    this.communities = communities;
    this.graph = graph;
    this.profiles = profiles;
    this.media = media;
    this.variants = variants;
    this.clock = clock;
    this.work = work;
    this.keys = keys;
    this.secrets = secrets;
    this.json = json;
  }

  private PostAudience audience(UUID author, UUID community, Audience value) {
    try {
      if (value == null) throw new IllegalArgumentException();
      return new PostAudience(
          author,
          community,
          PostAudience.Type.valueOf(value.type()),
          value.recipientIds() == null ? List.of() : value.recipientIds());
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw Failure.invalid();
    }
  }

  private PostBody body(String text, String link, List<Mention> mentions) {
    try {
      return new PostBody(
          text,
          link,
          mentions == null
              ? List.of()
              : mentions.stream()
                  .map(m -> new PostBody.Mention(m.userId(), m.start(), m.end()))
                  .toList());
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw Failure.invalid();
    }
  }

  private List<Post.Attachment> attachments(List<Attachment> values) {
    try {
      return values == null
          ? List.of()
          : values.stream().map(v -> new Post.Attachment(v.mediaId(), v.altText())).toList();
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw Failure.invalid();
    }
  }

  private void require(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var a = accounts.find(actor.userId());
    if (a == null || !a.active()) throw Failure.unauthorized();
  }

  private void guard(ActorContext actor, List<UUID> users) {
    require(actor);
    var all = new HashSet<>(users);
    all.add(actor.userId());
    for (var id : all.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
      var a = accounts.guard(id, false);
      if (id.equals(actor.userId()) && !a.active()) throw Failure.unauthorized();
    }
  }

  private List<UUID> references(PostAudience audience, PostBody body) {
    return java.util.stream.Stream.concat(
            audience.recipients().stream(), body.mentions().stream().map(PostBody.Mention::userId))
        .distinct()
        .toList();
  }

  private void validateParticipants(List<UUID> users) {
    for (int start = 0; start < users.size(); start += 100) {
      var batch = users.subList(start, Math.min(start + 100, users.size()));
      if (accounts.activeIds(batch).size() != batch.size())
        throw new Failure(400, "INVALID_PARTICIPANT");
    }
  }

  @Transactional
  public ContentQueries.PostView publish(ActorContext actor, Publish request, String key) {
    require(actor);
    if (request == null) throw Failure.invalid();
    var a = audience(actor.userId(), request.communityId(), request.audience());
    var b = body(request.body(), request.linkUrl(), request.mentions());
    com.flyvo.socialcore.content.domain.post.Post p;
    try {
      p =
          new com.flyvo.socialcore.content.domain.post.Post(
              UUID.randomUUID(),
              actor.userId(),
              request.communityId(),
              b,
              a,
              attachments(request.media()),
              "PUBLISHED",
              1,
              clock.instant(),
              null,
              null,
              null,
              null,
              0);
    } catch (IllegalArgumentException invalid) {
      throw Failure.invalid();
    }
    var refs = references(a, b);
    guard(actor, refs);
    if (p.community() != null) communities.guardWrite(actor, p.community());
    graph.guardInteractions(actor, refs);
    validateParticipants(refs);
    UUID previous =
        keys.claim(actor.userId(), "POST /posts", key, json.writeValueAsString(request));
    if (previous != null) return read(actor, previous);
    media.bind(
        actor.userId(), "POST", p.id(), p.media().stream().map(Post.Attachment::id).toList());
    store.create(p);
    store.revision(p, actor.userId(), clock.instant());
    changed(actor, p, "PostCreated");
    keys.complete(actor.userId(), "POST /posts", key, p.id());
    return representation(
        actor, p, profiles.attributionSummaries(List.of(actor.userId())).getFirst().displayName());
  }

  @Transactional
  public ContentQueries.PostView edit(ActorContext actor, UUID id, long expected, Edit request) {
    require(actor);
    var initial = existing(id);
    var b = body(request.body(), request.linkUrl(), request.mentions());
    var refs = references(initial.audience(), b);
    guard(
        actor,
        java.util.stream.Stream.concat(refs.stream(), java.util.stream.Stream.of(initial.author()))
            .toList());
    if (initial.community() != null) communities.guardWrite(actor, initial.community());
    graph.guardInteractions(actor, refs);
    var p = store.find(id, true);
    visible(actor, p);
    owner(actor, p);
    version(expected, p.version());
    validateParticipants(refs);
    com.flyvo.socialcore.content.domain.post.Post next;
    try {
      next = p.edited(b, attachments(request.media()), clock.instant());
    } catch (IllegalArgumentException invalid) {
      throw Failure.invalid();
    }
    var old = p.media().stream().map(Post.Attachment::id).toList();
    var newer = next.media().stream().map(Post.Attachment::id).toList();
    media.bind(actor.userId(), "POST", id, newer.stream().filter(m -> !old.contains(m)).toList());
    media.release(
        actor.userId(),
        "POST",
        id,
        old.stream().filter(m -> !newer.contains(m)).toList(),
        clock.instant().plusSeconds(30L * 86400));
    store.save(next);
    store.revision(next, actor.userId(), clock.instant());
    changed(actor, next, "PostUpdated");
    return read(actor, id);
  }

  @Transactional
  public ContentQueries.PostView audience(
      ActorContext actor, UUID id, long expected, Audience request) {
    require(actor);
    var initial = existing(id);
    var a = audience(initial.author(), initial.community(), request);
    var refs = references(a, initial.body());
    guard(
        actor,
        java.util.stream.Stream.concat(refs.stream(), java.util.stream.Stream.of(initial.author()))
            .toList());
    if (initial.community() != null) communities.guardWrite(actor, initial.community());
    graph.guardInteractions(actor, refs);
    var p = store.find(id, true);
    visible(actor, p);
    owner(actor, p);
    version(expected, p.version());
    validateParticipants(refs);
    if (p.audience().equals(a)) return read(actor, id);
    var next = p.audience(a, clock.instant());
    store.save(next);
    store.revision(next, actor.userId(), clock.instant());
    // All explicit audience changes are audited, including widening incomparable sets.
    changed(actor, next, "PostAudienceChanged");
    return read(actor, id);
  }

  @Transactional
  public void delete(ActorContext actor, UUID id, long expected) {
    var first = existing(id);
    guard(actor, List.of(first.author()));
    if (first.community() != null) communities.guardScope(actor, first.community(), false, true);
    var p = store.find(id, true);
    if (!p.author().equals(actor.userId())) visible(actor, p);
    owner(actor, p);
    version(expected, p.version());
    if (p.deleted() != null) return;
    var next = p.deleted(clock.instant());
    store.save(next);
    media.release(
        p.author(),
        "POST",
        id,
        p.media().stream().map(Post.Attachment::id).toList(),
        clock.instant().plusSeconds(30L * 86400));
    changed(actor, next, "PostDeleted");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Transition apply(
      ActorContext actor, UUID community, UUID id, long expected, String action, UUID actionId) {
    var first = existing(id);
    guard(actor, List.of(first.author()));
    if (!Objects.equals(community, first.community()) || community == null)
      throw new Failure(404, "NOT_FOUND");
    communities.guardScope(actor, community, true, true);
    var p = store.find(id, true);
    version(expected, p.version());
    if (!Set.of("REMOVE_CONTENT", "RESTORE_CONTENT").contains(action)) throw Failure.invalid();
    if (p.deleted() != null) throw new Failure(409, "AUTHOR_DELETED");
    com.flyvo.socialcore.content.domain.post.Post next;
    try {
      next = p.moderated(action.equals("RESTORE_CONTENT"), actionId, clock.instant());
    } catch (IllegalArgumentException invalid) {
      throw Failure.conflict();
    }
    store.save(next);
    changed(actor, next, action.equals("REMOVE_CONTENT") ? "PostRemoved" : "PostRestored");
    return new Transition(p.author(), p.status(), next.status(), next.version());
  }

  @Transactional(readOnly = true)
  public ContentQueries.PostView read(ActorContext actor, UUID id) {
    var p = existing(id);
    visible(actor, p);
    return representation(
        actor, p, profiles.attributionSummaries(List.of(p.author())).getFirst().displayName());
  }

  @Transactional(readOnly = true)
  public List<ContentQueries.PostView> hydrate(ActorContext actor, List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
    UUID viewer = viewer(actor);
    var posts = store.load(ids);
    var facts = facts(viewer, posts);
    var eligible =
        posts.stream()
            .filter(
                p ->
                    ContentAccessPolicy.canRead(
                        viewer, postFacts(p), facts.get(p.id()), clock.instant()))
            .toList();
    var names =
        profiles
            .attributionSummaries(eligible.stream().map(Post::author).distinct().toList())
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ProfileQueries.Attribution::userId, ProfileQueries.Attribution::displayName));
    var mapped =
        eligible.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Post::id, p -> representation(actor, p, names.get(p.author()))));
    return ids.stream().distinct().filter(mapped::containsKey).map(mapped::get).toList();
  }

  private void feedScope(FeedScope scope) {
    if (scope == null
        || scope.mode() == null
        || (scope.mode() == FeedMode.HOME) != (scope.id() == null)) throw Failure.invalid();
  }

  @Transactional(readOnly = true)
  public void authorizeFeed(ActorContext actor, FeedScope scope) {
    feedScope(scope);
    UUID viewer = viewer(actor);
    if (scope.mode() == FeedMode.HOME) {
      if (viewer == null) throw Failure.unauthorized();
      return;
    }
    if (scope.mode() == FeedMode.TIMELINE) {
      var a = accounts.find(scope.id());
      if (a == null
          || !a.active()
          || viewer != null
              && !viewer.equals(scope.id())
              && graph.facts(viewer, List.of(scope.id())).getFirst().blocked())
        throw new Failure(404, "NOT_FOUND");
      return;
    }
    var cs = communities.scopes(List.of(scope.id()));
    if (cs.isEmpty()) throw new Failure(404, "NOT_FOUND");
    var c = cs.getFirst();
    var ms =
        viewer == null
            ? List.<MembershipQueries.Facts>of()
            : communities.facts(viewer, List.of(scope.id()));
    var m = ms.isEmpty() ? null : ms.getFirst();
    var f =
        new Facts(
            true,
            viewer != null,
            false,
            false,
            false,
            false,
            false,
            c.visibility(),
            c.status(),
            m == null ? null : m.status(),
            m == null ? null : m.role(),
            m == null ? null : m.suspendedUntil());
    if (!ContentAccessPolicy.canRead(
        viewer,
        new PostFacts(c.ownerId(), c.communityId(), "COMMUNITY", "PUBLISHED"),
        f,
        clock.instant())) throw new Failure(404, "NOT_FOUND");
  }

  @Transactional(readOnly = true)
  public Instant feedCutoff() {
    return store.databaseTime();
  }

  @Transactional(readOnly = true)
  public List<Candidate> scanCandidates(
      ActorContext actor, FeedScope scope, int limit, Instant asOf, Candidate after) {
    authorizeFeed(actor, scope);
    if (limit < 1 || limit > 100 || asOf == null) throw Failure.invalid();
    return store
        .feedCandidates(
            viewer(actor),
            scope,
            limit,
            asOf,
            after == null ? null : new PostRepository.Key(after.id(), after.publishedAt()),
            clock.instant())
        .stream()
        .map(k -> new Candidate(k.id(), k.published()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<PostView> hydrateAuthorized(ActorContext actor, FeedScope scope, List<UUID> ids) {
    authorizeFeed(actor, scope);
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
    UUID viewer = viewer(actor);
    var source = store.feedLoad(viewer, scope, ids, clock.instant());
    // Body, relations, source eligibility and privacy share the source statement;
    // subsequent owner checks can only remove, never load a newer body.
    var current = facts(viewer, source);
    var eligible =
        source.stream()
            .filter(
                p ->
                    ContentAccessPolicy.canRead(
                        viewer, postFacts(p), current.get(p.id()), clock.instant()))
            .toList();
    var names =
        profiles
            .attributionSummaries(eligible.stream().map(Post::author).distinct().toList())
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ProfileQueries.Attribution::userId, ProfileQueries.Attribution::displayName));
    var mapped =
        eligible.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Post::id, p -> representation(actor, p, names.get(p.author()))));
    return ids.stream().distinct().filter(mapped::containsKey).map(mapped::get).toList();
  }

  private UUID viewer(ActorContext actor) {
    if (actor != null && actor.authenticated()) {
      require(actor);
      return actor.userId();
    }
    return null;
  }

  private Map<UUID, Facts> facts(
      UUID viewer, List<com.flyvo.socialcore.content.domain.post.Post> posts) {
    var authors = posts.stream().map(Post::author).distinct().toList();
    var active = accounts.activeIds(authors);
    var relations =
        viewer == null
            ? Map.<UUID, RelationshipQueries.Facts>of()
            : graph.facts(viewer, authors).stream()
                .collect(
                    java.util.stream.Collectors.toMap(RelationshipQueries.Facts::userId, f -> f));
    var scopes = posts.stream().map(Post::community).filter(Objects::nonNull).distinct().toList();
    var cs =
        communities.scopes(scopes).stream()
            .collect(
                java.util.stream.Collectors.toMap(MembershipQueries.Scope::communityId, c -> c));
    var ms =
        viewer == null
            ? Map.<UUID, MembershipQueries.Facts>of()
            : communities.facts(viewer, scopes).stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        MembershipQueries.Facts::communityId, m -> m));
    var result = new HashMap<UUID, Facts>();
    for (var p : posts) {
      var r = relations.get(p.author());
      var c = p.community() == null ? null : cs.get(p.community());
      var m = p.community() == null ? null : ms.get(p.community());
      result.put(
          p.id(),
          new Facts(
              active.contains(p.author()),
              viewer != null,
              r != null && r.blocked(),
              r != null && r.following(),
              r != null && r.friends(),
              viewer != null && p.audience().recipients().contains(viewer),
              false,
              c == null ? null : c.visibility(),
              c == null ? null : c.status(),
              m == null ? null : m.status(),
              m == null ? null : m.role(),
              m == null ? null : m.suspendedUntil()));
    }
    return result;
  }

  private void visible(ActorContext actor, com.flyvo.socialcore.content.domain.post.Post p) {
    UUID viewer = viewer(actor);
    if (!ContentAccessPolicy.canRead(
        viewer, postFacts(p), facts(viewer, List.of(p)).get(p.id()), clock.instant()))
      throw new Failure(404, "NOT_FOUND");
  }

  private PostFacts postFacts(com.flyvo.socialcore.content.domain.post.Post p) {
    return new PostFacts(p.author(), p.community(), p.audience().type().name(), p.status());
  }

  private com.flyvo.socialcore.content.domain.post.Post existing(UUID id) {
    var p = store.find(id, false);
    if (p == null) throw new Failure(404, "NOT_FOUND");
    return p;
  }

  private void owner(ActorContext actor, com.flyvo.socialcore.content.domain.post.Post p) {
    if (!p.author().equals(actor.userId())) throw new Failure(403, "FORBIDDEN");
  }

  private void version(long expected, long current) {
    if (expected != current) throw new Failure(412, "STALE_VERSION");
  }

  private ContentQueries.PostView representation(
      ActorContext actor, com.flyvo.socialcore.content.domain.post.Post p, String name) {
    return new ContentQueries.PostView(
        p.id(),
        p.author(),
        name,
        p.community(),
        p.body().text(),
        p.body().linkUrl(),
        p.audience().type().name(),
        actor != null && p.author().equals(actor.userId()) ? p.audience().recipients() : List.of(),
        p.body().mentions().stream().map(m -> new Mention(m.userId(), m.start(), m.end())).toList(),
        p.body().hashtags().stream().map(PostBody.Hashtag::display).toList(),
        p.media().stream().map(m -> new Attachment(m.id(), m.altText())).toList(),
        p.published(),
        p.edited(),
        p.revision(),
        p.version());
  }

  @Transactional
  public Page<Revision> revisions(ActorContext actor, UUID id, int size, int before) {
    require(actor);
    if (size < 1 || size > 100 || before < 1) throw Failure.invalid();
    var p = existing(id);
    var f = facts(actor.userId(), List.of(p)).get(id);
    if (!ContentAccessPolicy.canReadHistory(actor.userId(), postFacts(p), f, clock.instant()))
      throw new Failure(404, "NOT_FOUND");
    if (!p.author().equals(actor.userId()))
      work.auditScoped(
          actor.userId(), "POST_HISTORY_READ", "post", id, p.community(), "SUCCESS", null);
    // The history statement can see later commits, but must never return a body
    // created after the coherent post version we just authorized.
    var rows =
        store.revisions(
            id,
            size,
            Math.min(before, p.revision() + 1),
            clock.instant().minusSeconds(30L * 86400));
    return new Page<>(rows, rows.size() == size ? Integer.toString(rows.getLast().number()) : null);
  }

  @Transactional
  public List<MediaQueries.Variant> mediaAccess(
      ActorContext actor, UUID id, UUID mediaId, Integer revision) {
    var p = existing(id);
    visible(actor, p);
    boolean history = revision != null;
    if (history) {
      var rows = revisions(actor, id, 1, revision + 1).items();
      if (rows.isEmpty()
          || rows.getFirst().number() != revision
          || rows.getFirst().media().stream().noneMatch(m -> m.mediaId().equals(mediaId)))
        throw new Failure(404, "NOT_FOUND");
    } else if (p.media().stream().noneMatch(m -> m.id().equals(mediaId)))
      throw new Failure(404, "NOT_FOUND");
    return variants.access("POST", id, mediaId, history);
  }

  public record Cursor(int version, Instant asOf, Instant expires, UUID last, Instant published) {}

  @Transactional(readOnly = true)
  public Page<ContentQueries.PostView> posts(
      ActorContext actor, UUID author, UUID community, int size, String after) {
    if (size < 1 || size > 100 || author != null && community != null) throw Failure.invalid();
    UUID viewer = viewer(actor);
    String purpose = "post-cursor:v1:" + viewer + ":" + author + ":" + community;
    Cursor cursor = new Cursor(1, clock.instant(), clock.instant().plusSeconds(900), null, null);
    if (after != null) {
      try {
        if (after.length() > 4096) throw new IllegalArgumentException();
        cursor =
            json.readValue(
                secrets.decrypt(purpose, Base64.getUrlDecoder().decode(after)), Cursor.class);
        if (cursor.version() != 1
            || !clock.instant().isBefore(cursor.expires())
            || cursor.last() == null
            || cursor.published() == null) throw new IllegalArgumentException();
      } catch (RuntimeException invalid) {
        throw new Failure(400, "INVALID_CURSOR");
      }
    }
    var candidates =
        store.candidates(
            viewer,
            author,
            community,
            size,
            cursor.asOf(),
            cursor.last() == null
                ? null
                : new PostRepository.Key(cursor.last(), cursor.published()),
            clock.instant());
    var items = hydrate(actor, candidates.stream().map(PostRepository.Key::id).toList());
    String next = null;
    if (candidates.size() == size) {
      var last = candidates.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  secrets.encrypt(
                      purpose,
                      json.writeValueAsString(
                          new Cursor(
                              1, cursor.asOf(), cursor.expires(), last.id(), last.published()))));
    }
    return new Page<>(items, next);
  }

  private void changed(
      ActorContext actor, com.flyvo.socialcore.content.domain.post.Post p, String type) {
    work.event(
        type,
        "post",
        p.id(),
        p.version(),
        0,
        json.writeValueAsString(
            Map.of(
                "postId",
                p.id(),
                "authorId",
                p.author(),
                "revision",
                p.revision(),
                "status",
                p.status())));
    work.auditScoped(
        actor.userId(),
        type.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT),
        "post",
        p.id(),
        p.community(),
        "SUCCESS",
        null);
    work.job(
        "POST_RETENTION",
        p.id() + ":" + p.version(),
        p.id().toString(),
        clock.instant().plusSeconds(30L * 86400));
    work.job(
        "POST_BINDING_RECONCILE", p.id() + ":" + p.version(), p.id().toString(), clock.instant());
  }
}
