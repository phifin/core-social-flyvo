package com.flyvo.socialcore.account.application.port;

import com.flyvo.socialcore.account.api.AccountErasure.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErasureStore {
  record Intent(UUID id, UUID user, long expectedVersion, Instant createdAt, UUID lineage) {
    public boolean matches(Request request) {
      return request != null
          && id.equals(request.id())
          && user.equals(request.user())
          && expectedVersion + 1 == request.generation();
    }
  }

  record Request(
      UUID id,
      UUID user,
      Instant at,
      long generation,
      long round,
      long version,
      String manifestDigest) {}

  record State(
      StepView view,
      String checkpoint,
      long examined,
      String proof,
      Long proofVersion,
      long round,
      UUID execution) {}

  record Export(
      UUID id,
      UUID intent,
      String operation,
      String record,
      String digest,
      String receipt,
      UUID execution) {}

  Instant now();

  Intent intent(UUID id);

  Intent prepare(UUID user, long expected, UUID lineage);

  Request request(UUID user);

  boolean current(UUID request, String rosterDigest);

  void completionAttempt(Context context, UUID attempt);

  void createRequest(Intent intent, Instant at, long generation, String rosterDigest);

  void seed(UUID user, UUID request, Step step, String status, String reason);

  State step(UUID user, Step step, boolean lock);

  void advance(
      Context context,
      String checkpoint,
      int examined,
      String status,
      String reason,
      Instant due,
      String proof,
      UUID execution);

  void failed(UUID user, Step step, boolean dead);

  void invalidate(UUID user, Step step);

  List<UUID> scan(UUID after, int limit);

  Export export(UUID id);

  Export export(UUID intent, String operation);

  UUID addExport(
      UUID id,
      UUID intent,
      String operation,
      String owner,
      String kind,
      List<UUID> resources,
      String record,
      String digest);

  void receipt(UUID id, String digest);

  void exportFailed(UUID id);

  java.util.Map<String, Long> resourceFailures(UUID intent);

  boolean pendingResources(UUID intent);

  boolean pendingResources(UUID intent, String owner);

  List<Export> pending(UUID intent);

  record Resource(UUID id, String owner, String kind, List<UUID> ids, boolean exported) {}

  Resource nextResource(UUID request, String kind, UUID after);

  Resource resource(UUID chunk);

  void resumeExport(UUID id, UUID execution);

  String lockManifest(UUID request);

  void manifest(UUID request, String digest);

  boolean issue(UUID user, String reason);

  StepView issue(UUID user);
}
