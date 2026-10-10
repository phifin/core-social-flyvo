package com.flyvo.socialcore.profile.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profile", schema = "profile")
public class Profile {
  @Id
  @Column(name = "user_id")
  private UUID userId;

  private String handle;

  @Column(name = "handle_normalized")
  private String handleNormalized;

  @Column(name = "display_name", length = 80)
  private String displayName;

  @Column(length = 1000)
  private String bio;

  private String visibility;

  @Column(name = "avatar_media_id")
  private UUID avatarMediaId;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version private long version;

  protected Profile() {}

  public Profile(UUID id, Instant now) {
    userId = id;
    handle = "user_" + id.toString().replace("-", "").substring(0, 24);
    handleNormalized = handle;
    displayName = handle;
    bio = "";
    visibility = "AUTHENTICATED";
    createdAt = now;
    updatedAt = now;
  }

  public UUID userId() {
    return userId;
  }

  public String handle() {
    return handle;
  }

  public String displayName() {
    return displayName;
  }

  public String bio() {
    return bio;
  }

  public String visibility() {
    return visibility;
  }

  public long version() {
    return version;
  }

  public UUID avatarMediaId() {
    return avatarMediaId;
  }

  public void avatar(UUID media, Instant now) {
    avatarMediaId = media;
    updatedAt = now;
  }

  public void change(String handle, String name, String bio, String visibility, Instant now) {
    if (handle == null
        || !handle.matches("[a-z0-9_]{3,30}")
        || name == null
        || name.isBlank()
        || name.length() > 80
        || bio == null
        || bio.length() > 1000
        || visibility == null
        || !java.util.Set.of("PUBLIC", "AUTHENTICATED", "FRIENDS", "PRIVATE").contains(visibility))
      throw new IllegalArgumentException("Invalid profile");
    this.handle = handle;
    handleNormalized = handle;
    displayName = name;
    this.bio = bio;
    this.visibility = visibility;
    updatedAt = now;
  }
}
