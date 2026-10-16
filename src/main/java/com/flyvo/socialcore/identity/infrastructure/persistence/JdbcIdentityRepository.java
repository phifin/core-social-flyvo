package com.flyvo.socialcore.identity.infrastructure.persistence;

import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import com.flyvo.socialcore.identity.domain.IdentityState.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityRepository implements IdentityRepository {
  private final JdbcTemplate jdbc;

  public JdbcIdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime t(Instant now) {
    return now.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private <T> T first(String sql, RowMapper<T> mapper, Object... args) {
    var rows = jdbc.query(sql, mapper, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public UUID emailOwner(String email) {
    return first(
        "SELECT user_id FROM identity.login_email WHERE email_normalized=?",
        (rs, n) -> uuid(rs, "user_id"),
        email);
  }

  public String email(UUID user) {
    return first(
        "SELECT email_normalized FROM identity.login_email WHERE user_id=?",
        (rs, n) -> rs.getString(1),
        user);
  }

  public void claimEmail(UUID user, String email, Instant now) {
    jdbc.update(
        "INSERT INTO identity.login_email(user_id,email_original,email_normalized,verified_at,updated_at) VALUES (?,?,?,?,?)",
        user,
        email,
        email,
        t(now),
        t(now));
  }

  public void changeEmail(UUID user, String email, Instant now) {
    jdbc.update(
        "UPDATE identity.login_email SET email_original=?,email_normalized=?,verified_at=?,updated_at=?,version=version+1 WHERE user_id=?",
        email,
        email,
        t(now),
        t(now),
        user);
  }

  public boolean dispatch(byte[] key, String purpose, Instant now) {
    return jdbc.update(
            "INSERT INTO identity.email_dispatch_window(email_key,purpose,window_start,issued_count) VALUES (?,?,?,1) ON CONFLICT(email_key,purpose,window_start) DO UPDATE SET issued_count=identity.email_dispatch_window.issued_count+1 WHERE identity.email_dispatch_window.issued_count<3",
            key,
            purpose,
            t(now.truncatedTo(ChronoUnit.HOURS)))
        == 1;
  }

  public Credential credential(UUID user) {
    if (user == null) return null;
    return first(
        "SELECT user_id,password_hash,version FROM identity.local_credential WHERE user_id=?",
        (rs, n) ->
            new Credential(
                uuid(rs, "user_id"), rs.getString("password_hash"), rs.getLong("version")),
        user);
  }

  public void credential(UUID user, String encoded, Instant now) {
    jdbc.update(
        "INSERT INTO identity.local_credential(user_id,password_hash,password_changed_at,updated_at) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET password_hash=EXCLUDED.password_hash,password_changed_at=EXCLUDED.password_changed_at,updated_at=EXCLUDED.updated_at,version=identity.local_credential.version+1",
        user,
        encoded,
        t(now),
        t(now));
  }

  public void intent(
      UUID id,
      String email,
      byte[] hash,
      String flow,
      byte[] proof,
      byte[] browserHash,
      Instant expiresAt,
      Instant now) {
    jdbc.update(
        "INSERT INTO identity.registration_intent(id,email_normalized,token_hash,flow,provider_proof_encrypted,browser_binding_hash,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?)",
        id,
        email,
        hash,
        flow,
        proof,
        browserHash,
        t(expiresAt),
        t(now));
  }

  public Intent intent(byte[] hash) {
    return first(
        "SELECT * FROM identity.registration_intent WHERE token_hash=? FOR UPDATE",
        (rs, n) ->
            new Intent(
                uuid(rs, "id"),
                rs.getString("email_normalized"),
                rs.getString("flow"),
                rs.getBytes("provider_proof_encrypted"),
                rs.getBytes("browser_binding_hash"),
                instant(rs, "expires_at"),
                instant(rs, "consumed_at")),
        hash);
  }

  public void consumeIntent(UUID id, Instant now) {
    jdbc.update("UPDATE identity.registration_intent SET consumed_at=? WHERE id=?", t(now), id);
  }

  private static final RowMapper<Session> SESSION =
      (rs, n) ->
          new Session(
              uuid(rs, "id"),
              uuid(rs, "user_id"),
              instant(rs, "authenticated_at"),
              instant(rs, "reauthenticated_at"),
              instant(rs, "idle_expires_at"),
              instant(rs, "absolute_expires_at"),
              instant(rs, "revoked_at"),
              rs.getBoolean("reauthentication_required"),
              rs.getLong("authentication_epoch"),
              rs.getLong("version"));

  public Session session(UUID id, boolean lock) {
    return first(
        "SELECT * FROM identity.auth_session WHERE id=?" + (lock ? " FOR UPDATE" : ""),
        SESSION,
        id);
  }

  public void createSession(
      UUID id, UUID user, Instant now, boolean reauthenticationRequired, long authenticationEpoch) {
    jdbc.update(
        "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at,reauthentication_required,authentication_epoch) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        user,
        t(now),
        t(now),
        t(now),
        t(now.plusSeconds(7 * 86400L)),
        t(now.plusSeconds(30 * 86400L)),
        t(now),
        reauthenticationRequired,
        authenticationEpoch);
  }

  public void rotateSession(UUID id, Instant idle, Instant now) {
    jdbc.update(
        "UPDATE identity.auth_session SET idle_expires_at=?,last_seen_at=?,updated_at=?,version=version+1 WHERE id=?",
        t(idle),
        t(now),
        t(now),
        id);
  }

  public void reauthenticate(UUID id, Instant now) {
    jdbc.update(
        "UPDATE identity.auth_session SET reauthenticated_at=?,reauthentication_required=false,updated_at=?,version=version+1 WHERE id=?",
        t(now),
        t(now),
        id);
  }

  public void revoke(UUID id, String reason, Instant now) {
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=?,revoke_reason=?,updated_at=?,version=version+1 WHERE id=? AND revoked_at IS NULL",
        t(now),
        reason,
        t(now),
        id);
  }

  public void revokeAll(UUID user, UUID except, String reason, Instant now) {
    jdbc.update(
        "UPDATE identity.auth_session SET revoked_at=?,revoke_reason=?,updated_at=?,version=version+1 WHERE user_id=? AND revoked_at IS NULL AND (?::uuid IS NULL OR id<>?)",
        t(now),
        reason,
        t(now),
        user,
        except,
        except);
  }

  public List<Session> sessions(UUID user, Instant now, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM identity.auth_session WHERE user_id=? AND revoked_at IS NULL AND idle_expires_at>? AND absolute_expires_at>? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        SESSION,
        user,
        t(now),
        t(now),
        limit,
        offset);
  }

  public Refresh refresh(byte[] hash) {
    return first(
        "SELECT * FROM identity.refresh_token WHERE token_hash=?",
        (rs, n) ->
            new Refresh(
                uuid(rs, "id"),
                uuid(rs, "session_id"),
                rs.getInt("generation"),
                instant(rs, "expires_at"),
                instant(rs, "consumed_at")),
        hash);
  }

  public void refresh(
      UUID id, UUID session, int generation, byte[] hash, Instant expiry, Instant now) {
    jdbc.update(
        "INSERT INTO identity.refresh_token(id,session_id,generation,token_hash,issued_at,expires_at) VALUES (?,?,?,?,?,?)",
        id,
        session,
        generation,
        hash,
        t(now),
        t(expiry));
  }

  public void consumeRefresh(UUID id, UUID successor, Instant now) {
    jdbc.update(
        "UPDATE identity.refresh_token SET consumed_at=?,replaced_by_id=? WHERE id=?",
        t(now),
        successor,
        id);
  }

  public void oneTime(UUID id, UUID user, String purpose, byte[] hash, String target, Instant now) {
    jdbc.update(
        "UPDATE identity.one_time_token SET consumed_at=? WHERE user_id=? AND purpose=? AND consumed_at IS NULL",
        t(now),
        user,
        purpose);
    jdbc.update(
        "INSERT INTO identity.one_time_token(id,user_id,purpose,token_hash,target_email,expires_at,created_at) VALUES (?,?,?,?,?,?,?)",
        id,
        user,
        purpose,
        hash,
        target,
        t(now.plusSeconds(1800)),
        t(now));
  }

  public OneTime oneTime(byte[] hash) {
    return first(
        "SELECT * FROM identity.one_time_token WHERE token_hash=?",
        (rs, n) ->
            new OneTime(
                uuid(rs, "id"),
                uuid(rs, "user_id"),
                rs.getString("purpose"),
                rs.getString("target_email"),
                instant(rs, "expires_at"),
                instant(rs, "consumed_at")),
        hash);
  }

  public void consumeOneTime(UUID id, Instant now) {
    jdbc.update("UPDATE identity.one_time_token SET consumed_at=? WHERE id=?", t(now), id);
  }

  public void invalidateTokens(UUID user, String purpose, Instant now) {
    jdbc.update(
        "UPDATE identity.one_time_token SET consumed_at=? WHERE user_id=? AND purpose=? AND consumed_at IS NULL",
        t(now),
        user,
        purpose);
  }

  private static final RowMapper<External> EXTERNAL =
      (rs, n) ->
          new External(
              uuid(rs, "id"),
              uuid(rs, "user_id"),
              rs.getString("provider"),
              rs.getString("issuer"),
              rs.getString("subject"));

  public External external(String provider, String issuer, String subject) {
    return first(
        "SELECT * FROM identity.auth_identity WHERE provider=? AND issuer=? AND subject=?",
        EXTERNAL,
        provider,
        issuer,
        subject);
  }

  public List<External> identities(UUID user) {
    return jdbc.query(
        "SELECT * FROM identity.auth_identity WHERE user_id=? ORDER BY provider", EXTERNAL, user);
  }

  public void bind(UUID id, UUID user, ProviderProof proof, Instant now) {
    jdbc.update(
        "INSERT INTO identity.auth_identity(id,user_id,provider,issuer,subject,created_at,last_login_at) VALUES (?,?,?,?,?,?,?)",
        id,
        user,
        proof.provider(),
        proof.issuer(),
        proof.subject(),
        t(now),
        t(now));
  }

  public void unlink(UUID id, UUID user) {
    jdbc.update("DELETE FROM identity.auth_identity WHERE id=? AND user_id=?", id, user);
  }

  public void oauth(OAuth value, byte[] state, Instant now) {
    jdbc.update(
        "INSERT INTO identity.oauth_transaction(id,state_hash,browser_binding_hash,provider,intent,initiating_user_id,initiating_session_id,nonce_hash,pkce_verifier_encrypted,return_path,expires_at) VALUES (?,?,?,?,?,?,?,?,?,'/auth/result',?)",
        value.id(),
        state,
        value.browserHash(),
        value.provider(),
        value.intent(),
        value.userId(),
        value.sessionId(),
        value.nonceHash(),
        value.verifier(),
        t(value.expiresAt()));
  }

  public OAuth oauth(byte[] state) {
    return first(
        "SELECT * FROM identity.oauth_transaction WHERE state_hash=? FOR UPDATE",
        (rs, n) ->
            new OAuth(
                uuid(rs, "id"),
                rs.getString("provider"),
                rs.getString("intent"),
                uuid(rs, "initiating_user_id"),
                uuid(rs, "initiating_session_id"),
                rs.getBytes("browser_binding_hash"),
                rs.getBytes("nonce_hash"),
                rs.getBytes("pkce_verifier_encrypted"),
                instant(rs, "expires_at"),
                instant(rs, "consumed_at")),
        state);
  }

  public boolean consumeOAuth(UUID id, Instant now) {
    return jdbc.update(
            "UPDATE identity.oauth_transaction SET consumed_at=?,pkce_verifier_encrypted=NULL WHERE id=? AND consumed_at IS NULL AND expires_at>?",
            t(now),
            id,
            t(now))
        == 1;
  }

  public void prune(Instant now) {
    jdbc.update(
        "DELETE FROM identity.external_onboarding WHERE id IN (SELECT id FROM identity.external_onboarding WHERE expires_at<? ORDER BY expires_at LIMIT 500)",
        t(now.minusSeconds(86400)));
    jdbc.update(
        "DELETE FROM identity.registration_intent WHERE id IN (SELECT id FROM identity.registration_intent WHERE expires_at<? ORDER BY expires_at LIMIT 500)",
        t(now.minusSeconds(86400)));
    jdbc.update(
        "DELETE FROM identity.oauth_transaction WHERE id IN (SELECT id FROM identity.oauth_transaction WHERE expires_at<? ORDER BY expires_at LIMIT 500)",
        t(now.minusSeconds(86400)));
    jdbc.update(
        "DELETE FROM identity.one_time_token WHERE id IN (SELECT id FROM identity.one_time_token WHERE expires_at<? ORDER BY expires_at LIMIT 500)",
        t(now.minusSeconds(7 * 86400)));
    jdbc.update(
        "DELETE FROM identity.email_dispatch_window WHERE (email_key,purpose,window_start) IN (SELECT email_key,purpose,window_start FROM identity.email_dispatch_window WHERE window_start<? ORDER BY window_start LIMIT 500)",
        t(now.minusSeconds(86400)));
    pruneSessions(now);
  }

  private void pruneSessions(Instant now) {
    jdbc.update(
        "DELETE FROM identity.refresh_token WHERE id IN (SELECT r.id FROM identity.refresh_token r JOIN identity.auth_session s ON s.id=r.session_id WHERE s.absolute_expires_at<? ORDER BY r.session_id,r.generation LIMIT 500)",
        t(now.minusSeconds(7 * 86400L)));
    jdbc.update(
        "DELETE FROM identity.auth_session WHERE id IN (SELECT s.id FROM identity.auth_session s WHERE s.absolute_expires_at<? AND NOT EXISTS(SELECT 1 FROM identity.refresh_token r WHERE r.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM identity.oauth_transaction o WHERE o.initiating_session_id=s.id) LIMIT 500)",
        t(now.minusSeconds(7 * 86400L)));
  }

  public void onboarding(UUID id, byte[] hash, byte[] browser, byte[] proof, Instant expires) {
    jdbc.update(
        "INSERT INTO identity.external_onboarding(id,secret_hash,browser_binding_hash,provider_proof_encrypted,expires_at) VALUES (?,?,?,?,?)",
        id,
        hash,
        browser,
        proof,
        t(expires));
  }

  public byte[] consumeOnboarding(byte[] hash, byte[] browser, Instant now) {
    var rows =
        jdbc.query(
            "UPDATE identity.external_onboarding SET consumed_at=? WHERE secret_hash=? AND browser_binding_hash=? AND consumed_at IS NULL AND expires_at>? RETURNING provider_proof_encrypted",
            (rs, n) -> rs.getBytes(1),
            t(now),
            hash,
            browser,
            t(now));
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
