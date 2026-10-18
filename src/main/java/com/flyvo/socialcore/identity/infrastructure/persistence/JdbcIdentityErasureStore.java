package com.flyvo.socialcore.identity.infrastructure.persistence;

import com.flyvo.socialcore.identity.application.port.IdentityErasureStore;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityErasureStore implements IdentityErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcIdentityErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void start(UUID user) {
    jdbc.update(
        "INSERT INTO identity.erasure_marker(user_id) VALUES (?) ON CONFLICT DO NOTHING", user);
  }

  public void captureLogin(UUID user) {
    jdbc.update(
        "INSERT INTO identity.erasure_mailbox(user_id,email_normalized) SELECT user_id,email_normalized FROM identity.login_email WHERE user_id=? ON CONFLICT DO NOTHING",
        user);
  }

  public void eraseCredential(UUID user) {
    jdbc.update("DELETE FROM identity.local_credential WHERE user_id=?", user);
  }

  public List<UUID> tokens(UUID user) {
    return jdbc.query(
        "SELECT id FROM identity.one_time_token WHERE user_id=? ORDER BY id LIMIT 100",
        (r, n) -> r.getObject(1, UUID.class),
        user);
  }

  public void eraseTokens(UUID user, List<UUID> ids) {
    jdbc.update(
        "INSERT INTO identity.erasure_mailbox(user_id,email_normalized) SELECT DISTINCT user_id,target_email FROM identity.one_time_token WHERE user_id=? AND id=ANY(?) AND target_email IS NOT NULL ON CONFLICT DO NOTHING",
        user,
        ids.toArray(UUID[]::new));
    jdbc.update(
        "DELETE FROM identity.one_time_token WHERE user_id=? AND id=ANY(?)",
        user,
        ids.toArray(UUID[]::new));
  }

  private String mailbox(UUID user) {
    var rows =
        jdbc.query(
            "SELECT email_normalized FROM identity.erasure_mailbox WHERE user_id=? AND NOT scanned ORDER BY email_normalized LIMIT 1",
            (r, n) -> r.getString(1),
            user);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public List<UUID> registrations(UUID user, Instant cut) {
    var email = mailbox(user);
    if (email == null) return List.of();
    return jdbc.query(
        "SELECT id FROM identity.registration_intent WHERE email_normalized=? AND created_at<=? ORDER BY id LIMIT 100",
        (r, n) -> r.getObject(1, UUID.class),
        email,
        cut.atOffset(ZoneOffset.UTC));
  }

  public boolean nextMailbox(UUID user, Instant cut) {
    var email = mailbox(user);
    if (email == null) return false;
    jdbc.update(
        "UPDATE identity.erasure_mailbox SET scanned=true WHERE user_id=? AND email_normalized=? AND NOT EXISTS(SELECT 1 FROM identity.registration_intent WHERE email_normalized=? AND created_at<=?)",
        user,
        email,
        email,
        cut.atOffset(ZoneOffset.UTC));
    return mailbox(user) != null;
  }

  public void eraseRegistrations(UUID user, List<UUID> ids) {
    jdbc.update(
        "DELETE FROM identity.registration_intent WHERE id=ANY(?) AND email_normalized IN (SELECT email_normalized FROM identity.erasure_mailbox WHERE user_id=?)",
        ids.toArray(UUID[]::new),
        user);
  }

  public int eraseOAuth(UUID user) {
    return jdbc.update(
        "DELETE FROM identity.oauth_transaction WHERE id IN (SELECT id FROM identity.oauth_transaction WHERE initiating_user_id=? ORDER BY id LIMIT 100)",
        user);
  }

  public int eraseRefresh(UUID user) {
    return jdbc.update(
        "DELETE FROM identity.refresh_token WHERE id IN (SELECT r.id FROM identity.refresh_token r JOIN identity.auth_session s ON s.id=r.session_id WHERE s.user_id=? ORDER BY r.session_id,r.generation LIMIT 100)",
        user);
  }

  public int eraseSessions(UUID user) {
    return jdbc.update(
        "DELETE FROM identity.auth_session WHERE id IN (SELECT id FROM identity.auth_session s WHERE user_id=? AND NOT EXISTS(SELECT 1 FROM identity.refresh_token r WHERE r.session_id=s.id) AND NOT EXISTS(SELECT 1 FROM identity.oauth_transaction o WHERE o.initiating_session_id=s.id) ORDER BY id LIMIT 100)",
        user);
  }

  public void releaseClaims(UUID user) {
    jdbc.update(
        "DELETE FROM identity.auth_identity WHERE user_id=?",
        user); // At most two providers by the current unique/provider CHECKs.
    jdbc.update("DELETE FROM identity.login_email WHERE user_id=?", user);
  }

  public void eraseMailboxes(UUID user) {
    jdbc.update(
        "DELETE FROM identity.erasure_mailbox WHERE (user_id,email_normalized) IN (SELECT user_id,email_normalized FROM identity.erasure_mailbox WHERE user_id=? ORDER BY email_normalized LIMIT 100)",
        user);
  }

  public boolean residualSecrets(UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM identity.local_credential WHERE user_id=?) OR EXISTS(SELECT 1 FROM identity.one_time_token WHERE user_id=?) OR EXISTS(SELECT 1 FROM identity.auth_session WHERE user_id=?) OR EXISTS(SELECT 1 FROM identity.oauth_transaction WHERE initiating_user_id=?) OR EXISTS(SELECT 1 FROM identity.erasure_mailbox WHERE user_id=?)",
            Boolean.class,
            user,
            user,
            user,
            user,
            user));
  }
}
