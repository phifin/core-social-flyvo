package com.flyvo.socialcore.profile.infrastructure.persistence;

import com.flyvo.socialcore.profile.application.port.ProfileRepository;
import com.flyvo.socialcore.profile.domain.Preferences;
import com.flyvo.socialcore.profile.domain.Profile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaProfileRepository implements ProfileRepository {
  private final EntityManager entities;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  JpaProfileRepository(
      EntityManagerFactory factory, org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.entities =
        org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
    this.jdbc = jdbc;
  }

  public Profile find(UUID user, boolean lock) {
    if (!lock) return entities.find(Profile.class, user);
    var profile = entities.find(Profile.class, user);
    // A caller's transaction may already contain an earlier unlocked read.
    // Refresh acquires the lock and reads current state; locking an already
    // managed old version first would raise an optimistic conflict before reload.
    if (profile != null) entities.refresh(profile, LockModeType.PESSIMISTIC_WRITE);
    return profile;
  }

  public Preferences preferences(UUID user, boolean lock) {
    return lock
        ? entities.find(Preferences.class, user, LockModeType.PESSIMISTIC_WRITE)
        : entities.find(Preferences.class, user);
  }

  public void add(Profile profile, Preferences preferences) {
    entities.persist(profile);
    entities.persist(preferences);
  }

  public void flush() {
    entities.flush();
  }

  public java.util.List<Profile> summaries(java.util.List<UUID> users) {
    if (users.isEmpty()) return java.util.List.of();
    return entities
        .createQuery("SELECT p FROM Profile p WHERE p.userId IN :users", Profile.class)
        .setParameter("users", users)
        .getResultList();
  }

  public java.util.List<UUID> scan(UUID after, int limit) {
    return jdbc.query(
        "SELECT user_id FROM (SELECT user_id FROM profile.profile UNION SELECT user_id FROM profile.erasure_tombstone) p WHERE (?::uuid IS NULL OR user_id>?) ORDER BY user_id LIMIT ?",
        (r, n) -> r.getObject(1, UUID.class),
        after,
        after,
        limit);
  }

  public java.util.Map<UUID, Long> tombstones(java.util.List<UUID> ids) {
    if (ids.isEmpty()) return java.util.Map.of();
    var result = new java.util.HashMap<UUID, Long>();
    jdbc.query(
        "SELECT user_id,source_version FROM profile.erasure_tombstone WHERE user_id=ANY(?)",
        r -> {
          result.put(r.getObject(1, UUID.class), r.getLong(2));
        },
        (Object) ids.toArray(UUID[]::new));
    return java.util.Map.copyOf(result);
  }
}
