# Authentication, authorization and privacy

Security rules here are implementation requirements. Product-specific privacy relaxations require an ADR and regression tests. Anonymous access is supported only for deliberately PUBLIC resources. “Public” means an unauthenticated person can read it; blocking a signed-in user cannot prevent that person from logging out to see PUBLIC material. Use restricted audiences for confidentiality.

## Local authentication

Registration is two-step to prevent unverified-account pre-hijacking. `POST /auth/registrations` takes email, returns a generic 202, and sends a short-lived email verification link bound to a browser transaction. It does **not** preinstall a password or create an active account. Completion takes the verification secret plus a newly chosen password from the mailbox owner and creates account, verified login email, local credential and initial profile atomically. Links expire in 30 minutes; incomplete intents expire without reserving the email. Verification must not make an attacker-supplied password usable just because the victim clicked a link. An existing email receives recovery/sign-in guidance with the same outward 202 behavior; never silently overwrite it.

Use Argon2id through Spring Security's supported encoder and encoded algorithm identifier. Baseline minimum: 19 MiB, two iterations, parallelism one; benchmark deployment hardware, prefer a higher cost within a 200–500 ms login budget and bounded concurrent hashing capacity. Rehash opportunistically on successful login. Passwords: 15–128 Unicode characters, no silent truncation, no forced composition rules, allow paste, reject known compromised/common values using a local maintained list initially. Never log/hash passwords with a general-purpose fast hash. The parameter floor is from [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html); the input policy and timing budget are project decisions.

Login accepts normalized email/password, generic bad-credential response, comparable dummy-hash work for unknown users, and status verification. Password reset request returns generic 202; a single-use random secret (30-minute expiry) resets an existing local credential and revokes every session in the same transaction. Reset cannot add a local credential to an OAuth-only account. Adding a password requires recent authenticated provider reauthentication plus verified recovery email. Email change similarly requires recent reauthentication, proof of the new mailbox, unique email claim, notification to old address, and revocation of other sessions. Do not use security questions.

Perform expensive password hashing/verification outside long database locks, then recheck credential version/account state under the account guard before issuing a session or committing a change. If a reset changed the credential during verification, reject/retry the proof instead of issuing a session from an obsolete password. Link/unlink/password/email mutations take the exclusive account lifecycle guard so simultaneous removal of two login methods cannot leave none.

## Access tokens and refresh families

Phase 1 refinements: changing a password or recovery email also invalidates
outstanding password-reset proofs under the account guard, so an old mailbox
proof cannot regain access after email change. Reset issuance uses its email lookup
only to select the exclusive account guard, then re-reads current recovery email
and local-credential eligibility under that guard. A stale old-address lookup
returns the same generic response without issuing a proof or reset mail.
Adding the first password to an
OAuth-only account requires an explicit completed provider REAUTH transaction,
in addition to the verified recovery email and five-minute freshness check.
The local common-password snapshot and provenance are in
`src/main/resources/security/`; review it during security maintenance.

Use signed JWT access tokens, 5-minute lifetime, asymmetric RS256 initially, library validation with exact algorithm/issuer/audience allowlists and key ID rotation. Claims: `sub` user UUID, `sid` session UUID, `iss`, `aud`, `iat`, `exp`, `jti`; no email, profile data, community memberships or long-lived authorization roles. Sign with a secret-manager-mounted private key, publish/cache only verification keys; keep old verification keys through maximum token lifetime plus clock skew (30 seconds). Do not invent JWT parsing/crypto.

Every authenticated request validates JWT **and current session/account state in PostgreSQL**. The deliberate indexed lookup provides immediate revocation and suspension. A JWT is not authorization and is not treated as irrevocable until expiry. No Redis positive-status cache initially. DB failure fails protected requests closed with 503, not a misleading 401. Public requests with an invalid Authorization header return 401 rather than silently becoming anonymous.

Refresh tokens contain 256 bits of cryptographic randomness; store only SHA-256 hashes (appropriate for random high-entropy secrets). One session is a token family, idle expiry 7 days, absolute expiry 30 days. Rotation locks the session row, verifies the supplied generation, consumes it and issues exactly one successor with an expiry bounded by the family's absolute expiry. Retain hashes of old generations. Reuse of a consumed token revokes the **entire family**, writes audit, commits, then returns 401. This follows the replay-detection approach in [OAuth Security BCP, RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html).

