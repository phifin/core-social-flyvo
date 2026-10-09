# Identity and durable-work operations (Phase 1)

Local run instructions are in [README](../README.md). PostgreSQL and Mailpit
images are pinned; Mailpit only captures local/test email. No frontend is bundled.
Use the explicit local profile with `bash ops/local/generate-keys.sh`; keep
`.local/secrets` with the database across restarts. Never use local credentials,
HTTP cookie settings or ephemeral test keys in production. Local Compose's app
may use the developer's non-root APP_UID/APP_GID to read mode-0600 keys; the image
defaults to UID/GID 10001. Ports are localhost-only.

## Configuration

| Environment/property | Meaning |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Required PostgreSQL credentials outside local profile |
| `FRONTEND_ORIGIN` | One exact same-site TLS origin; local default `http://localhost:3000` |
| `AUTH_ISSUER` | Exact JWT issuer; set the real deployed issuer |
| `app.auth.audience` | Exact audience, default `social-core-browser` |
| `AUTH_PRIVATE_KEY_FILE`, `AUTH_PUBLIC_KEY_FILE` | PKCS8 private / X509 public PEM; matching RSA pair, >=2048 bits |
| `AUTH_SIGNING_KEY_ID` | Current JWT key ID; local generator uses 3072-bit RSA |
| `AUTH_RETAINED_PUBLIC_KEYS` | Comma-separated `kid=/mounted/public.pem` verification keys only |
| `MAIL_ENCRYPTION_KEY_FILE`, `LIMITER_KEY_FILE` | Separate base64-encoded 256-bit files outside PostgreSQL |
| `MAIL_ENCRYPTION_KEY_ID` | Current encrypted payload key label |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` | SMTP delivery; default port 1025 is local-oriented |
| `SMTP_STARTTLS` | Default true, required TLS; only explicit local profile disables it |
| `WORKER_ENABLED`, `app.worker.poll-ms` | Worker execution (default true), tick (default 1000 ms) |
| `app.oauth.google.*`, `app.oauth.facebook.*` | Provider settings below; disabled by default |
| `OAUTH_AUTHORIZE_PER_MINUTE` / `app.auth.oauth-authorize-per-minute` | Per-IP OAuth authorize GET bucket capacity/refill per minute; default 10 |
| `OAUTH_CALLBACK_PER_MINUTE` / `app.auth.oauth-callback-per-minute` | Independent per-IP OAuth callback GET bucket capacity/refill per minute; default 20 |

Auth properties are typed/validated; TLS/Origin, key sizes and provider settings
are checked at startup. Ephemeral keys are rejected outside local/test profiles
and are enabled only in test configuration. HTTP request details and framework
HTTP-client wire/debug logging must stay disabled: provider protocol credentials
and mailbox proofs must never reach logs, tracing or proxy access logs. Redact
callback query strings at the edge. Email secrets are placed in frontend fragments;
submit them in POST bodies and remove the fragment after completion.

OAuth budgets must be integers 1–1000 (invalid configuration fails startup).
Each starts full and refills continuously over 60 seconds; both providers share
each budget. Requests over budget get sanitized 429/`RATE_LIMITED` and conservative
`Retry-After: 60` before state insertion, consumption or provider work. Invalid
callbacks spend the same callback budget; a throttled valid state remains usable
until its normal expiry. GET redirects remain exempt from unsafe Origin/CSRF
checks. Health probes do not spend these budgets. Limiter keys use socket remote
IP, not client-supplied forwarded headers; configure trusted proxy/edge protection
before deployment. Shared state is still required before multi-instance public
traffic; per-instance buckets reset on process restart and are not a global quota.
The existing 10,000-key bound fails closed for new keys when full; this is not a
substitute for deployment-edge DDoS protection.

## Provider configuration and release gates

For each enabled provider configure `enabled`, `client-id`, `client-secret` and
`redirect-uri` ending exactly `/api/v1/auth/oauth/{provider}/callback`. Production
URLs must use TLS. Provider endpoints are server configuration, never user input;
loopback HTTP overrides are accepted only for test fixtures. Google defaults to
its authorization/token/JWKS endpoints, openid/email scopes, S256 PKCE and nonce.
No provider tokens are stored after proof verification.

Before Google enablement, register exact redirect origins, consent screen/scopes
and restricted credentials, then run real login/link/reauth/error smoke tests.
Verify issuer, audience/azp, nonce, signing-key rotation and recovery onboarding.
Implementation was checked against [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect)
and [Spring's authorization-code client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html).

Facebook additionally requires explicit `graph-version` and `contract-approved=true`.
That approval means an operator has recorded review of current official Meta
authorization-code/token/debug-token semantics, app-scoped identity, permissions,
app review, deauthorization and data-deletion callback obligations for the actual
app and Graph version. The official [manual flow](https://developers.facebook.com/docs/facebook-login/guides/advanced/manual-flow/),
[token verification](https://developers.facebook.com/docs/graph-api/reference/debug_token/)
and [data deletion](https://developers.facebook.com/docs/development/create-an-app/app-dashboard/data-deletion-callback/)
pages returned HTTP 429 during implementation research on 2026-10-09. Consequently
**real Facebook enablement remains blocked**; deterministic fake-server tests are
not provider approval. No callback obligation is claimed implemented ahead of a
verified contract/full erasure workflow. Test fixtures use a synthetic explicit
version solely to exercise URL version pinning, not certify provider support.

The Facebook adapter verifies app ID, user ID, token validity and both expiries
through debug-token plus user-info. Its token-validation query is private
server-to-provider protocol; never log its URL. It requests only public_profile
and email, permits missing email and uses no assumed Facebook OIDC/PKCE support.

## Browser and account recovery

Access JWTs live only in browser memory. Refresh cookie is host-only, Secure,
HttpOnly, Strict, auth-path scoped. CSRF cookie is host-only/readable/Strict.
OAuth's ten-minute Lax binding is not a session credential. All unsafe API calls
require CSRF plus exact Origin; bearer validation checks current PostgreSQL
session/account and fails 503 on DB loss. Refresh has no grace window and must
be serialized across tabs; replay/lost-response reuse forces login again.
Read the current CSRF cookie for each unsafe call, not a cached bootstrap token.
Authentication can clear/rotate the cookie; fetch `/auth/csrf` again when absent.

Unknown/existing email registration and reset requests return the same 202.
Existing registration gets sign-in guidance; OAuth-only reset creates no local
credential. Reset issuance rechecks current recovery address and local credential
under the account guard; a request racing with email change cannot mail a new
usable proof to the old address. Provider callbacks reject an observed binding
removed/replaced while waiting for the guard (`identity_conflict`); start a new
flow, never restore bindings manually or replay consumed state.
Do not merge/link by email or change unique claims manually to solve
collisions. Lost recovery email or account merging needs a separately approved
operator recovery policy; no public bypass exists. Account deletion denies access
and records a deletion event; full erasure remains Phase 9A. Phase 2 must add its
active-owner blocker through the community API before communities are enabled.

## Worker recovery and retention

One scheduled workload runs per process. Mail claims and acknowledgements are
short transactions around external SMTP, with a 60-second lease, owner/generation
fencing, five-second SMTP connect/read/write timeouts and at most ten attempts.
Backoff starts at five seconds with jitter and caps at one hour. No Redis/broker
or in-memory-only async scheduling is used. Metrics `social.work.queue`,
`social.work.oldest.seconds` and `social.work.failures` expose progress/failure;
protect their operational endpoint before adding a scraper. Alert on DEAD count,
oldest age beyond mailbox expiry and repeated failure growth at the deployment edge.

SMTP may accept before a worker crashes: retries can deliver duplicate mail with
the same deterministic Message-ID. Proofs remain single-use. Expired payloads
are never sent. Completion/expiry/terminal failure deletes encrypted mail content;
DEAD metadata is retained. For DEAD mail, fix SMTP/key/configuration, then request
a **fresh proof** through the normal bounded API. Never replay an expired link or
manufacture a token from a hash. For a DB projection, repair its handler and replay
the same event ID after an attributable operator audit; receipt+effect commit
together and current source versions remain the consumer's responsibility.

Cleanup batches contain at most 500 records/category. Expired registration/OAuth/
onboarding intents remain for 24 hours, one-time proofs seven days, terminal refresh
families through absolute expiry + seven days, completed jobs/events 30 days.
Events with unresolved DEAD deliveries and DEAD jobs are not silently discarded.
Security audit's append-only trigger prevents application updates/deletes; a
controlled retention/migration role handles the proposed 365-day policy before
public launch. Do not disable the trigger in application code.

## Key rotation and production readiness

Mount persistent secrets with runtime UID read access and no source/log inclusion.
For signing rotation: distribute the old public key via retained keys, switch to
a new matching private/public pair and new kid, and retain old verification through
five-minute access lifetime + 30 seconds of skew before retiring it. Tests validate
old/new verification. Never publish private material. The current implementation
loads keys at startup; rotation is a controlled restart.

Mail encryption uses one current AES-GCM key; no online multi-key re-encryption
workflow is claimed. Pause new proof/provider issuance at the deployment edge,
drain workers and allow every old 30-minute proof/ten-minute OAuth flow to expire
before swapping the encryption key. Keep old key backups as required by restore
policy. Coordinate limiter-key rotation with edge limits so issuance budgets
cannot be reset to bypass rate limits. Never rotate by deleting local key files.

Before public launch: production SMTP/domain delivery, TLS/proxy redaction,
mounted-key permissions/backup, Argon2 deployment-hardware tuning (19 MiB, t=2,
p=1 is only the baseline), rate-limit/edge capacity and common-password snapshot
review, restricted runtime DML/audit roles, provider approvals/smokes, operator MFA,
backup/restore and full erasure must be evidenced. No production deployment or
external provider/email traffic was performed for Phase 1.
