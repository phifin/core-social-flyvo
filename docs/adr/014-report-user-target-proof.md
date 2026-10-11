# ADR-014 — Profile authorization for user report submission

Status: Accepted for implementation by main, 2026-10-11 (P56-B1).
Implementation is delivered in the [Phase 5 record](../phases/phase-5.md);
phase/package acceptance remains pending main review.

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

## Decision

Allow `moderation -> profile.api` solely for `ProfileQueries.visible` when submitting
a USER report. Authorize through the current owner API before recording a bounded
encrypted evidence snapshot. No private profile bypass, graph directory, profile
writes, general staff lookup or foreign SQL is introduced. The same current account/
block/profile policy governs the proof as the ordinary public user surface.

The architecture DAG now authorizes this edge; development must update the test-owned
map with the report implementation, without exemptions or unrelated new edges;
test PRIVATE/FRIENDS/AUTHENTICATED/PUBLIC, bilateral blocks, inactive users, IDOR,
idempotent replay and encryption/no-public-evidence paths. Staff evidence remains
restricted to the case's permitted moderation scope and attributable audit.

Use only the owner-authorized returned summary for the evidence snapshot; do not
authorize first and then reload a newer body through another path. Recheck inside
the report command transaction, not only in the controller or before idempotency.
Document and test the ordering against profile privacy, block and account changes;
if a guarded owner API is necessary, keep it in profile.api and raise any additional
dependency change rather than importing graph or profile internals into moderation.

USER reports do not acquire a community scope from caller input. Community staff
membership alone cannot grant access to these global cases; global case handling
requires current platform ADMIN through explicit audited case endpoints. This does
not authorize account suspension (Phase 8) or a general profile lookup bypass.

## Alternative not selected

Explicitly limit Phase 5 report submission to POST/COMMENT/COMMUNITY and defer USER
reports to a later accepted assignment. This changes the package's interpretation
of the domain target contract and must be recorded by main, not silently inferred
by development. Do not advertise unimplemented USER support in OpenAPI.

## Consequences

One narrow acyclic module dependency, using an existing owner API and no library,
schema authority or policy duplication. This proposal does not introduce Phase 8
assignment/filtering/platform suspension/retention operations or Phase 7 consumers.
P56-B1's dependency decision is unblocked. Keep all four report target types;
development continues reports, the full Phase 5 gate, then Phase 6 in the same
assignment. This ADR refines ADR-002's dependency allowlist only; ownership and
published-SQL restrictions remain unchanged. Revisit before broadening the profile
API usage, staff scope or supported actions.
