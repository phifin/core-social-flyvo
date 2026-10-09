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

  public Profiles(
      ProfileRepository entities,
      AccountQueries accounts,
      Clock clock,
      DurableWork work,
      tools.jackson.databind.ObjectMapper json) {
    this.entities = entities;
    this.accounts = accounts;
    this.clock = clock;
    this.work = work;
    this.json = json;
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
    if (!own
        && !("PUBLIC".equals(profile.visibility())
            || actor.authenticated() && "AUTHENTICATED".equals(profile.visibility())))
      throw new Failure(404, "NOT_FOUND");
    return summary(profile);
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
        p.userId(), p.handle(), p.displayName(), p.bio(), p.visibility(), p.version());
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
