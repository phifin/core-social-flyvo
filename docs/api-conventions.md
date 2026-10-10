# REST direction and contract conventions

Version business endpoints under `/api/v1`, lowercase plural nouns, UUID paths, camelCase JSON. OAuth callbacks use the same auth prefix. Actuator health is operational and outside the public versioned API. Controllers translate HTTP to application commands; they do not perform repository access or privacy decisions. Maintain `docs/api/openapi.yaml` as the implemented contract, expanding it per phase rather than advertising unimplemented endpoints.

## Representation and errors

Return resource DTOs directly. Collections use `{ "items": [...], "nextCursor": "..." }`; there is no universal success envelope. 201 + Location for create, 200 for representation/update, 204 for successful idempotent delete, 202 + status resource for asynchronous upload/registration. UTC timestamps are ISO-8601 strings with `Z`; UUIDs are strings, enums stable uppercase codes, omitted vs null semantics documented. Account emails are only in self/account DTOs, never public profiles.

Errors use `application/problem+json` with RFC 9457-style `type`, `title`, `status`, `detail`, `instance`, plus stable `code`, `requestId`, optional `violations:[{field,code,message}]`. Example:

```json
{
  "type": "urn:social-core:problem:stale-version",
  "title": "Resource changed",
  "status": 412,
  "detail": "Reload the resource before editing it.",
  "instance": "/api/v1/posts/5e102a14-9fd5-4852-bcf0-a1bcfa78b940",
  "code": "STALE_VERSION",
  "requestId": "ad67059a-2b10-44a9-9c58-b76259eb2a36"
}
```

Use 400 for malformed input/validation/cursor; 401 authentication; 403 visible-but-forbidden action; 404 absent/inaccessible resource; 409 conflicting lifecycle/unique state/idempotency body mismatch; 412 stale `If-Match`; 413 request/upload too large; 415 unsupported media; 428 missing required precondition; 429 + Retry-After throttled; 503 temporarily unavailable required dependency. Translate centrally, including Security filter errors. No SQL, stack traces, token material or existence hints in errors. Provider/internal errors map to stable public codes. The protocol follows [Problem Details, RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).

GET returns an ETag derived from resource version. PATCH/DELETE on mutable owned aggregates require `If-Match` (community settings, profile, post/comment edits, role changes). Set-style graph/reaction PUT/DELETE and logout do not require an ETag; repeats are idempotent. Use purpose-specific commands for accept/transfer/approve rather than arbitrary writable status enums.

For resource-creating authenticated POSTs (posts, comments, invitations, reports, upload intents), support `Idempotency-Key`: actor + normalized route + key hash, request hash and response reference, 24h retention. Same key/body returns original logical result, after current authorization; differing body ->409. Insert record and business mutation in one transaction. Avoid replaying a cached private body after permissions change. Generic idempotency does not apply to auth secrets or replace unique domain constraints. Clients can retry graph/reaction PUT/DELETE without a key.

## Phase 1 implemented surface

Phase 1 implements the auth/identity and initial profile routes in OpenAPI.
Its small own-session list uses stable `(created_at DESC,id DESC)` ordering with
`size` 1–100 and `offset` 0–10,000, without a total. Profile/preferences and own
account reads return version ETags; profile/preferences PATCH and account DELETE
require a strong quoted `If-Match`. All unsafe Phase 1 API calls use CSRF and
exact Origin, even with a Bearer. Unknown JSON fields are rejected consistently.
Clients read the current CSRF cookie for each call, fetching `/auth/csrf`
again when authentication has cleared/rotated it; never cache an old token.
API bodies, including chunked bodies, are capped at 16 KiB. Provider link/reauth
start returns a URL in JSON; callbacks redirect with a marker, never credentials.

## Pagination

Phase 2's implemented community routes are specified in OpenAPI and
[community subsystem](community.md). Community ETag covers settings/ownership/archive
and effective user transitions; membership ETag covers role/removal/leave/sanctions.
Purpose-specific admission responses are idempotent without an ETag after current
actor/scope checks. Community/invitation/request creation accepts optional
Idempotency-Key with 24-hour logical-reference retention and changed-body 409.
Small community/member/request/invitation lists use bounded explicit size/offset,
no totals, nextCursor null; they do not claim signed keyset or snapshot pagination.

Feeds/timelines/posts/notifications/comments use signed opaque keyset cursors, default limit 20, max 100 (feed max 50). Cursor includes version, query scope/filter hash, viewer binding, ordering fields, asOf cutoff and expiry; HMAC validates integrity, not secrecy. Do not put sensitive email/content in cursors. A changed viewer/filter rejects with 400; expired feed cursor asks client to refresh. No client-controlled SQL sort fields beyond an allowlist.

Chronological feeds sort `(publishedAt DESC,id DESC)`; comments sort `(createdAt ASC,id ASC)` independently for roots and replies. New rows after `asOf` wait until refresh. Filtering may produce short or empty pages with a non-null nextCursor; advance by last scanned candidate, not last returned visible item. `nextCursor=null` means the candidate range is exhausted, not merely this batch was empty. No exact total for filtered feeds/search. Small administration/member lists may use page/size (size <=100, maximum offset 10,000) with a stable tie-break and privileged total only when safe.

## Major resource surface

Paths below omit `/api/v1`. These establish direction; exact request field examples are added during the corresponding phase and must preserve policy.

