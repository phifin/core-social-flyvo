package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.content.api.ContentReportProof;
import com.flyvo.socialcore.moderation.application.port.CaseStore;
import com.flyvo.socialcore.platform.api.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Complete structural account set before erasure steps, community or moderation roots. */
@Component
public class ModerationSubjects {
  private final AccountQueries accounts;
  private final AccountErasure erasure;
  private final CaseStore cases;
  private final ContentReportProof content;

  public ModerationSubjects(
      AccountQueries accounts,
      AccountErasure erasure,
      CaseStore cases,
      ContentReportProof content) {
    this.accounts = accounts;
    this.erasure = erasure;
    this.cases = cases;
    this.content = content;
  }

  private List<UUID> guard(ActorContext actor, List<UUID> ids, boolean existing) {
    if (actor == null || !actor.authenticated()) throw Failure.unauthorized();
    var complete =
        java.util.stream.Stream.concat(java.util.stream.Stream.of(actor.userId()), ids.stream())
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    for (var id : complete) {
      var a = accounts.guard(id, false);
      if (a == null) throw new Failure(404, "NOT_FOUND");
      if (id.equals(actor.userId()) && !a.active()) throw Failure.unauthorized();
      if ("DELETED".equals(a.status()) || !existing && "DELETION_PENDING".equals(a.status()))
        throw new Failure(409, "TERMINAL_SUBJECT");
    }
    if (existing) erasure.invalidateModeration(complete);
    return complete;
  }

  public void existing(ActorContext actor, CaseStore.Root root) {
    var first = cases.subjects(root);
    guard(actor, first, true);
    if (!first.equals(cases.subjects(root))) throw new Failure(409, "CASE_SUBJECTS_CHANGED");
  }

  public void member(ActorContext actor, UUID target) {
    guard(actor, List.of(target), false);
  }

  public void post(ActorContext actor, UUID target) {
    guard(actor, content.structuralUsers(target), false);
  }
}
