package com.flyvo.socialcore.account.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner maintenance protocol, never a user impersonation or owner callback. */
public interface AccountErasure {
  String PROTOCOL = "erasure-v1";
  String POLICY = "isolated-synthetic-v1";

  enum Step {
    RECOVERY_INTENT("account", "recovery-intent", "ERASURE_EXPORT"),
    IDENTITY("identity", "credentials", "IDENTITY_ERASURE"),
    PROFILE("profile", "social", "PROFILE_ERASURE"),
    COMMUNITY("community", "participation", "COMMUNITY_ERASURE"),
    GRAPH("graph", "relations", "GRAPH_ERASURE"),
    CONTENT("content", "authored-and-references", "CONTENT_ERASURE"),
    MODERATION("moderation", "private-payloads", "MODERATION_ERASURE"),
    TECHNICAL("account", "technical", "ERASURE_TECHNICAL"),
    MEDIA("media", "objects", "MEDIA_ERASURE"),
    NOTIFICATION("notification", "derived", "NOTIFICATION_ERASURE"),
    SEARCH("search", "derived", "SEARCH_ERASURE"),
    COMPLETION("account", "completion-manifest", "ERASURE_COMPLETE");

    private final String owner;
    private final String name;
    private final String kind;

    Step(String owner, String name, String kind) {
      this.owner = owner;
      this.name = name;
      this.kind = kind;
    }

    public String owner() {
      return owner;
    }

    public String step() {
      return name;
    }

    public String kind() {
      return kind;
    }
  }

  record Context(
      UUID userId,
      UUID requestId,
      Step step,
      long version,
      long round,
      Instant requestedAt,
      String checkpoint,
      long examined) {}

  record StepView(
      Step step,
      String status,
      String reason,
      Instant nextCheckAt,
      long version,
      long failures,
      Instant updatedAt) {}

  record Progress(
      UUID userId,
      UUID requestId,
      String accountStatus,
      String protocol,
      String policy,
      long version,
      List<StepView> steps) {}

  record Pass(UUID userId, Step step, long version, UUID executionId) {}

  /** All begin/advance/resource calls join the worker's fenced DB transaction. */
  Context begin(UUID user, Step step, long expectedVersion);

  /** Nonlocking stage observation for pre-reading the complete owner account guard set. */
  String checkpoint(Pass pass);

  void advance(
      Context context,
      String checkpoint,
      int examined,
      String status,
      String reason,
      Instant nextCheckAt,
      String proof);

  UUID recordResources(Context context, String kind, List<UUID> ids);

  boolean resourcesExported(Context context, UUID chunk);

  List<UUID> resourceIds(Context context, UUID chunk);

  boolean stepDone(Context context, Step step);

  Instant databaseTime();

  /** Existing-case private append; caller holds all sorted subject account guards first. */
  void invalidateModeration(List<UUID> subjects);

  void executionFailed(Pass pass, int attempts);

  List<UUID> scanSubjects(UUID after, int limit);

  /** Prepare is transaction-required; export must run outside any transaction. */
  UUID prepare(UUID user, long expectedVersion);

  void exportPrepared(UUID intent);

  Progress progress(ActorContext actor, UUID user);

  Progress resume(ActorContext actor, UUID user, long expectedVersion, Step step);

  /**
   * Validate an eleven-proof vector; finalization additionally needs the fenced archive receipt.
   */
  void requireCompletion(UUID user, String prerequisiteVectorDigest);
}
