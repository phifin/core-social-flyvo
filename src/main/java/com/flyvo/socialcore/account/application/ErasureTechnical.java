package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.account.application.port.ErasureStore;
import com.flyvo.socialcore.platform.api.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ErasureTechnical implements ExternalJobHandler {
  private final AccountErasure erasure;
  private final ErasureStore store;
  private final ScopedWork scoped;
  private final ObjectMapper json;

  public ErasureTechnical(
      AccountErasure erasure, ErasureStore store, ScopedWork scoped, ObjectMapper json) {
    this.erasure = erasure;
    this.store = store;
    this.scoped = scoped;
    this.json = json;
  }

  record Prepared(Context context, UUID chunk, List<UUID> proofs, boolean finalPage) {}

  public String kind() {
    return "ERASURE_TECHNICAL";
  }

  public String prepare(String payload, Lease lease) {
    var pass = json.readValue(payload, Pass.class);
    if (pass.step() != Step.TECHNICAL) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    var c = erasure.begin(pass.userId(), Step.TECHNICAL, pass.version());
    if (c == null) return "NOOP";
    var identity = store.step(c.userId(), Step.IDENTITY, false);
    if (!Set.of("INVENTORY_READY", "MAILBOXES", "VERIFIED").contains(identity.checkpoint())
        || store.pendingResources(c.requestId())) throw new Deferred(store.now().plusSeconds(60));
    var after =
        c.checkpoint().startsWith("CHUNK:") ? UUID.fromString(c.checkpoint().substring(6)) : null;
    var resource = store.nextResource(c.requestId(), "MAIL_PROOF", after);
    if (resource != null && !resource.exported()) throw new Deferred(store.now().plusSeconds(60));
    return json.writeValueAsString(
        new Prepared(
            c,
            resource == null ? null : resource.id(),
            resource == null ? List.of() : resource.ids(),
            resource == null));
  }

  public String execute(String prepared) {
    if ("NOOP".equals(prepared)) return "NOOP";
    var p = json.readValue(prepared, Prepared.class);
    return json.writeValueAsString(
        scoped.cancelMail(p.context().userId(), p.proofs(), p.context().requestedAt()));
  }

  public void complete(String payload, Lease lease, String prepared, String result) {
    if ("NOOP".equals(prepared)) return;
    var p = json.readValue(prepared, Prepared.class);
    var c = erasure.begin(p.context().userId(), Step.TECHNICAL, p.context().version());
    if (!p.context().equals(c)) return;
    var drain = json.readValue(result, ScopedWork.Drain.class);
    var now = store.now();
    if (drain.notBefore() != null && now.isBefore(drain.notBefore())) {
      erasure.advance(
          c,
          c.checkpoint(),
          drain.examined(),
          "WAITING_EXTERNAL",
          "LEGACY_MAIL_EXPIRY",
          drain.notBefore(),
          null);
      return;
    }
    if (drain.inFlight()) {
      erasure.advance(
          c,
          c.checkpoint(),
          drain.examined(),
          "WAITING_EXTERNAL",
          "SMTP_DRAIN_UNPROVEN",
          now.plusSeconds(60),
          null);
      return;
    }
    if (drain.remaining()) {
      erasure.advance(c, c.checkpoint(), drain.examined(), "RUNNABLE", null, null, null);
      return;
    }
    if (!p.finalPage()) {
      erasure.advance(c, "CHUNK:" + p.chunk(), drain.examined(), "RUNNABLE", null, null, null);
      return;
    }
    var barrier = c.requestedAt().plusSeconds(1800);
    if (now.isBefore(barrier)) {
      erasure.advance(
          c,
          c.checkpoint(),
          drain.examined(),
          "WAITING_EXTERNAL",
          "LEGACY_MAIL_EXPIRY",
          barrier,
          null);
      return;
    }
    // Separate pass: no target job lock is acquired after this account/step guard.
    if (drain.examined() > 0) {
      erasure.advance(c, c.checkpoint(), drain.examined(), "RUNNABLE", null, null, null);
      return;
    }
    int n = scoped.eraseIdempotency(c.userId());
    if (n == 100) erasure.advance(c, c.checkpoint(), n, "RUNNABLE", null, null, null);
    else erasure.advance(c, "VERIFIED", n, "DONE", null, null, "TECHNICAL_DRAIN_V1");
  }

  public void failed(String payload, Lease lease) {
    erasure.executionFailed(json.readValue(payload, Pass.class), lease.attempt());
  }
}
