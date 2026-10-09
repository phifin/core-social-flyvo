package com.flyvo.socialcore.account.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account", schema = "account")
public class UserAccount {
  @Id private UUID id;

  @Column(nullable = false)
  private String status;

  @Column(name = "platform_role", nullable = false)
  private String platformRole;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deletion_requested_at")
  private Instant deletionRequestedAt;

  @Column(name = "suspended_until")
  private Instant suspendedUntil;

  @Version private long version;

  protected UserAccount() {}

  public UserAccount(UUID id, Instant now) {
    this.id = id;
    status = "ACTIVE";
    platformRole = "USER";
    createdAt = now;
    updatedAt = now;
  }

  public UUID id() {
    return id;
  }

  public String status() {
    return status;
  }

  public String platformRole() {
    return platformRole;
  }

  public long version() {
    return version;
  }

  public void requestDeletion(Instant now) {
    if (!"ACTIVE".equals(status)) throw new IllegalStateException("Account is not active");
    status = "DELETION_PENDING";
    deletionRequestedAt = now;
    updatedAt = now;
  }

  public void suspend(Instant now) {
    if (!"ACTIVE".equals(status)) throw new IllegalStateException("Account is not active");
    status = "SUSPENDED";
    updatedAt = now;
  }
}
