package com.flyvo.socialcore.identity.application;

import com.flyvo.socialcore.identity.application.port.IdentityRepository;
import com.flyvo.socialcore.identity.application.port.ProviderClient;
import com.flyvo.socialcore.identity.domain.IdentityState.*;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.Failure;
import com.flyvo.socialcore.platform.api.Secrets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class OAuthAuthentication {
  private final IdentityRepository store;
  private final ProviderClient provider;
  private final Authentication auth;
  private final Secrets secrets;
  private final Clock clock;
  private final DurableWork work;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public OAuthAuthentication(
      IdentityRepository store,
      ProviderClient provider,
      Authentication auth,
      Secrets secrets,
      Clock clock,
      DurableWork work,
      ObjectMapper json,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.store = store;
    this.provider = provider;
    this.auth = auth;
    this.secrets = secrets;
    this.clock = clock;
    this.work = work;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  public record Start(String location, String browser) {}

  public record Result(String marker, String refresh, String onboarding) {
    @Override
    public String toString() {
      return "OAuthResult[redacted]";
    }
  }

  public Start start(String name, String intent, ActorContext actor, String browser) {
    String p = name.toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("GOOGLE", "FACEBOOK").contains(p)
        || !java.util.Set.of("LOGIN", "LINK", "REAUTH").contains(intent)) throw Failure.invalid();
    String binding = browser == null ? secrets.randomToken() : browser;
    String state = secrets.randomToken(),
        nonce = secrets.randomToken(),
        verifier = secrets.randomToken();
    String location = provider.authorization(p, state, nonce, verifier);
    tx.executeWithoutResult(
        status -> {
          if (!"LOGIN".equals(intent)) auth.current(actor, true, "LINK".equals(intent));
          var id = UUID.randomUUID();
          var now = store.databaseTime();
          store.oauth(
              new OAuth(
                  id,
                  p,
                  intent,
                  "LOGIN".equals(intent) ? null : actor.userId(),
                  "LOGIN".equals(intent) ? null : actor.sessionId(),
                  secrets.hash(binding),
                  "GOOGLE".equals(p) ? secrets.hash(nonce) : null,
                  "GOOGLE".equals(p) ? secrets.encrypt("pkce:" + id, verifier) : null,
                  now.plusSeconds(600),
                  null),
              secrets.hash(state),
              now);
        });
    return new Start(location, binding);
  }

  public Result callback(
      String name, String state, String browser, String code, String providerError) {
    var transaction =
        tx.execute(
            status -> {
              var value = store.oauth(auth.hashSecret(state));
              var now = store.databaseTime();
              if (value == null
                  || !name.toUpperCase(java.util.Locale.ROOT).equals(value.provider())
                  || browser == null
                  || !MessageDigest.isEqual(value.browserHash(), secrets.hash(browser))
                  || value.consumedAt() != null
                  || !now.isBefore(value.expiresAt()))
                throw new Failure(401, "INVALID_OAUTH_TRANSACTION");
              if (!store.consumeOAuth(value.id(), now))
                throw new Failure(401, "INVALID_OAUTH_TRANSACTION");
              return value;
            });
    if (providerError != null) return new Result("provider_denied", null, null);
    // State consumption commits before external exchange: callbacks are never replayed, including
    // failed exchanges.
    var proof =
        provider.verify(
            transaction.provider(),
            code,
            transaction.nonceHash(),
            transaction.verifier() == null
                ? null
                : secrets.decrypt("pkce:" + transaction.id(), transaction.verifier()));
    if (!transaction.provider().equals(proof.provider())
        || proof.subject() == null
        || proof.subject().isBlank()
        || proof.subject().length() > 255
        || proof.issuer() == null
        || proof.issuer().length() > 255) throw new Failure(401, "PROVIDER_PROOF_INVALID");
    Result result;
    try {
      result =
          tx.execute(
              status -> {
                // A consumed state is not an unlimited network-completion credential. This
                // bounds pre-erasure anonymous proof chains before unique claims are released.
                callbackTime(transaction);
                var existing = store.external(proof.provider(), proof.issuer(), proof.subject());
                if (!"LOGIN".equals(transaction.intent())) {
                  var actor = new ActorContext(transaction.userId(), transaction.sessionId());
                  auth.current(actor, true, "LINK".equals(transaction.intent()));
                  callbackTime(transaction);
                  var guarded = store.external(proof.provider(), proof.issuer(), proof.subject());
                  if (existing != null && !sameBinding(existing, guarded)) {
                    if ("LINK".equals(transaction.intent()))
                      work.audit(
                          actor.userId(),
                          "IDENTITY_LINK_REJECTED",
                          "account",
                          actor.userId(),
                          "DENIED",
                          "BINDING_CHANGED");
                    return new Result("identity_conflict", null, null);
                  }
                  existing = guarded;
                  if ("REAUTH".equals(transaction.intent())) {
                    if (existing == null || !actor.userId().equals(existing.userId()))
                      return new Result("identity_conflict", null, null);
                    store.reauthenticate(actor.sessionId(), clock.instant());
                    work.audit(
                        actor.userId(),
                        "REAUTHENTICATED",
                        "session",
                        actor.sessionId(),
                        "SUCCESS",
                        proof.provider());
                    callbackTime(transaction);
                    return new Result("reauthenticated", null, null);
                  }
                  if (existing != null && !actor.userId().equals(existing.userId())) {
                    work.audit(
                        actor.userId(),
                        "IDENTITY_LINK_REJECTED",
                        "account",
                        actor.userId(),
                        "DENIED",
                        "BOUND_ELSEWHERE");
                    return new Result("identity_conflict", null, null);
                  }
                  if (existing == null)
                    store.bind(UUID.randomUUID(), actor.userId(), proof, clock.instant());
                  auth.notify(actor.userId(), "IDENTITY_LINKED");
                  work.audit(
                      actor.userId(),
                      "IDENTITY_LINKED",
                      "account",
                      actor.userId(),
                      "SUCCESS",
                      proof.provider());
                  callbackTime(transaction);
                  return new Result("linked", null, null);
                }
                if (existing != null) {
                  auth.activeGuard(existing.userId(), false);
                  // Never grant from an observation made before the guard or follow a new owner
                  // by acquiring a second account guard out of order. A new flow is required.
                  var guarded = store.external(proof.provider(), proof.issuer(), proof.subject());
                  callbackTime(transaction);
                  if (!sameBinding(existing, guarded))
                    return new Result("identity_conflict", null, null);
                  var grant = auth.grant(guarded.userId());
                  work.audit(
                      guarded.userId(),
                      "LOGIN_SUCCEEDED",
                      "account",
                      existing.userId(),
                      "SUCCESS",
                      proof.provider());
                  callbackTime(transaction);
                  return new Result("signed_in", grant.refreshSecret(), null);
                }
                if (proof.email() != null) {
                  String email;
                  try {
                    email = auth.normalize(proof.email());
                  } catch (Failure invalid) {
                    email = null;
                  }
                  if (email != null && store.emailOwner(email) != null)
                    return new Result("sign_in_to_connect", null, null);
                }
                // All unbound identities enter a limited onboarding capability; own mailbox proof
                // is
                // still required.
                var secret = secrets.randomToken();
                var now = callbackTime(transaction);
                var expiry = now.plusSeconds(600);
                if (expiry.isAfter(transaction.expiresAt().plusSeconds(600)))
                  expiry = transaction.expiresAt().plusSeconds(600);
                store.onboarding(
                    UUID.randomUUID(),
                    secrets.hash(secret),
                    secrets.hash(browser),
                    secrets.encrypt("onboarding", json.writeValueAsString(proof)),
                    expiry);
                callbackTime(transaction);
                return new Result("verify_recovery_email", null, secret);
              });
    } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
      if (!"LINK".equals(transaction.intent())) throw conflict;
      // The unique claim can race after the earlier read. The failed binding is fully rolled back;
      // attributable denial is a separate complete transaction, not a hidden partial success.
      tx.executeWithoutResult(
          status -> {
            var actor = new ActorContext(transaction.userId(), transaction.sessionId());
            auth.current(actor, true, false);
            work.audit(
                actor.userId(),
                "IDENTITY_LINK_REJECTED",
                "account",
                actor.userId(),
                "DENIED",
                "UNIQUE_CLAIM_CONFLICT");
          });
      return new Result("identity_conflict", null, null);
    }
    return result;
  }

  private boolean sameBinding(External observed, External guarded) {
    return guarded != null
        && observed.id().equals(guarded.id())
        && observed.userId().equals(guarded.userId());
  }

  private java.time.Instant callbackTime(OAuth transaction) {
    var now = store.databaseTime();
    if (!now.isBefore(transaction.expiresAt())) throw new Failure(401, "INVALID_OAUTH_TRANSACTION");
    return now;
  }

  public void register(
      String email, String onboarding, String browser, String registrationBrowser) {
    String normalized = auth.normalize(email);
    tx.executeWithoutResult(
        status -> {
          var value = store.onboarding(auth.hashSecret(onboarding));
          var now = store.databaseTime();
          if (value == null
              || value.consumedAt() != null
              || !MessageDigest.isEqual(
                  value.browserHash(), secrets.hash(browser == null ? "" : browser))
              || !now.isBefore(value.expiresAt())
              || !store.consumeOnboarding(value.id(), now)) throw new Failure(401, "INVALID_PROOF");
          var proof =
              json.readValue(secrets.decrypt("onboarding", value.proof()), ProviderProof.class);
          auth.initiate(normalized, registrationBrowser, proof, value.expiresAt());
        });
  }
}