| Capability | Routes | Main authorization/behavior |
|---|---|---|
| Registration / recovery | `POST /auth/registrations`, `POST /auth/registrations/complete`, `POST /auth/password-resets`, `POST /auth/password-resets/complete` | Generic initiation; one-use completion proof; no account enumeration |
| Sessions | `GET /auth/csrf`, `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/logout-all`; `GET /auth/sessions`, `DELETE /auth/sessions/{id}` | Cookie/CSRF rules for refresh, own sessions only; immediate revocation |
| Own identity | `GET /me/account`, `PUT /me/password`, `POST /me/email-changes`, `/me/email-changes/complete`; `GET /me/identities`, `DELETE /me/identities/{id}` | Recent reauth for changes; no last-method unlink |
| OAuth | `GET /auth/oauth/{provider}/authorize`, `GET /auth/oauth/{provider}/callback`; `POST /me/identities/{provider}/link-intents`; `POST /auth/reauthenticate` | Only configured Google/Facebook; server intent bound to browser/session. Reauth starts password or provider-specific proof flow |
| Profile / lifecycle | `GET /users/{id}/profile`, `GET/PATCH /me/profile`, `GET/PATCH /me/preferences`, `DELETE /me/account` | Viewer-aware profile; own write; account deletion after recent reauth and owner-transfer check |
| Communities | `POST/GET /communities`, `GET/PATCH /communities/{id}`, `POST /communities/{id}/archive`, `POST /communities/{id}/ownership-transfers` | Minimal discovery vs private details; owner transfer is atomic |
| Membership | `GET /communities/{id}/members`, `PUT/DELETE /communities/{id}/members/me`, `PATCH /communities/{id}/members/{userId}/role`, `DELETE /communities/{id}/members/{userId}` | Open join only via PUT; explicit staff permission; roles never from signup |
| Join requests | `POST /communities/{id}/join-requests`, `GET /communities/{id}/join-requests`, `POST /join-requests/{id}/approve`, `/reject`, `/cancel` | Requester or scoped staff according to transition |
| Invitations | `POST /communities/{id}/invitations`, `GET /me/community-invitations`, `POST /community-invitations/{id}/accept`, `/decline`, `/revoke` | Existing-user invitation; invitee alone accepts; expiry checked live |
| Follows | `PUT/DELETE /me/following/{userId}`, `GET /me/following`, `GET /users/{id}/followers` | No self/block; another user's graph list requires profile visibility and is disabled for PRIVATE |
| Friends | `POST /friend-requests`, `GET /me/friend-requests`, `POST /friend-requests/{id}/accept`, `/decline`, `/cancel`; `GET /me/friends`, `DELETE /me/friends/{userId}` | Canonical pair, recipient acceptance, no auto mutual-follow |
| Block / mute | `PUT/DELETE /me/blocks/{userId}`, `GET /me/blocks`; equivalent `/me/mutes` | Own private lists; block effects atomic |
| Posts | `POST /posts`, `GET/PATCH/DELETE /posts/{id}`, `PUT /posts/{id}/audience`, `GET /posts/{id}/revisions` | Scoped author command; versioned privacy edit; history author/moderator only |
| Comments | `GET/POST /posts/{id}/comments`, `GET/POST /comments/{rootId}/replies`, `PATCH/DELETE /comments/{id}` | Two levels; enforce parent post policy on every path |
| Reactions | `PUT/DELETE /posts/{id}/reactions/me`, `GET /posts/{id}/reactions`; equivalent comment routes | PUT `{type:"LOVE"}` replaces; viewer-filtered summaries and actor list |
| Feeds | `GET /feeds/home`, `GET /communities/{id}/feed`, `GET /users/{id}/timeline` | No implicit global-public discovery in home feed; cursor contract |
| Media | `POST /media/uploads`, `POST /media/{id}/finalize`, `GET /media/{id}`, `DELETE /media/{id}` | Owner upload/status/unbound deletion; GET is metadata, not arbitrary object read |
| Bound media access | `POST /posts/{postId}/media/{mediaId}/access`, `POST /users/{id}/avatar/access` | Fresh content/profile authorization; returns short-lived variants URLs, no public bucket |
| Notifications | `GET /notifications`, `PUT /notifications/{id}/read`, `POST /notifications/read`, `GET/PATCH /me/notification-preferences`, `GET /notifications/stream` | Recipient only; read carries observed activityVersion; SSE via authenticated fetch stream |
| Reports | `POST /reports`, `GET /me/reports` | Reporter can access target at report time; no exposure of others' reports |
| Moderation | `GET /communities/{id}/moderation/reports`, `GET /communities/{id}/moderation/reports/{reportId}`, `POST /communities/{id}/moderation/actions` | Typed action/target/reason/version, staff hierarchy, audited evidence reads |
| Platform administration | `POST /admin/moderation/actions`, `GET /admin/moderation/reports` | Platform ADMIN and separate operational access controls |
| Search | `GET /search/users?q=`, `/search/communities?q=`, `/search/posts?q=` | Current source authorization before snippets; no hidden exact totals |

Example publish request:

```json
{
  "body": "A useful community resource",
  "communityId": "bf2bd357-d549-41d1-a25d-ddb9e5634165",
  "audience": { "type": "COMMUNITY" },
  "media": [{ "mediaId": "b0a160d5-5752-4a54-93ce-1c063871f59e", "altText": "Meeting notes" }],
  "linkUrl": "https://example.org/resource"
}
```

Body never includes authorId, role, status, publishedAt, storage key or approval fields. CUSTOM instead has `audience:{type:"CUSTOM",recipientIds:[...]}` and no communityId. Parser may extract hashtags; explicit mention IDs/offsets are validated. The response contains current accessible data and attachment descriptors; obtaining a media URL is a separate authorized operation.

SSE uses an Authorization-capable fetch streaming client; native browser EventSource cannot set a Bearer header. Never place access tokens in stream URL query parameters. Connection expires at access token expiry, then client refreshes and reconnects. SSE is an enhancement over the REST inbox, never a reliable message queue.
