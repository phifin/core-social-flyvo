package com.flyvo.socialcore.profile.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "preference", schema = "profile")
public class Preferences {
  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(length = 35)
  private String locale;

  @Column(name = "time_zone", length = 64)
  private String timeZone;

  @Column(name = "default_post_audience")
  private String defaultPostAudience;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version private long version;

  protected Preferences() {}

  public Preferences(UUID id, Instant now) {
    userId = id;
    locale = "en";
    timeZone = "UTC";
    defaultPostAudience = "FRIENDS";
    updatedAt = now;
  }

  public void change(String locale, String zone, String audience, Instant now) {
    if (locale == null
        || locale.length() > 35
        || !locale.matches("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*")
        || Locale.forLanguageTag(locale).getLanguage().isEmpty()
        || zone == null
        || zone.length() > 64
        || audience == null
        || !java.util.Set.of("PUBLIC", "FOLLOWERS", "FRIENDS", "ONLY_ME").contains(audience))
      throw new IllegalArgumentException("Invalid preferences");
    try {
      ZoneId.of(zone);
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid timezone");
    }
    this.locale = locale;
    timeZone = zone;
    defaultPostAudience = audience;
    updatedAt = now;
  }

  public String locale() {
    return locale;
  }

  public String timeZone() {
    return timeZone;
  }

  public String defaultPostAudience() {
    return defaultPostAudience;
  }

  public long version() {
    return version;
  }
}
