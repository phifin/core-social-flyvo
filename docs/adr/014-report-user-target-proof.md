# ADR-014 — Profile authorization for user report submission

Status: Proposed, 2026-10-11. Not accepted; no implementation of this dependency.

## Conflict and evidence

The accepted [domain model](../domain-model.md) defines report targets as
post/comment/community/user and requires access to the target at submission.
[Security](../security.md) assigns profile visibility and bilateral-block
authorization to the profile owner policy. The implemented public user surface is
`ProfileQueries.visible(ActorContext, UUID)`; account queries expose account lifecycle
and platform role, not viewer-aware user/profile visibility.

The accepted [architecture](../architecture.md) and ADR-002 permit moderation to
call only content/community/account/platform APIs. `ModuleRules.DEPENDENCIES` enforces
this allowlist. There is no authorized moderation-to-profile seam for USER proof.
Knowing an active account UUID cannot satisfy target access. Relaying profile reads
through content or copying profile/graph policy into moderation would evade ownership.

## Proposed decision

Allow `moderation -> profile.api` solely for `ProfileQueries.visible` when submitting
a USER report. Authorize through the current owner API before recording a bounded
encrypted evidence snapshot. No private profile bypass, graph directory, profile
writes, general staff lookup or foreign SQL is introduced. The same current account/
block/profile policy governs the proof as the ordinary public user surface.

Update the architecture DAG/allowlist and its test-owned map only after acceptance;
test PRIVATE/FRIENDS/AUTHENTICATED/PUBLIC, bilateral blocks, inactive users, IDOR,
idempotent replay and encryption/no-public-evidence paths. Staff evidence remains
restricted to the case's permitted moderation scope and attributable audit.

## Alternative requiring a product scope decision

Explicitly limit Phase 5 report submission to POST/COMMENT/COMMUNITY and defer USER
reports to a later accepted assignment. This changes the package's interpretation
of the domain target contract and must be recorded by main, not silently inferred
by development. Do not advertise unimplemented USER support in OpenAPI.

## Consequences

One narrow acyclic module dependency, using an existing owner API and no library,
schema authority or policy duplication. This proposal does not introduce Phase 8
assignment/filtering/platform suspension/retention operations or Phase 7 consumers.
Until decided, USER report implementation and complete Phase 5/package acceptance
are blocked. Independent content-interaction work can still be verified.