There is no replay grace window initially. Two concurrent refreshes or a lost successful refresh response may force login again; clients must serialize refreshes across tabs (e.g. browser coordination). Security wins over retry convenience here. Do not put refresh requests under generic response-caching idempotency; that would retain refresh secrets. Logout revokes current family and clears cookies; logout-all revokes all families. Revocation of sessions and account state must commit even when returning an authentication failure.

Browser default: access token held only in memory and sent as Bearer; refresh token in a host-only Secure, HttpOnly, SameSite=Strict cookie scoped to `/api/v1/auth`. No Domain attribute and no localStorage tokens. Cookie-authenticated refresh/logout and sensitive auth POSTs enforce CSRF token + trusted Origin checks; SameSite is defense-in-depth. Fetch a CSRF token from `/auth/csrf` with same-origin credentials. A separate short-lived SameSite=Lax browser-binding cookie supports OAuth top-level callbacks; it is not a session credential. OAuth redirects carry no access/refresh tokens. A trusted frontend receives a success marker and uses the refresh endpoint to obtain an access token. Local development may use explicit localhost HTTP cookie settings in the local profile only.

Native clients are deferred; introduce a documented secure-storage/token transport profile when a real native app begins. Do not expose a JSON refresh token based solely on a client-supplied `clientType` flag. Cross-site browser deployment is not the baseline; configure same-site app/API behind TLS and exact CORS origins with no wildcard credentials.

## Google and Facebook login

Implemented provider defaults are disabled. See [provider operations](../ops/identity.md)
for configuration and readiness gates. Public authorize GET starts LOGIN only;
authenticated link-intent/provider-reauth POSTs return `authorizationUrl` for
frontend navigation, with their persisted intent controlling the callback.
An unbound subject receives an encrypted, browser-bound, ten-minute onboarding
capability, never a full user session. It submits an email through registration
and must complete our own mailbox verification without a local password.
The resulting onboarding session requires explicit bound-provider reauthentication
before any action requiring fresh authentication. Mailbox completion and refresh
cannot turn an older provider proof into a fresh one.
All access/refresh secrets remain out of callback redirects.

Use backend authorization-code flow through Spring Security OAuth client facilities; Google OIDC uses PKCE S256 and nonce. Persist a short-lived one-use OAuth transaction bound to provider, intent, browser, and initiating session for linking. Validate state before token exchange, exact redirect allowlist, provider response/error, and trusted transport. OAuth network calls happen outside business transactions with strict timeout and no token logging.

