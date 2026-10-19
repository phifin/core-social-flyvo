package com.flyvo.socialcore.identity.application.port;

import com.flyvo.socialcore.identity.domain.IdentityState.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IdentityRepository {
  /** The same PostgreSQL wall-clock authority used by erasure retention, sampled after locks. */
  Instant databaseTime();

  UUID emailOwner(String email);

  String email(UUID user);

  void claimEmail(UUID user, String email, Instant now);

  void changeEmail(UUID user, String email, Instant now);

  boolean dispatch(byte[] key, String purpose, Instant now);

  Credential credential(UUID user);

  void credential(UUID user, String encoded, Instant now);

  void intent(
      UUID id,
      String email,
      byte[] hash,
      String flow,
      byte[] proof,
      byte[] browserHash,
      Instant expiresAt,
      Instant now);

  Intent intent(byte[] hash);

  void consumeIntent(UUID id, Instant now);

  Session session(UUID id, boolean lock);

  void createSession(
      UUID id, UUID user, Instant now, boolean reauthenticationRequired, long authenticationEpoch);

  void rotateSession(UUID id, Instant idle, Instant now);

  void reauthenticate(UUID id, Instant now);

  void revoke(UUID id, String reason, Instant now);

  void revokeAll(UUID user, UUID except, String reason, Instant now);

  List<Session> sessions(UUID user, Instant now, int limit, int offset);

  Refresh refresh(byte[] hash);

  void refresh(UUID id, UUID session, int generation, byte[] hash, Instant expiry, Instant now);

  void consumeRefresh(UUID id, UUID successor, Instant now);

  void oneTime(UUID id, UUID user, String purpose, byte[] hash, String target, Instant now);

  OneTime oneTime(byte[] hash);

  void consumeOneTime(UUID id, Instant now);

  void invalidateTokens(UUID user, String purpose, Instant now);

  External external(String provider, String issuer, String subject);

  List<External> identities(UUID user);

  void bind(UUID id, UUID user, ProviderProof proof, Instant now);

  void unlink(UUID id, UUID user);

  void oauth(OAuth value, byte[] state, Instant now);

  OAuth oauth(byte[] state);

  boolean consumeOAuth(UUID id, Instant now);

  void prune(Instant now);

  void onboarding(UUID id, byte[] hash, byte[] browser, byte[] proof, Instant expires);

  Onboarding onboarding(byte[] hash);

  boolean consumeOnboarding(UUID id, Instant now);
}
