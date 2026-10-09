package com.flyvo.socialcore.identity.application;

import com.flyvo.socialcore.account.api.AccountCommands;
import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.identity.api.SessionQueries;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import com.flyvo.socialcore.identity.application.port.PasswordHashing;
import com.flyvo.socialcore.identity.domain.EmailAddress;
import com.flyvo.socialcore.identity.domain.IdentityState.*;
import com.flyvo.socialcore.identity.domain.PasswordPolicy;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.api.Secrets;
import com.flyvo.socialcore.profile.api.ProfileCommands;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class Authentication {
  private final IdentityRepository store;
  private final AccountCommands commands;
  private final AccountQueries accounts;
  private final ProfileCommands profiles;
  private final DurableWork work;
  private final Secrets secrets;
  private final PasswordHashing passwords;
  private final AccessTokens tokens;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final String dummy;
  private final Set<String> common;
  private final SessionQueries sessions;

  public Authentication(
      IdentityRepository store,
      AccountCommands commands,
      AccountQueries accounts,
      ProfileCommands profiles,
      DurableWork work,
      Secrets secrets,
      PasswordHashing passwords,
      AccessTokens tokens,
      Clock clock,
      org.springframework.transaction.PlatformTransactionManager manager,
      ObjectMapper json,
      SessionQueries sessions)
      throws java.io.IOException {
    this.store = store;
    this.commands = commands;
    this.accounts = accounts;
    this.profiles = profiles;
    this.work = work;
    this.secrets = secrets;
    this.passwords = passwords;
    this.tokens = tokens;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    this.json = json;
    this.sessions = sessions;
    dummy = passwords.encode(secrets.randomToken());
    try (var stream =
        new org.springframework.core.io.ClassPathResource("security/common-passwords.txt")
            .getInputStream()) {
      common =
          Set.copyOf(
              new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                  .lines()
                  .filter(s -> !s.isBlank())
                  .toList());
    }
  }

  public record Grant(String accessToken, String refreshSecret, UUID sessionId) {
    @Override
    public String toString() {
      return "Grant[redacted]";
    }
  }

  public record SessionInfo(
      UUID id,
      Instant authenticatedAt,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      boolean current) {}

  public String normalize(String email) {
    try {
      return EmailAddress.parse(email).normalized();
    } catch (IllegalArgumentException e) {
      throw Failure.invalid();
    }
  }

  private String encodeNew(String password) {
    try {
      PasswordPolicy.validate(password, common);
    } catch (IllegalArgumentException e) {
      throw Failure.invalid();
    }
    return passwords.encode(password);
  }

  public void register(String rawEmail, String browser) {
    String email = normalize(rawEmail);
    tx.executeWithoutResult(status -> initiate(email, browser, null));
  }

  void initiate(String email, String browser, ProviderProof proof) {
    if (browser == null) throw Failure.invalid();
    var now = clock.instant();
    if (!store.dispatch(secrets.emailKey(email), "VERIFICATION", now)) return;
    if (store.emailOwner(email) != null) {
      work.mail(UUID.randomUUID().toString(), email, "SIGN_IN_GUIDANCE", "", now.plusSeconds(1800));
      return;
    }
    var id = UUID.randomUUID();
    var secret = secrets.randomToken();
    store.intent(
        id,
        email,
        secrets.hash(secret),
        proof == null ? "LOCAL" : "EXTERNAL",
        proof == null ? null : secrets.encrypt("provider-proof", json.writeValueAsString(proof)),
        secrets.hash(browser),
        now.plusSeconds(1800),
        now);
    work.mail(id.toString(), email, "VERIFY_EMAIL", secret, now.plusSeconds(1800));
  }

  public Grant complete(String secret, String browser, String password) {
    // Hashing is deliberately outside the account/intent commit transaction.
    String encoded = password == null ? null : encodeNew(password);
    return tx.execute(
        status -> {
          var now = clock.instant();
          var intent = store.intent(hashSecret(secret));
          if (intent == null
              || intent.consumedAt() != null
              || !now.isBefore(intent.expiresAt())
              || browser == null
              || !MessageDigest.isEqual(intent.browserHash(), secrets.hash(browser)))
            throw new Failure(401, "INVALID_PROOF");
          if ("LOCAL".equals(intent.flow()) && encoded == null
              || "EXTERNAL".equals(intent.flow()) && password != null) throw Failure.invalid();
          if (store.emailOwner(intent.email()) != null) throw Failure.conflict();
          ProviderProof proof =
              intent.proof() == null
                  ? null
                  : json.readValue(
                      secrets.decrypt("provider-proof", intent.proof()), ProviderProof.class);
          if (proof != null
              && store.external(proof.provider(), proof.issuer(), proof.subject()) != null)
            throw Failure.conflict();
          var user = commands.createVerified();
          store.claimEmail(user, intent.email(), now);
          if (encoded != null) store.credential(user, encoded, now);
          else store.bind(UUID.randomUUID(), user, proof, now);
          profiles.provision(user);
          store.consumeIntent(intent.id(), now);
          work.audit(user, "REGISTRATION_COMPLETED", "account", user, "SUCCESS", null);
          return grant(user, encoded == null);
        });
  }

  public Grant login(String rawEmail, String password) {
    String email = normalize(rawEmail);
    if (password == null || password.length() > 256) throw Failure.unauthorized();
    var credential = store.credential(store.emailOwner(email));
    boolean valid = passwords.matches(password, credential == null ? dummy : credential.hash());
    String upgraded =
        valid && credential != null && passwords.needsUpgrade(credential.hash())
            ? passwords.encode(password)
            : null;
    if (!valid || credential == null) {
      tx.executeWithoutResult(
          status -> work.audit(null, "LOGIN_FAILED", "account", null, "DENIED", "BAD_CREDENTIALS"));
      throw Failure.unauthorized();
    }
    return tx.execute(
        status -> {
          activeGuard(credential.userId(), upgraded != null);
          var current = store.credential(credential.userId());
          if (current == null
              || current.version() != credential.version()
              || !email.equals(store.email(credential.userId()))) throw Failure.unauthorized();
          if (upgraded != null) store.credential(credential.userId(), upgraded, clock.instant());
          work.audit(
              credential.userId(),
              "LOGIN_SUCCEEDED",
              "account",
              credential.userId(),
              "SUCCESS",
              null);
          return grant(credential.userId());
        });
  }

  Grant grant(UUID user) {
    return grant(user, false);
  }

  private Grant grant(UUID user, boolean reauthenticationRequired) {
    var now = clock.instant();
    var session = UUID.randomUUID();
    var secret = secrets.randomToken();
    store.createSession(session, user, now, reauthenticationRequired);
    store.refresh(
        UUID.randomUUID(), session, 0, secrets.hash(secret), now.plusSeconds(7 * 86400L), now);
    return new Grant(tokens.issue(new ActorContext(user, session)), secret, session);
  }

  public Grant refresh(String secret) {
    var hash = hashSecret(secret);
    var observed = store.refresh(hash);
    if (observed == null) throw Failure.unauthorized();
    var session = store.session(observed.sessionId(), false);
    if (session == null) throw Failure.unauthorized();
    Grant result =
        tx.execute(
            status -> {
              var account = accounts.guard(session.userId(), false);
              var family = store.session(session.id(), true);
              var now = clock.instant();
              var token = store.refresh(hash);
              if (family == null || token == null) return null;
              if (token.consumedAt() != null) {
                store.revoke(family.id(), "REFRESH_REPLAY", now);
                work.audit(
                    family.userId(),
                    "REFRESH_REPLAY",
                    "session",
                    family.id(),
                    "DENIED",
                    "CONSUMED_TOKEN");
                return null;
              }
              if (!account.active() || !family.active(now) || !now.isBefore(token.expiresAt()))
                return null;
              var successor = UUID.randomUUID();
              var next = secrets.randomToken();
              var expiry = now.plusSeconds(7 * 86400L);
              if (expiry.isAfter(family.absoluteExpiresAt())) expiry = family.absoluteExpiresAt();
              store.consumeRefresh(token.id(), successor, now);
              store.refresh(
                  successor, family.id(), token.generation() + 1, secrets.hash(next), expiry, now);
              store.rotateSession(family.id(), expiry, now);
              return new Grant(
                  tokens.issue(new ActorContext(family.userId(), family.id())), next, family.id());
            });
    if (result == null) throw Failure.unauthorized();
    return result;
  }

  public void requireCurrent(ActorContext actor) {
    sessions.requireCurrent(actor);
  }

  Session current(ActorContext actor, boolean exclusive, boolean recent) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    activeGuard(actor.userId(), exclusive);
    var session = store.session(actor.sessionId(), true);
    if (session == null
        || !session.userId().equals(actor.userId())
        || !session.active(clock.instant())) throw Failure.unauthorized();
    if (recent && !session.recent(clock.instant()))
      throw new Failure(403, "REAUTHENTICATION_REQUIRED");
    return session;
  }

  void activeGuard(UUID user, boolean exclusive) {
    if (!accounts.guard(user, exclusive).active()) throw Failure.unauthorized();
  }

  public void logout(String secret) {
    if (secret == null) return;
    var token = store.refresh(hashSecret(secret));
    if (token == null) return;
    var family = store.session(token.sessionId(), false);
    if (family == null) return;
    tx.executeWithoutResult(
        status -> {
          accounts.guard(family.userId(), false);
          store.session(family.id(), true);
          store.revoke(family.id(), "LOGOUT", clock.instant());
          work.audit(
              family.userId(), "SESSION_REVOKED", "session", family.id(), "SUCCESS", "LOGOUT");
        });
  }

  public void logoutAll(ActorContext actor) {
    tx.executeWithoutResult(
        status -> {
          current(actor, true, false);
          store.revokeAll(actor.userId(), null, "LOGOUT_ALL", clock.instant());
          work.audit(
              actor.userId(), "ALL_SESSIONS_REVOKED", "account", actor.userId(), "SUCCESS", null);
        });
  }

  public List<SessionInfo> sessions(ActorContext actor, int size, int offset) {
    if (size < 1 || size > 100 || offset < 0 || offset > 10000) throw Failure.invalid();
    requireCurrent(actor);
    return store.sessions(actor.userId(), clock.instant(), size, offset).stream()
        .map(
            s ->
                new SessionInfo(
                    s.id(),
                    s.authenticatedAt(),
                    s.idleExpiresAt(),
                    s.absoluteExpiresAt(),
                    s.id().equals(actor.sessionId())))
        .toList();
  }

  public void revokeSession(ActorContext actor, UUID target) {
    tx.executeWithoutResult(
        status -> {
          current(actor, true, false);
          var session = store.session(target, true);
          if (session == null || !session.userId().equals(actor.userId()))
            throw new Failure(404, "NOT_FOUND");
          store.revoke(target, "USER_REVOKED", clock.instant());
          work.audit(actor.userId(), "SESSION_REVOKED", "session", target, "SUCCESS", null);
        });
  }

  public void requestReset(String rawEmail) {
    var email = normalize(rawEmail);
    tx.executeWithoutResult(
        status -> {
          var now = clock.instant();
          if (!store.dispatch(secrets.emailKey(email), "RESET", now)) return;
          var user = store.emailOwner(email);
          if (user == null) return;
          // The pre-lock lookup only selects the guard. Email change may have committed while
          // waiting; only current recovery-email ownership and local eligibility may issue proof.
          if (!accounts.guard(user, true).active()
              || !email.equals(store.email(user))
              || store.credential(user) == null) return;
          now = clock.instant();
          var id = UUID.randomUUID();
          var secret = secrets.randomToken();
          store.oneTime(id, user, "RESET_PASSWORD", secrets.hash(secret), null, now);
          work.mail(id.toString(), email, "RESET_PASSWORD", secret, now.plusSeconds(1800));
        });
  }

  public void reset(String secret, String password) {
    var encoded = encodeNew(password);
    var hash = hashSecret(secret);
    var observed = store.oneTime(hash);
    if (observed == null) throw new Failure(401, "INVALID_PROOF");
    tx.executeWithoutResult(
        status -> {
          activeGuard(observed.userId(), true);
          var token = usable(hash, "RESET_PASSWORD");
          if (store.credential(token.userId()) == null) throw new Failure(401, "INVALID_PROOF");
          store.credential(token.userId(), encoded, clock.instant());
          store.consumeOneTime(token.id(), clock.instant());
          store.revokeAll(token.userId(), null, "PASSWORD_RESET", clock.instant());
          notify(token.userId(), "PASSWORD_RESET");
          work.audit(token.userId(), "PASSWORD_RESET", "account", token.userId(), "SUCCESS", null);
        });
  }

  OneTime usable(byte[] hash, String purpose) {
    var token = store.oneTime(hash);
    if (token == null
        || !purpose.equals(token.purpose())
        || token.consumedAt() != null
        || !clock.instant().isBefore(token.expiresAt())) throw new Failure(401, "INVALID_PROOF");
    return token;
  }

  public void reauthenticate(ActorContext actor, String password) {
    var snapshot = store.credential(actor.userId());
    if (snapshot == null
        || password == null
        || password.length() > 256
        || !passwords.matches(password, snapshot.hash())) throw Failure.unauthorized();
    tx.executeWithoutResult(
        status -> {
          current(actor, true, false);
          var current = store.credential(actor.userId());
          if (current == null || current.version() != snapshot.version())
            throw Failure.unauthorized();
          store.reauthenticate(actor.sessionId(), clock.instant());
          work.audit(
              actor.userId(),
              "REAUTHENTICATED",
              "session",
              actor.sessionId(),
              "SUCCESS",
              "PASSWORD");
        });
  }

  public void password(ActorContext actor, String password) {
    var encoded = encodeNew(password);
    tx.executeWithoutResult(
        status -> {
          var session = current(actor, true, true);
          if (store.credential(actor.userId()) == null && session.reauthenticatedAt() == null)
            throw new Failure(403, "REAUTHENTICATION_REQUIRED");
          store.credential(actor.userId(), encoded, clock.instant());
          store.invalidateTokens(actor.userId(), "RESET_PASSWORD", clock.instant());
          store.revokeAll(actor.userId(), actor.sessionId(), "PASSWORD_CHANGED", clock.instant());
          notify(actor.userId(), "PASSWORD_CHANGED");
          work.audit(
              actor.userId(), "PASSWORD_CHANGED", "account", actor.userId(), "SUCCESS", null);
        });
  }

  public void requestEmail(ActorContext actor, String rawEmail) {
    String email = normalize(rawEmail);
    tx.executeWithoutResult(
        status -> {
          current(actor, true, true);
          if (store.emailOwner(email) != null) throw Failure.conflict();
          var now = clock.instant();
          if (!store.dispatch(secrets.emailKey(email), "CHANGE_EMAIL", now))
            throw new Failure(429, "EMAIL_RATE_LIMITED");
          var secret = secrets.randomToken();
          var id = UUID.randomUUID();
          store.oneTime(id, actor.userId(), "CHANGE_EMAIL", secrets.hash(secret), email, now);
          work.mail(id.toString(), email, "CHANGE_EMAIL", secret, now.plusSeconds(1800));
        });
  }

  public void completeEmail(ActorContext actor, String secret) {
    var hash = hashSecret(secret);
    tx.executeWithoutResult(
        status -> {
          current(actor, true, true);
          var token = usable(hash, "CHANGE_EMAIL");
          if (!token.userId().equals(actor.userId())) throw new Failure(401, "INVALID_PROOF");
          var old = store.email(actor.userId());
          store.changeEmail(actor.userId(), token.targetEmail(), clock.instant());
          store.invalidateTokens(actor.userId(), "RESET_PASSWORD", clock.instant());
          store.consumeOneTime(token.id(), clock.instant());
          store.revokeAll(actor.userId(), actor.sessionId(), "EMAIL_CHANGED", clock.instant());
          work.mail(
              UUID.randomUUID().toString(),
              old,
              "EMAIL_CHANGED",
              "",
              clock.instant().plusSeconds(1800));
          work.audit(actor.userId(), "EMAIL_CHANGED", "account", actor.userId(), "SUCCESS", null);
        });
  }

  void notify(UUID user, String kind) {
    work.mail(
        UUID.randomUUID().toString(),
        store.email(user),
        kind,
        "",
        clock.instant().plusSeconds(1800));
  }

  public record AccountInfo(
      UUID userId, String email, String status, boolean hasPassword, long version) {}

  public AccountInfo account(ActorContext actor) {
    requireCurrent(actor);
    return new AccountInfo(
        actor.userId(),
        store.email(actor.userId()),
        accounts.find(actor.userId()).status(),
        store.credential(actor.userId()) != null,
        accounts.find(actor.userId()).version());
  }

  public record IdentityInfo(UUID id, String provider) {}

  public List<IdentityInfo> identities(ActorContext actor) {
    requireCurrent(actor);
    return store.identities(actor.userId()).stream()
        .map(e -> new IdentityInfo(e.id(), e.provider()))
        .toList();
  }

  public void unlink(ActorContext actor, UUID id) {
    tx.executeWithoutResult(
        status -> {
          current(actor, true, true);
          var all = store.identities(actor.userId());
          if (all.stream().noneMatch(i -> i.id().equals(id))) throw new Failure(404, "NOT_FOUND");
          if (all.size() == 1 && store.credential(actor.userId()) == null)
            throw new Failure(409, "LAST_LOGIN_METHOD");
          store.unlink(id, actor.userId());
          notify(actor.userId(), "IDENTITY_UNLINKED");
          work.audit(actor.userId(), "IDENTITY_UNLINKED", "identity", id, "SUCCESS", null);
        });
  }

  public void close(ActorContext actor, long expectedVersion) {
    tx.executeWithoutResult(
        status -> {
          current(actor, true, true);
          if (accounts.find(actor.userId()).version() != expectedVersion)
            throw new Failure(412, "STALE_VERSION");
          commands.requestDeletion(actor.userId());
          store.revokeAll(actor.userId(), null, "ACCOUNT_DELETION", clock.instant());
        });
  }

  byte[] hashSecret(String secret) {
    if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw Failure.unauthorized();
    return secrets.hash(secret);
  }
}
