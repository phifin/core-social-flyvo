package com.flyvo.socialcore.moderation.application.port;

import com.flyvo.socialcore.moderation.api.CaseOperations;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CaseStore {
  record Root(CaseOperations.Root type, UUID id) {}

  enum EligibilityState {
    KNOWN,
    UNRESOLVED,
    UNKNOWN
  }

  enum EligibilityAnchor {
    REPORT_RESOLVED_AT,
    ACTION_CREATED_AT
  }

  /** Source age provenance, independent of export time and of the separate current hold. */
  record Eligibility(
      EligibilityState state, EligibilityAnchor anchor, Instant anchorAt, Instant eligibleAt) {}

  record Control(
      Root root,
      UUID community,
      UUID author,
      UUID parentAuthor,
      long version,
      UUID assignedTo,
      String status,
      Instant holdUntil,
      String holdReason,
      String retentionState,
      Eligibility eligibility,
      Instant now) {}

  record Payload(
      UUID id, UUID reportId, String legacy, byte[] ciphertext, String keyId, Instant redactedAt) {
    public Payload {
      ciphertext = ciphertext == null ? null : ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext == null ? null : ciphertext.clone();
    }

    @Override
    public String toString() {
      return "ModerationPayload[redacted]";
    }
  }

  record Action(
      UUID id,
      UUID reportId,
      UUID actor,
      UUID community,
      String targetType,
      UUID target,
      String operation,
      Instant created,
      Payload payload) {}

  record History(
      UUID id,
      Long version,
      UUID actor,
      String operation,
      UUID actionId,
      UUID assignee,
      Instant holdUntil,
      Instant created,
      Payload payload) {}

  Control control(Root root, boolean lock);

  List<UUID> subjects(Root root);

  List<CaseOperations.CaseView> queue(
      UUID community, CaseOperations.Filter filter, int size, int offset);

  CaseOperations.CaseView caseView(UUID id);

  void assign(UUID report, long expected, UUID assignee);

  void hold(Root root, long expected, Instant until, String reason);

  void history(UUID report, UUID actor, String operation, UUID actionId);

  List<History> history(UUID report, int size, int offset);

  Action action(UUID id);

  Payload payload(UUID action);

  Root legacyRoot();

  List<Payload> legacy(Root root, int limit);

  void encrypted(UUID action, byte[] ciphertext, String keyId);

  Root retentionRoot();

  int redact(Root root, int limit);

  CaseOperations.Progress progress();
}
