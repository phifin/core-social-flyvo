package com.flyvo.socialcore.account.infrastructure.persistence;

import com.flyvo.socialcore.account.application.port.AccountRepository;
import com.flyvo.socialcore.account.domain.UserAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaAccountRepository implements AccountRepository {
  private final EntityManager entities;

  JpaAccountRepository(EntityManagerFactory factory) {
    this.entities =
        org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
  }

  public UserAccount find(UUID id) {
    return entities.find(UserAccount.class, id);
  }

  public java.util.Set<UUID> activeIds(java.util.List<UUID> ids) {
    if (ids.isEmpty()) return java.util.Set.of();
    return new java.util.HashSet<>(
        entities
            .createQuery(
                "select a.id from UserAccount a where a.id in :ids and a.status='ACTIVE'",
                UUID.class)
            .setParameter("ids", ids)
            .getResultList());
  }

  public UserAccount guard(UUID id, boolean exclusive) {
    var mode = exclusive ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ;
    var value = entities.find(UserAccount.class, id, mode);
    if (value != null) entities.refresh(value, mode);
    return value;
  }

  public java.util.List<UserAccount> snapshots(java.util.List<UUID> ids) {
    if (ids.isEmpty()) return java.util.List.of();
    return entities
        .createQuery("select a from UserAccount a where a.id in :ids", UserAccount.class)
        .setParameter("ids", ids)
        .getResultList();
  }

  public void add(UserAccount account) {
    entities.persist(account);
  }

  public void flush() {
    entities.flush();
  }
}
