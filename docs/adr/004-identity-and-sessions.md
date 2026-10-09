# ADR-004 — Identity, linking and sessions

Status: Accepted, 2026-10-08.

Context: matching emails are not sufficient proof that two login methods belong to the same account. Self-contained JWTs also complicate immediate logout/suspension.

Decision: user account UUID is stable; local credential and multiple provider identities are separate records; profile is social data. Provider identity key is provider/issuer/subject. Never auto-link by email. Require existing-account recent reauthentication plus a fresh bound provider flow. New accounts verify email through our own completion flow; password is chosen at completion to prevent unverified account pre-hijacking.

Use five-minute asymmetric JWTs plus a current DB session/account check on every authenticated request, opaque rotating refresh tokens, seven-day idle/thirty-day absolute family limits and strict replay family revocation. Browser refresh cookie is HttpOnly/Secure with CSRF protection; access tokens stay in memory. Detailed collision cases and provider contracts are frozen in [security](../security.md).

Consequences: indexed session lookup costs a DB read but provides immediate revocation. Lost/concurrent refresh responses can require login again. Provider email changes never move identity bindings. No automatic account merge or insecure recovery fallback. Audit/security notifications cover credential changes.

Revisit token transport for an actual native client, or external authorization-server adoption if operating authentication grows beyond team competence. Do not relax linking or replay semantics for UX convenience without security review.
