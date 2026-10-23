package com.flyvo.socialcore.moderation.application.port;

import java.time.Instant;
import java.util.*;

public interface PreservationStore {
  record Export(
      UUID id,
      CaseStore.Root root,
      long version,
      String operation,
      UUID actor,
      UUID community,
      Instant until,
      String record,
      String digest,
      String receipt,
      UUID execution) {}

  record State(
      long version,
      UUID after,
      boolean complete,
      UUID pending,
      UUID pendingAfter,
      boolean pendingComplete) {}

  record History(
      UUID id,
      Long version,
      UUID actor,
      String operation,
      UUID action,
      UUID assignee,
      Instant until) {}

  Export find(UUID id);

  void add(Export export);

  void receipt(UUID id, String digest);

  void failed(UUID id);

  List<Export> pending(int limit);

  void resume(UUID id, UUID execution);

  State state(CaseStore.Root root, long version);

  void pending(CaseStore.Root root, long version, UUID export, UUID after, boolean complete);

  void acknowledge(CaseStore.Root root, long version);

  List<History> history(CaseStore.Root root, UUID after, int limit);

  CaseStore.Root pendingRoot(UUID subject, String targetType, UUID target);

  CaseStore.Root scan(CaseStore.Root after);
}
