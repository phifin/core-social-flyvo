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

  JpaProfileRepository(EntityManagerFactory factory) {
    this.entities =
        org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
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
    var query =
        entities.createQuery(
            "select p.userId from Profile p "
                + (after == null ? "" : "where p.userId > :after ")
                + "order by p.userId",
            UUID.class);
    if (after != null) query.setParameter("after", after);
    return query.setMaxResults(limit).getResultList();
  }
}
