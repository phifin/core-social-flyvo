# Agent handoff — Phase 4

## Routing

- Task: phase-4-posts-and-secure-images
- Round: 1
- State: READY_FOR_IMPLEMENTATION
- Last writer: main orchestrator, 2026-10-10
- Next owner: development chat `Develop Phase 4 — Posts & Media` (user opens manually)
- Assignment: user requested checking completed review and continuing the next work.
- Baseline: `552c4ca99692aba277b6fee6bb91b23962d65787`; clean checkout/index before coordination.
- Accepted Phase 3 code: `bfda6e856d42282eaccd2ed70a81f49c416f86b7`.
- Prior verdict/findings/checks: [accepted Phase 3](handoffs/phase-3-social-graph.md).
- Protocol: [thread workflow](thread-workflow.md), [Git workflow](git-workflow.md).

## Scope and implementation instructions

Implement Phase 4 exactly as specified in [roadmap](roadmap.md#phase-4--publishable-posts-with-secure-image-attachments):
personal/community posts, audiences including CUSTOM, mentions/hashtags, revisions
and explicit audience edits, owner soft deletion and basic community remove/restore;
secure image upload/finalize/process/status/bind and avatar lifecycle. Include
ContentAccessPolicy, SQL candidate/hydration building blocks, guarded writes and a
basic paginated post query. This is not a media-only task.

Read AGENTS.md, README.md, implementation-handoff.md, architecture.md, security.md,
domain-model.md, database-design.md, media.md, events-and-jobs.md, API conventions,
testing strategy, and applicable ADRs (especially 002, 003, 005, 008, 009) before
implementation. Inspect existing module APIs/guards/worker jobs and dependency
introduction matrix. Follow the authoritative contracts rather than this summary
where more detailed requirements apply.

Required infrastructure is the specified S3 adapter, pinned Compose MinIO with
private/versioned storage, bounded image processor, quotas and durable cleanup.
No network/storage calls under business locks, arbitrary key signing, unpinned
quarantine reads or public original files. Owner content/profile policy authorizes
variant issuance; media does not call those modules. Keep existing module DAG.
Explain new dependencies and verify storage behavior against real local storage.
Production S3 compatibility remains a distinct deployment gate when not exercised.

No Phase 5 comments/reactions/reports workflow, full home feed, search,
notifications, video/documents/CDN or speculative future modules. Do not silently
change product/security/architecture decisions; raise conflicts with a concrete
proposed ADR change.

## Development plan and delivery

1. Verify baseline/index, set IN_PROGRESS, inspect contracts and record a concrete
   scoped implementation plan before edits. Only this development chat writes code.
2. Implement coherent, reviewable slices; preserve existing migrations, add new
   Flyway migrations and use owner APIs/published allowlisted views only.
3. Test all Phase 4 acceptance/edge cases, especially audience SQL/object parity,
   current history/media authorization, blocked users/community departure,
   version/idempotency conflicts, atomic ten-image binding and concurrent reuse,
   overwrite-after-finalize, stale generation fencing, partial output failure,
   attach/cleanup races, quotas, avatar replacement and metadata stripping.
4. Run targeted real PostgreSQL/storage checks, formatting and full ./mvnw verify;
   report actual operational checks and remaining external gates honestly.
5. Update implemented OpenAPI, schema/domain/subsystem/operations docs and
   docs/phases/phase-4.md. Commit verified task-owned work using git-workflow.md.
6. Set READY_FOR_REVIEW with real code commit, checks, limitations and next owner
   main in this mailbox; commit handoff separately. Do not self-accept or push.

## Reviewer findings and developer responses

No Phase 4 implementation/review yet. No finding IDs or required responses.
Phase 3 was independently ACCEPTED without open findings; preserved in archive.

## Checks and limitations

This is documentation-only orchestration, not Phase 4 implementation.
Confirmed Phase 3 acceptance at baseline and no newer executable changes after
its accepted code. Reviewer reported 124 passing tests; historical evidence, not
rerun in this coordination turn. No Phase 4 tests or readiness claim yet.
External provider/production gates remain. No push, deployment or agent spawning.

## Next action and role boundary

User opens the named development chat and asks it to implement this mailbox.
Main conversation remains orchestrator/reviewer only: prepare prompts, name/reuse
development chats and review delivered code. It must not implement Phase 4 or
fixes itself. Return to main after development commits and sets READY_FOR_REVIEW.
Run one chat at a time in this checkout.
