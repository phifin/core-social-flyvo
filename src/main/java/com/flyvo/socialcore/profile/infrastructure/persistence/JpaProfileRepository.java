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
    return lock
        ? entities.find(Profile.class, user, LockModeType.PESSIMISTIC_WRITE)
        : entities.find(Profile.class, user);
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
}
