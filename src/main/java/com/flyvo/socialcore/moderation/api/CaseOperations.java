package com.flyvo.socialcore.moderation.api;

import com.flyvo.socialcore.account.api.AccountCommands;
import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operations on existing moderation reports/actions, never a second case system. */
public interface CaseOperations {
  enum Root {
    REPORT,
    ACTION
  }

  enum AccountAction {
    SUSPEND_ACCOUNT,
    REINSTATE_ACCOUNT
  }

  enum Maintenance {
    ENCRYPT,
    RETENTION,
    PRESERVATION
  }

  record Filter(String status, UUID assignedTo, boolean unassigned) {}

  record CaseView(
      UUID id,
      Reports.Target targetType,
      UUID targetId,
      UUID communityId,
      Reports.Category category,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      long version,
      UUID assignedTo,
      Instant holdUntil,
      String holdReason,
      String retentionState) {
    public CaseView(
        Reports.View r,
        UUID assignedTo,
        Instant holdUntil,
        String holdReason,
        String retentionState) {
      this(
          r.id(),
          r.targetType(),
          r.targetId(),
          r.communityId(),
          r.category(),
          r.status(),
          r.createdAt(),
          r.resolvedAt(),
          r.version(),
          assignedTo,
          holdUntil,
          holdReason,
          retentionState);
    }
  }

  record Page(List<CaseView> items, String nextCursor) {
    public Page(List<CaseView> items) {
      this(items, null);
    }
  }

  record Hold(Instant expiresAt, String reasonCode) {}

  record Control(
      Root type,
      UUID id,
      long version,
      Instant holdUntil,
      String holdReason,
      String retentionState) {}

  record HistoryEntry(
      UUID id,
      Long caseVersion,
      UUID actorId,
      String operation,
      UUID actionId,
      UUID assignedTo,
      Instant holdUntil,
      Instant createdAt,
      boolean redacted,
      String reason,
      String beforeState,
      String afterState) {}

  record History(List<HistoryEntry> items) {}

  record ActionView(
      UUID id,
      UUID reportId,
      UUID actorId,
      UUID communityId,
      String targetType,
      UUID targetId,
      String action,
      Instant createdAt,
      boolean redacted,
      String reason,
      String beforeState,
      String afterState,
      Control control) {}

  record AccountRequest(UUID userId, AccountAction action, String reasonCode) {}

  record AccountResult(UUID actionId, AccountCommands.Change account) {}

  record AccountState(UUID userId, String status, String platformRole, long version) {}

  record Progress(
      long legacyPayloads,
      long pendingCaseRoots,
      long pendingActionRoots,
      long pendingPreservationExports,
      long preservationFailures) {
    public Progress(long legacyPayloads, long pendingCaseRoots, long pendingActionRoots) {
      this(legacyPayloads, pendingCaseRoots, pendingActionRoots, 0, 0);
    }
  }

  Page queue(ActorContext actor, UUID community, Filter filter, int size, int offset);

  CaseView assign(ActorContext actor, UUID community, UUID report, long expected, UUID assignee);

  Control hold(ActorContext actor, UUID community, Root type, UUID id, long expected, Hold hold);

  History history(ActorContext actor, UUID community, UUID report, int size, int offset);

  ActionView action(ActorContext actor, UUID community, UUID action);

  AccountResult account(ActorContext actor, long expected, AccountRequest request);

  AccountState accountState(ActorContext actor, UUID user);

  Progress maintenance(ActorContext actor, Maintenance operation, boolean resume);

  void authorizeMetrics(ActorContext actor);
}
