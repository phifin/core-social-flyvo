package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import com.flyvo.socialcore.content.api.ContentNotificationSources;
import com.flyvo.socialcore.moderation.application.port.*;
import com.flyvo.socialcore.platform.api.JobHandler;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class ModerationErasure implements JobHandler {
  private final AccountErasure erasure;
  private final AccountQueries accounts;
  private final CaseStore cases;
  private final PreservationStore store;
  private final ModerationPreservation preservation;
  private final ContentNotificationSources content;
  private final ObjectMapper json;

  public ModerationErasure(
      AccountErasure erasure,
      AccountQueries accounts,
      CaseStore cases,
      PreservationStore store,
      ModerationPreservation preservation,
      ContentNotificationSources content,
      ObjectMapper json) {
    this.erasure = erasure;
    this.accounts = accounts;
    this.cases = cases;
    this.store = store;
    this.preservation = preservation;
    this.content = content;
    this.json = json;
  }

  public String kind() {
    return "MODERATION_ERASURE";
  }

  public void apply(String payload) {
    var p = json.readValue(payload, Pass.class);
    if (p.step() != Step.MODERATION) throw new IllegalStateException("ERASURE_OWNER_MISMATCH");
    String stage = erasure.checkpoint(p);
    if (stage == null) return;
    var fields = stage.split(":");
    boolean targets = fields[0].equals("POST") || fields[0].equals("COMMENT");
    UUID target = null;
    if (targets) {
      var ids =
          content.ownedTargets(
              ContentNotificationSources.Type.valueOf(fields[0]),
              p.userId(),
              fields[1].equals("START") ? null : UUID.fromString(fields[1]),
              1);
      if (!ids.isEmpty()) target = ids.getFirst();
    }
    var root =
        targets && target == null
            ? null
            : store.pendingRoot(p.userId(), targets ? fields[0] : null, target);
    accounts.guard(p.userId(), false);
    var c = erasure.begin(p.userId(), Step.MODERATION, p.version());
    if (c == null) return;
    if (root == null) {
      String next =
          !targets
              ? "POST:START"
              : target != null
                  ? fields[0] + ":" + target
                  : fields[0].equals("POST") ? "COMMENT:START" : null;
      if (next == null)
        erasure.advance(c, "VERIFIED", 0, "DONE", null, null, "MODERATION_ABSENCE_V1");
      else erasure.advance(c, next, target == null ? 0 : 1, "RUNNABLE", null, null, null);
      return;
    }
    var control = cases.control(root, true);
    if (control == null) throw new IllegalStateException("MODERATION_ERASURE_ROOT_MISSING");
    var baseline = preservation.baseline(control);
    if (!baseline.ready()) {
      erasure.advance(
          c,
          stage,
          baseline.examined(),
          "WAITING_EXTERNAL",
          "PRESERVATION_EXPORT_PENDING",
          erasure.databaseTime().plusSeconds(60),
          null);
      return;
    }
    if (Set.of("OPEN", "IN_REVIEW").contains(control.status())) {
      erasure.advance(
          c,
          stage,
          0,
          "WAITING_CASE",
          "UNRESOLVED_CASE",
          erasure.databaseTime().plusSeconds(3600),
          null);
      return;
    }
    if (control.holdUntil() != null && control.holdUntil().isAfter(control.now())) {
      erasure.advance(c, stage, 0, "WAITING_HOLD", "MODERATION_HOLD", control.holdUntil(), null);
      return;
    }
    if (!control.retentionState().equals("DONE")) {
      int count = cases.redact(root, 100);
      erasure.advance(
          c,
          stage,
          count,
          count > 0 ? "RUNNABLE" : "WAITING_RETENTION",
          count > 0 ? null : "MODERATION_RETENTION_PENDING",
          count > 0 ? null : erasure.databaseTime().plusSeconds(3600),
          null);
      return;
    }
    // Receipt now covers the current one-way redaction state and all bounded history pages.
    erasure.advance(c, stage, 0, "RUNNABLE", null, null, null);
  }

  public void failed(String payload, int attempts) {
    erasure.executionFailed(json.readValue(payload, Pass.class), attempts);
  }
}
