package com.flyvo.socialcore.profile.web;

import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.profile.api.ProfileQueries;
import com.flyvo.socialcore.profile.application.Profiles;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ProfileController {
  private final Profiles profiles;

  ProfileController(Profiles profiles) {
    this.profiles = profiles;
  }

  public record Change(String handle, String displayName, String bio, String visibility) {}

  public record PreferenceChange(String locale, String timeZone, String defaultPostAudience) {}

  @GetMapping("/users/{id}/profile")
  ResponseEntity<ProfileQueries.Summary> visible(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID id) {
    var value = profiles.visible(actor == null ? ActorContext.anonymous() : actor, id);
    return ResponseEntity.ok().eTag("\"" + value.version() + "\"").body(value);
  }

  @GetMapping("/me/profile")
  ResponseEntity<ProfileQueries.Summary> own(@AuthenticationPrincipal ActorContext actor) {
    return visible(actor, actor.userId());
  }

  @PatchMapping("/me/profile")
  ResponseEntity<ProfileQueries.Summary> change(
      @AuthenticationPrincipal ActorContext actor,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Change request) {
    var value =
        profiles.change(
            actor,
            version(match),
            request.handle(),
            request.displayName(),
            request.bio(),
            request.visibility());
    return ResponseEntity.ok().eTag("\"" + value.version() + "\"").body(value);
  }

  @GetMapping("/me/preferences")
  ResponseEntity<Profiles.PreferenceInfo> preferences(@AuthenticationPrincipal ActorContext actor) {
    var value = profiles.preferences(actor);
    return ResponseEntity.ok().eTag("\"" + value.version() + "\"").body(value);
  }

  @PatchMapping("/me/preferences")
  ResponseEntity<Profiles.PreferenceInfo> preferences(
      @AuthenticationPrincipal ActorContext actor,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody PreferenceChange request) {
    var value =
        profiles.preferences(
            actor,
            version(match),
            request.locale(),
            request.timeZone(),
            request.defaultPostAudience());
    return ResponseEntity.ok().eTag("\"" + value.version() + "\"").body(value);
  }

  private long version(String match) {
    return com.flyvo.socialcore.platform.api.ResourceVersions.require(match);
  }
}
