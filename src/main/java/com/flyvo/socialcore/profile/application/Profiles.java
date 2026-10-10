package com.flyvo.socialcore.profile.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.profile.api.ProfileCommands;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import com.flyvo.socialcore.profile.application.port.ProfileRepository;
import com.flyvo.socialcore.profile.domain.Preferences;
import com.flyvo.socialcore.profile.domain.Profile;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Profiles implements ProfileCommands, ProfileQueries {
  private final ProfileRepository entities;
  private final AccountQueries accounts;
  private final Clock clock;
  private final DurableWork work;
  private final tools.jackson.databind.ObjectMapper json;
  private final com.flyvo.socialcore.graph.api.RelationshipQueries graph;
  private final com.flyvo.socialcore.media.api.MediaCommands media;
  private final com.flyvo.socialcore.media.api.MediaQueries variants;

  public Profiles(
      ProfileRepository entities,
      AccountQueries accounts,
      Clock clock,
      DurableWork work,
      tools.jackson.databind.ObjectMapper json,
      com.flyvo.socialcore.graph.api.RelationshipQueries graph,
      com.flyvo.socialcore.media.api.MediaCommands media,
      com.flyvo.socialcore.media.api.MediaQueries variants) {
    this.entities = entities;
    this.accounts = accounts;
    this.clock = clock;
    this.work = work;
    this.json = json;
    this.graph = graph;
    this.media = media;
    this.variants = variants;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void provision(UUID id) {
    entities.add(new Profile(id, clock.instant()), new Preferences(id, clock.instant()));
    entities.flush();
  }

  @Transactional(readOnly = true)
  public Summary visible(ActorContext actor, UUID id) {
    var account = accounts.find(id);
    var profile = entities.find(id, false);
    if (account == null || !account.active() || profile == null)
      throw new Failure(404, "NOT_FOUND");
    if (actor.authenticated()) requireActor(actor);
    boolean own = id.equals(actor.userId());
    var facts =
        actor.authenticated() && !own
            ? graph.facts(actor.userId(), java.util.List.of(id)).getFirst()
            : null;
    if (facts != null && facts.blocked()) throw new Failure(404, "NOT_FOUND");
    if (!own
        && !("PUBLIC".equals(profile.visibility())
            || actor.authenticated() && "AUTHENTICATED".equals(profile.visibility())
            || facts != null && facts.friends() && "FRIENDS".equals(profile.visibility())))
      throw new Failure(404, "NOT_FOUND");
    return summary(profile);
  }

  @Transactional(readOnly = true)
  public com.flyvo.socialcore.graph.api.RelationshipQueries.Page<UUID> graph(
      ActorContext actor, UUID id, String kind, int size, String after) {
    visible(actor, id);
    return graph.visibleEdges(actor, id, kind, size, after);
  }

  @Transactional
  public Summary change(
      ActorContext actor,
      long expected,
      String handle,
      String name,
      String bio,
      String visibility) {
    requireActor(actor);
    if (!accounts.guard(actor.userId(), false).active()) throw Failure.unauthorized();
    var value = entities.find(actor.userId(), true);
    if (value.version() != expected) throw new Failure(412, "STALE_VERSION");
    try {
      value.change(handle, name, bio, visibility, clock.instant());
    } catch (IllegalArgumentException invalid) {
      throw Failure.invalid();
    }
    entities.flush();
    work.event(
        "ProfileUpdated",
        "profile",
        value.userId(),
        value.version(),
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.profile.api.event.ProfileUpdated(value.userId())));
    return summary(value);
  }

  private Summary summary(Profile p) {
    return new Summary(
        p.userId(),
        p.handle(),
        p.displayName(),
        p.bio(),
        p.visibility(),
        p.version(),
        p.avatarMediaId());
  }

  @Transactional
  public Summary avatar(ActorContext actor, long expected, UUID mediaId) {
    requireActor(actor);
    if (!accounts.guard(actor.userId(), false).active()) throw Failure.unauthorized();
    var p = entities.find(actor.userId(), true);
    if (p.version() != expected) throw new Failure(412, "STALE_VERSION");
    if (java.util.Objects.equals(p.avatarMediaId(), mediaId)) return summary(p);
    if (mediaId != null)
      media.bind(actor.userId(), "AVATAR", actor.userId(), java.util.List.of(mediaId));
    if (p.avatarMediaId() != null)
      media.release(
          actor.userId(),
          "AVATAR",
          actor.userId(),
          java.util.List.of(p.avatarMediaId()),
          clock.instant());
    p.avatar(mediaId, clock.instant());
    entities.flush();
    work.job(
        "AVATAR_BINDING_RECONCILE",
        p.userId() + ":" + p.version(),
        p.userId().toString(),
        clock.instant());
    work.event(
        "ProfileUpdated",
        "profile",
        p.userId(),
        p.version(),
        0,
        json.writeValueAsString(
            new com.flyvo.socialcore.profile.api.event.ProfileUpdated(p.userId())));
    return summary(p);
  }

  @Transactional(readOnly = true)
  public java.util.List<com.flyvo.socialcore.media.api.MediaQueries.Variant> avatarAccess(
      ActorContext actor, UUID id) {
    var profile = visible(actor, id);
    if (profile.avatarMediaId() == null) throw new Failure(404, "NOT_FOUND");
    return variants.access("AVATAR", id, profile.avatarMediaId(), false);
  }

  @Transactional(readOnly = true)
  public java.util.List<Attribution> attributionSummaries(java.util.List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull))
      throw Failure.invalid();
    return entities.summaries(ids).stream()
        .map(p -> new Attribution(p.userId(), p.displayName()))
        .toList();
  }

  public record PreferenceInfo(
      String locale, String timeZone, String defaultPostAudience, long version) {}

  @Transactional(readOnly = true)
  public PreferenceInfo preferences(ActorContext actor) {
    requireActor(actor);
    return preference(entities.preferences(actor.userId(), false));
  }

  @Transactional
  public PreferenceInfo preferences(
      ActorContext actor, long expected, String locale, String zone, String audience) {
    requireActor(actor);
    if (!accounts.guard(actor.userId(), false).active()) throw Failure.unauthorized();
    var value = entities.preferences(actor.userId(), true);
    if (value.version() != expected) throw new Failure(412, "STALE_VERSION");
    try {
      value.change(locale, zone, audience, clock.instant());
    } catch (IllegalArgumentException e) {
      throw Failure.invalid();
    }
    entities.flush();
    return preference(value);
  }

  private PreferenceInfo preference(Preferences value) {
    return new PreferenceInfo(
        value.locale(), value.timeZone(), value.defaultPostAudience(), value.version());
  }

  private void requireActor(ActorContext actor) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var account = accounts.find(actor.userId());
    if (account == null || !account.active()) throw Failure.unauthorized();
  }
}
