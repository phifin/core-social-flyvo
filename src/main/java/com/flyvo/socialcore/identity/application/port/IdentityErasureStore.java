package com.flyvo.socialcore.identity.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IdentityErasureStore {
  void start(UUID user);

  void captureLogin(UUID user);

  void eraseCredential(UUID user);

  List<UUID> tokens(UUID user);

  void eraseTokens(UUID user, List<UUID> ids);

  List<UUID> registrations(UUID user, Instant cut);

  void eraseRegistrations(UUID user, List<UUID> ids);

  boolean nextMailbox(UUID user, Instant cut);

  int eraseOAuth(UUID user);

  int eraseRefresh(UUID user);

  int eraseSessions(UUID user);

  void releaseClaims(UUID user);

  void eraseMailboxes(UUID user);

  boolean residualSecrets(UUID user);
}