Google: validate ID token signature against provider keys, issuer, audience, expiry, nonce, and authorized party where applicable. Use `(issuer,sub)` as identity; email can change. [Google's OIDC documentation](https://developers.google.com/identity/openid-connect/openid-connect) describes subject identifiers and token validation.

Facebook: use its supported server-side authorization-code flow and validate the returned credential belongs to the configured application and returned provider user. Scope identity by configured Facebook app/issuer plus app-scoped subject. Do not assume a generic OIDC ID token or an email claim always exists. Use PKCE only where the selected provider flow supports it; state/browser binding and a backend-held client secret remain mandatory. Pin Graph API version, validate app/user/expiry using the supported provider verification endpoint, request minimum profile/email scopes, discard provider tokens after identity verification. No posting to provider accounts is in scope.

The Meta documentation endpoint could not be fetched during blueprint research. Phase 1 therefore has an explicit provider-contract gate: verify current official Meta flow, token validation, permissions, app-review requirements and deauthorization/data-deletion callbacks before enabling real Facebook login. Mock tests alone cannot claim provider readiness. Provider registration/credentials are environment setup, not permission to weaken validation.

## Identity collision and linking policy

**No automatic linking by email, even a provider-verified email.** Email is a hint for recovery UX; proof of an already trusted login method plus a fresh provider proof authorizes linking. The following cases are frozen:

| Scenario | Required behavior |
|---|---|
| Local Alice later signs in with Google using same email | No new account, no login as Alice and no silent link. Show generic “sign in to your existing account to connect this provider”; Alice signs in locally, reauthenticates, then starts LINK flow |
| Provider identity already bound | Login existing user by provider subject; ignore changed provider email for account selection. Suspended/deleted user remains blocked |
| New provider identity, email not claimed | Create a pending registration intent; confirm a recovery email through our own verification before creating ACTIVE account/profile/identity. Provider email can prefill, never bypass our completion transaction |
| Provider supplies no email | Request an email in onboarding and verify it; no fabricated email or dummy credential. Limited onboarding transaction is not a full user session |
| Matching unverified registration intent | Intent is not an account or trusted credential; never merge/link it. Complete a fresh proof-bound flow; unique email claim arbitrates races |
| Provider identity belongs to another existing user | Reject linking with a safe conflict, audit; do not move it or merge accounts automatically |
| Two simultaneous link/registration callbacks | Unique provider-subject/email constraints choose one committed result; loser gets conflict/retry guidance, no partial account |
| Google/Facebook callback changes LOGIN to LINK in parameters | Ignore user-supplied intent; server transaction intent/session binding controls behavior |
| Last usable login method unlinked | Reject. Keep credential/provider required to authenticate; verified email by itself is insufficient |
| Recovery email lost / two established accounts need merging | No automatic merge feature. Document operator-assisted recovery policy before offering it |

LINK requires authentication within the last 5 minutes via password/provider reauthentication, not merely a recently refreshed token; bind fresh authentication time to the server-side session. Keep old sessions independent of linking; notify the account of added/removed methods. A forged callback cannot attach an attacker's identity to a victim's ambient session.

Callbacks re-read the provider/issuer/subject binding after the account guard.
LOGIN requires the same binding ID and owner as the pre-guard observation;
LINK/REAUTH also reject a previously observed binding that was removed or replaced.
Return `identity_conflict` and require a new flow rather than granting from stale
facts or following a reassigned owner with another account lock. LINK's changed-
binding rejection is audited. Unlink therefore serializes with authentication,
without holding business locks during the provider exchange.

## Authorization placement

Transport extracts a minimal `ActorContext` (user ID/session ID or anonymous) and validates syntax. Application commands call named resource policies after loading minimal authoritative facts; the domain enforces intrinsic transitions. The actor is never accepted from JSON. Module APIs accept actor context explicitly for user-facing operations; background/system capability methods are separate narrow contracts, not a magical `isSystem=true` bypass.

No universal “policy engine” or one enormous permissions service. `ContentAccessPolicy` composes account, graph and community facts; `CommunityPermissionPolicy` owns role hierarchy; `ProfileAccessPolicy` owns profile scopes. Shared fact APIs are batched. A single documented decision table drives both object checks and SQL filtering; parity/property tests keep them aligned.

Content read evaluation, in order:

1. Resource exists and is currently PUBLISHED; author account is ACTIVE. Parent post must pass for comments/reactions/media. Otherwise inaccessible.
2. Signed-in viewer account/session is active. Any block between viewer and author denies ordinary access, even if in the same community. For comment/reaction attribution, also check that actor. Mute is not a deny rule.
3. Community posts: PUBLIC community permits read; PRIVATE requires current ACTIVE, unsuspended membership. Banned/suspended members are denied community access even when signed in to a PUBLIC community; anonymous public visibility limitation still applies. Author exemption never bypasses a private community boundary. Archived communities are read-only with the same visibility rules.
4. Personal posts: author allowed; PUBLIC anyone; FOLLOWERS if viewer follows author; FRIENDS if canonical friendship exists; CUSTOM if explicit recipient; ONLY_ME author only. Anonymous never qualifies for a relationship scope.
5. Projection/snippet/media/count hydration must use the same decision and source status. Unknown audience or unavailable required facts denies access.

Edits/delete additionally require ownership, except moderation endpoints with explicit scope and reason. A post author who left a private community can request deletion through an owner-only delete command without receiving the content body; this privacy-preserving exception does not allow read/edit. Community moderation never permits rewriting an author's body. API returns 404 for inaccessible object reads to avoid existence leaks, 403 for a visible resource with a forbidden action, 401 for missing/invalid required authentication.

## Community permission matrix

All roles must have effective ACTIVE, unsuspended membership; archived communities deny writes except the domain's explicit self-leave allowance (including retained owner). Higher roles include lower permissions unless excluded below.

| Action | MEMBER | MODERATOR | ADMIN | OWNER |
|---|---|---|---|---|
| Read private content, publish/comment/react | Yes | Yes | Yes | Yes |
| Report, edit/delete own content, leave | Yes | Yes | Yes | Cannot leave before transfer/archive |
| Review reports, remove/restore community content | No | Yes | Yes | Yes |
| Suspend/ban/remove ordinary member | No | Yes | Yes | Yes |
| Invite / approve join request | No | No | Yes | Yes |
| Edit community settings / revoke invites | No | No | Yes | Yes |
| Promote/demote MODERATOR | No | No | Yes, ordinary members only | Yes |
| Promote/demote ADMIN | No | No | No | Yes |
| Transfer owner / archive | No | No | No | Yes |

Staff cannot sanction self, owner, or equal/higher effective role. Only owner can act on ADMIN; ADMIN can act on MODERATOR. Platform ADMIN uses separate audited endpoints to suspend accounts and handle otherwise unmoderatable owner abuse. Bootstrap platform admins out of band; no public endpoint grants platform roles. Before public deployment require operator access controls and MFA at the administrative gateway; ordinary consumer MFA is a later feature.

## Surfaces affected by privacy changes

Phase 2 implements community authority through exclusive transition guards and
transaction-required shared write guards. Discovery cards grant no private access;
member history requires current effective membership, request history requires
ADMIN/OWNER, invitation response requires invitee or scoped staff. Membership
sanctions commit with attributable moderation.action/scoped audit/outbox; reason
and snapshots are private and absent from public responses/events. No ordinary
platform ADMIN bypass or grant endpoint is added. Account closure takes its
exclusive account guard before checking active community ownership, serialized
against creation/transfer's shared owner account guard. See [community](community.md).

| Surface | Rule |
|---|---|
| Home feed | Hide blocked authors, muted authors, deleted/removed content; final source check |
| Community feed / timeline | Block and visibility enforced; mute hides home only, direct timeline/community remains readable |
| Search | Filter profiles/communities/posts before returning snippets, totals or highlights; no hidden-result counts |
| Comments | Blocked/deleted roots become tombstones only when visible children need context; suppress identity/text; replies still need post visibility |
| Notifications | Drop self, blocked, muted or no-longer-authorized social activity; filter actors/targets at read time; no stale content text in SSE |
| Media | Issue URL only via currently authorized post/profile; max 60-second capability lifetime; previously downloaded bytes cannot be recalled |
| Lists/counts | Do not expose blocked actor names, graph edges or exact hidden-result counts through pagination metadata |

Normal reads authorize against committed state near request time, not a global serializable timeline. A concurrent block/removal may race with an already-authorized response; subsequent requests must reflect it. Guarded writes serialize the relevant conflicting state. Do not promise revocation of in-flight responses or already issued storage URLs.

## Abuse controls and secrets

Phase 1 uses bounded per-instance token buckets and deployment-edge limits, plus DB-backed per-email verification/reset issuance windows. Initial limits: login 10/min/IP and 5/min/account key; registration/reset email 3/hour/address and 20/hour/IP; API writes 60/min/user; uploads 10/min/user with storage quotas; search 30/min/user. These are tunable defaults, not account lockouts an attacker can trigger indefinitely. Hash normalized emails in limiter keys. Trust forwarded IP headers only from configured proxies. Introduce shared Redis limits before multi-instance public traffic.

Public OAuth GETs have separate per-IP budgets: authorize 10/minute and callback
20/minute, shared across Google/Facebook. These run before state insertion or
callback state consumption/provider exchange, independently of unsafe-method
Origin/CSRF checks; top-level callbacks need neither Origin nor CSRF. Invalid
callback attempts also spend the budget. Route matching uses decoded framework
paths, not raw-URI string comparisons. Exhaustion returns `RATE_LIMITED` Problem
Details, 429 and `Retry-After: 60`. Buckets start full, refill continuously, retain
partial refill on denial, and share the existing 10,000-key per-instance bound.
Properties/environment overrides and deployment limitations are in
[identity operations](../ops/identity.md).

Explicit allowlisted request DTOs prevent mass assignment; ignore/reject unexpected privilege fields consistently. Set request/body/upload limits, database statement timeouts and provider timeouts. Parameterize SQL. Return plain text; rich HTML remains disabled. No remote URL downloads without SSRF review. Store secrets in environment/secret manager, never git/logs/events. Redact Authorization, cookies, reset codes, provider tokens, passwords, raw emails and presigned URL query strings. Log reason codes and correlation IDs instead. CORS, CSRF, object-level denial, rate-limit bypass, logout races and malicious upload cases are required tests.
