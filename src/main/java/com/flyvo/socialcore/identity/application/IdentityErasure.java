package com.flyvo.socialcore.identity.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.identity.application.port.IdentityErasureStore;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class IdentityErasure implements JobHandler {
  private final AccountErasure erasure;
  private final IdentityErasureStore store;
  private final ObjectMapper json;
  private final Clock clock;

  public IdentityErasure(
      AccountErasure erasure, IdentityErasureStore store, ObjectMapper json, Clock clock) {
    this.erasure = erasure;
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  public String kind() {
    return "IDENTITY_ERASURE";
  }

  public void apply(String payload) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.IDENTITY) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    var c = erasure.begin(pass.userId(), Step.IDENTITY, pass.version());
    if (c == null) return;
    var stage = c.checkpoint();
    if (stage.startsWith("TOKEN_EXPORT:") || stage.startsWith("REG_EXPORT:")) {
      var chunk = UUID.fromString(stage.substring(stage.indexOf(':') + 1));
      if (!erasure.resourcesExported(c, chunk)) {
        waitFor(c, stage, "RESOURCE_EXPORT_PENDING", erasure.databaseTime().plusSeconds(60));
        return;
      }
      var ids = erasure.resourceIds(c, chunk);
      if (stage.startsWith("TOKEN")) {
        store.eraseTokens(c.userId(), ids);
        next(c, "TOKENS", ids.size());
      } else {
        store.eraseRegistrations(c.userId(), ids);
        next(c, "REGISTRATIONS", ids.size());
      }
      return;
    }
    switch (stage) {
      case "START" -> {
        store.start(c.userId());
        store.captureLogin(c.userId());
        store.eraseCredential(c.userId());
        next(c, "TOKENS", 0);
      }
      case "TOKENS" -> {
        var ids = store.tokens(c.userId());
        if (ids.isEmpty()) next(c, "REGISTRATIONS", 0);
        else {
          var chunk = erasure.recordResources(c, "MAIL_PROOF", ids);
          waitFor(
              c,
              "TOKEN_EXPORT:" + chunk,
              "RESOURCE_EXPORT_PENDING",
              erasure.databaseTime().plusSeconds(5));
        }
      }
      case "REGISTRATIONS" -> {
        var ids = store.registrations(c.userId(), c.requestedAt());
        if (ids.isEmpty())
          next(c, store.nextMailbox(c.userId(), c.requestedAt()) ? "REGISTRATIONS" : "OAUTH", 0);
        else {
          var chunk = erasure.recordResources(c, "MAIL_PROOF", ids);
          waitFor(
              c,
              "REG_EXPORT:" + chunk,
              "RESOURCE_EXPORT_PENDING",
              erasure.databaseTime().plusSeconds(5));
        }
      }
      case "OAUTH" -> {
        int n = store.eraseOAuth(c.userId());
        next(c, n == 100 ? "OAUTH" : "REFRESH", n);
      }
      case "REFRESH" -> {
        int n = store.eraseRefresh(c.userId());
        next(c, n == 100 ? "REFRESH" : "SESSIONS", n);
      }
      case "SESSIONS" -> {
        int n = store.eraseSessions(c.userId());
        next(c, n == 100 ? "SESSIONS" : "INVENTORY_READY", n);
      }
      case "INVENTORY_READY" -> {
        var barrier =
            c.requestedAt()
                .plusSeconds(
                    3000); // LOGIN10m -> onboarding10m -> mailbox30m; every completion rechecks
        // expiry.
        if (erasure.databaseTime().isBefore(barrier)) {
          waitFor(c, stage, "ANONYMOUS_PROOF_DRAIN", barrier);
          return;
        }
        if (!erasure.stepDone(c, Step.TECHNICAL)) {
          waitFor(c, stage, "TECHNICAL_DRAIN_PENDING", erasure.databaseTime().plusSeconds(60));
          return;
        }
        store.releaseClaims(c.userId());
        next(c, "MAILBOXES", 0);
      }
      case "MAILBOXES" -> {
        store.eraseMailboxes(c.userId());
        if (store.residualSecrets(c.userId())) next(c, stage, 100);
        else erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "IDENTITY_ABSENCE_V1");
      }
      default -> throw new IllegalStateException("UNKNOWN_IDENTITY_ERASURE_STAGE");
    }
  }

  private void next(Context c, String stage, int count) {
    erasure.advance(c, stage, count, "RUNNABLE", null, null, null);
  }

  private void waitFor(Context c, String stage, String reason, java.time.Instant due) {
    erasure.advance(c, stage, 0, "WAITING_EXTERNAL", reason, due, null);
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
