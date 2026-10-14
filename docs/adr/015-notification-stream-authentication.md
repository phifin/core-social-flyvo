# ADR-015 — Narrow identity proof for notification streams

Status: Accepted for implementation by main, 2026-10-14, with Phases 7–8 activation.
This accepts the narrow design boundary, not an implemented phase.

## Context

ADR-010 requires a stream to close at verified access-token expiry and recheck
current session/account state at heartbeat (revocation target <=15 seconds).
The existing DAG does not permit notification to call identity. ActorContext has
only user/session IDs; AccessTokens.validate validates JWT but discards expiry.
SessionQueries.requireCurrent already owns authoritative session/account checks.
Copying JWT parsing/session SQL into notification or moving business policy into
platform would violate the accepted ownership/security design.

## Decision

Permit `notification -> identity.api` solely for stream authentication lifecycle:
obtaining an immutable verified actor/expiry proof and checking that same current
session via SessionQueries. Identity owns validation and current-state queries;
notification owns bounded stream lifecycle/transport and inbox authorization.
No identity repository/entity/application/infrastructure import, foreign SQL,
credential management, token issuance/refresh/revocation or broad identity lookup.

Expose the verified expiry through a narrow identity-owned API contract. It may
carry trusted validation output from the existing bearer adapter, or perform
validation through that same owner implementation at stream establishment. Do not
trust client-supplied expiry/actor fields or merely decode an unsigned JWT payload.
Preserve ordinary REST principal/session semantics and all JWT validation gates.
The proof is not an authorization cache: subsequent session/account checks remain
current, and source/recipient policy remains notification/owner responsibility.

Reject stream establishment when actual exp has elapsed, even within the existing
JWT clock-skew allowance. Close at exp, on session/account denial or unavailable
required auth state. Recheck heartbeat current status within the documented target;
do not retain raw tokens, credential-bearing Authentication objects in queues,
DB connections or transactions for the stream lifetime. No URL token transport.
Frames contain only invalidations and REST remains authoritative.

## Consequences and verification

This adds one narrow acyclic edge, not an identity-to-notification callback or a
platform business policy. Update the architecture allowlist plus a focused test
that rejects identity calls beyond the accepted stream/session proof contracts.
Add real HTTP expiry/revoke/logout/account-status/DB-outage/backpressure tests and
retain ordinary authentication tests. The exact API names are implementation
choices within this boundary; expanding authority needs another decision.

Revisit only for a new authenticated transport/session model or service extraction.
No change to JWT lifetime, refresh replay, permission caching or production rollout.
