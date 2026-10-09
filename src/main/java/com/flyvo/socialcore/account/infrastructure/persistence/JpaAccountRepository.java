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

  public UserAccount guard(UUID id, boolean exclusive) {
    var mode = exclusive ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ;
    var value = entities.find(UserAccount.class, id, mode);
    if (value != null) entities.refresh(value, mode);
    return value;
  }

  public void add(UserAccount account) {
    entities.persist(account);
  }

  public void flush() {
    entities.flush();
  }
}
