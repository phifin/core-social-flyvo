# Domain model and product invariants

This is the source of truth for lifecycle semantics. Database details are in [database-design.md](database-design.md); permission precedence is in [security.md](security.md). All configurable limits have defaults; changing a limit does not authorize changing a lifecycle.

## Account, identity and profile

`UserAccount` (`account`) is the durable actor: UUID, status, platform role, created time and version. Status is `PENDING_VERIFICATION -> ACTIVE -> SUSPENDED` (reversible) or `DELETION_PENDING -> DELETED` (terminal after erasure). Suspended users cannot authenticate/use private APIs; their content is hidden by account-status checks. Status changes are attributable and audited. A deletion request revokes access immediately and starts the retention/erasure workflow. Before deleting, an owner transfers or archives owned communities.

`AuthIdentity` (`identity`) is an external login binding, uniquely `(provider, issuer, subject)`; provider is GOOGLE or FACEBOOK initially. Subject, not email, identifies a provider principal. `LocalCredential` is at most one per user. `LoginEmail` is the unique normalized login/recovery address, with verification time, not a public profile field. A user may have all three authentication methods. Provider email is an observation and cannot replace the login email or link accounts implicitly. Explicit unlinking must leave at least one usable login method; recovery email alone is not a login method.

`AuthSession` is one login/device family, not one access token. Its refresh-token generations are single-use secrets with retained hash history. Verification, reset and OAuth transactions expire and are consumed once. They never grant community roles. See [identity scenarios](security.md#identity-collision-and-linking-policy).

`Profile` (`profile`) has the same user ID, globally unique normalized handle, display name, bio, avatar media reference and version. Profile visibility defaults to `AUTHENTICATED`; supported scopes are `PUBLIC`, `AUTHENTICATED`, `FRIENDS`, `PRIVATE`. Handle/name lookup follows profile visibility. A visible post may show an author's minimal attribution (ID/display name), even if the full profile is private; no bio, graph, email or avatar URL is implied. UUID-backed handles such as `user_<random>` avoid unsafe guesses and collisions at registration. Changes to profile privacy take effect at authoritative reads. Block wins over profile visibility for signed-in viewers.

`ProfilePreferences` contains locale, timezone identifier and default post audience; `NotificationPreference` belongs to notification. Store social preference fields explicitly, not an unbounded settings JSON bag. Account status and credentials do not belong in Profile. Avatars use the same media validation pipeline as posts.

## Communities

Phase 1 implementation provides account suspension/deletion-request primitives,
exactly one default profile and preference row at registration, and profile
scopes PUBLIC/AUTHENTICATED/PRIVATE. FRIENDS profile scope waits for Phase 3;
the neutral default post audience remains FRIENDS. No avatar column/media FK
exists before Phase 4. Deletion currently denies access and persists its event;
there is no erasure claim. Phase 2 must integrate the active-community ownership
check before enabling its ownership model.

`Community` owns name, stable slug, description, visibility (`PUBLIC`, `PRIVATE`), join mode (`OPEN`, `REQUEST`, `INVITE_ONLY`), status (`ACTIVE`, `ARCHIVED`) and exactly one owner reference. Defaults: PRIVATE + INVITE_ONLY. Private communities are discoverable by minimal name/description cards to signed-in users so request-to-join works; member lists/posts remain private. Hidden communities are a future explicit product extension, not an ambiguous meaning of PRIVATE.

Community visibility and membership mode are orthogonal. PUBLIC permits reading its posts anonymously; posting/commenting/reacting requires active membership. PRIVATE + OPEN permits immediate membership by a verified user, but content still requires membership. Members are not automatically friends/followers.

`Membership` is one row per `(community, user)`, with lifecycle `ACTIVE`, `LEFT`, `REMOVED`, `BANNED`. Rejoining reuses the row and increments version. Temporary suspension is `suspended_until` on ACTIVE membership: while in the future it denies private reads and community writes. BANNED is indefinite exclusion until a moderator explicitly unbans; unban changes to REMOVED, never silently restores access. LEFT/REMOVED may rejoin under the current join mode. Owner cannot leave, be removed, suspended or banned until ownership transfers or community archives.

Effective roles are OWNER, ADMIN, MODERATOR, MEMBER. Persist OWNER only as `community.owner_user_id`; membership stores ADMIN/MODERATOR/MEMBER. This prevents two independent sources of ownership. The owner must have an ACTIVE, unsuspended membership. Owner transfer locks the community, validates successor membership, changes the owner pointer and makes the former owner ADMIN. Archive prevents new activity and retains visibility/audit until deletion policy runs.

`CommunityInvitation` targets an existing user ID initially (not arbitrary email). It is PENDING, ACCEPTED, DECLINED, REVOKED or EXPIRED; expires after 7 days. Only the invitee accepts it. Invitation grants MEMBER only. A banned user cannot accept. Invitations for unknown emails are deferred: implement signup first rather than exposing transferable bearer grants.

`JoinRequest` is PENDING, APPROVED, REJECTED or CANCELLED. A pending request cannot coexist with a pending invitation for the same pair: the guarded command resolves the older path explicitly. Approval creates/reactivates membership in the same transaction. Existing membership produces an idempotent result without duplicate events. Changes to join mode do not auto-approve outstanding requests; administrators may approve/reject them. Expired invitations never grant access even before cleanup runs.

## Social graph

Use separate concepts/tables:

| Concept | Direction and lifecycle | Invariants |
|---|---|---|
| Follow | follower -> followed; presence means active | No self-follow, unique pair, neither side blocks. Open follow initially; private-profile follow approval is outside scope |
| FriendRequest | requester -> addressee; PENDING, ACCEPTED, DECLINED, CANCELLED, EXPIRED | One pending unordered pair, no self, recipient alone accepts; opposite request does not auto-accept; 30-day expiry |
| Friendship | unordered canonical pair; presence means friends | Created only by acceptance, unique pair, no block; no implicit follows |
| Block | blocker -> blocked; presence means blocked | Any direction denies bilateral interaction; creation removes follows both ways, friendship and pending friend requests atomically |
| Mute | muter -> muted; presence means muted | Suppresses home feed and social notifications for muter; does not change authorization or notify muted user |

Unblocking does not restore previous relations. Existing posts/comments/reactions are not destructively removed on block; reads suppress blocked actors and writes cannot create further interaction. In shared communities blocked comments become anonymous tombstones to preserve thread structure; reaction lists/totals are viewer-filtered. Community membership/role is not removed by a personal block. Moderators may use explicit audited moderation endpoints to handle community content despite personal blocks; ordinary reading endpoints do not gain a bypass.

Private-profile status does not itself restrict FOLLOWERS post audience: anyone can follow unless blocked. Use FRIENDS/CUSTOM/ONLY_ME for controlled sharing. This tradeoff is explicit; approved follow requests would be a separate feature and ADR.

## Content

`Post` is an aggregate: author, optional community, body (plain text initially, max 10,000 characters), optional one HTTP(S) link, content kind (`STANDARD` initially), audience, media references (max 10), timestamps, version and state (`PUBLISHED`, `DELETED`, `REMOVED`). Nonempty body, link or media is required. Create directly as PUBLISHED; server drafts are deferred. `deleted_at` is owner soft deletion; moderation removal uses separate reason/action reference. Neither is returned in normal lists. A deleted post has no readable comments or attachments through the application.

Personal audiences: PUBLIC, FOLLOWERS, FRIENDS, CUSTOM, ONLY_ME. Community posts use COMMUNITY only and carry one community ID. COMMUNITY means public if community is PUBLIC, otherwise effective members only. This avoids conflicting “public post in a private community” flags. No cross-post to several communities in one aggregate.

`PostAudience` is a discriminated value plus normalized recipient rows for CUSTOM (1..100 other users). Author always has access to their own active post, except community scope still requires current community access; an ex-member cannot use author ownership to enter a private community. CUSTOM is a fixed explicit recipient list, not a snapshot of all friends. Current blocks/status still apply. Audience changes are explicit versioned commands; narrowing takes effect immediately at source reads, widening requires author intent and is audited. Mentions never expand audience.

`PostRevision` records immutable content/audience/attachment snapshots, editor and revision number on edit; initial revision is 1. History is readable by author and authorized moderators only, under current post visibility. Historical audiences never grant current access. It is retention-limited and not event sourcing. Updates retain original `published_at`; editing does not bump chronological feed position.

Deletion and moderation are distinct causes even though status is a convenient query field: `deleted_at` wins (DELETED), otherwise an uncleared `removed_at` means REMOVED, otherwise PUBLISHED. Clearing moderation never clears deletion. Use CHECK constraints for timestamp/status combinations. The same precedence applies to comments (VISIBLE replaces PUBLISHED). Removing media in an edit releases the old binding but preserves its revision reference for the retention window; history does not sign media that is purged, unsafe or no longer authorized.

`Mention` references a user ID and body offsets validated against submitted text; max 20. Offsets are UTF-16 code units with start inclusive/end exclusive, matching Java/JavaScript strings; reject splits inside surrogate pairs. `Hashtag` is NFKC-normalized then Locale.ROOT lowercased text, max 50 characters and max 10 per post. Normalize once consistently, keep original display text. Mentions/hashtags are derived relations, not JPA graphs traversed during serialization. Mentions notify only eligible viewers. Never fetch arbitrary link previews in the first content phase; a future fetcher requires SSRF protections and isolated network egress.

Extensions add explicit subtype data (`post_poll`, `poll_option`, etc.) and versioned DTO variants. Do not make the core post an unchecked JSON blob or a large nullable column collection. Files/video are media kinds that remain disabled until their validation pipeline exists.

## Comments and reactions

`Comment` is its own aggregate in content: post, author, optional root comment ID, text (1..5,000 characters), status (`VISIBLE`,`DELETED`,`REMOVED`) and version. Maximum depth is two: root plus replies. Replying to a reply resolves the same root and may record `reply_to_user_id` for UI context; this reference does not grant notifications/access. Root and child must belong to the same post. Roots/replies paginate separately oldest-first by `(created_at,id)`, default 20/max 100. Root deletion leaves a non-identifying tombstone if visible replies remain; reply deletion hides its text. Comment edits set `edited_at`; no comment revision history in the initial design. Deleted/removed posts cannot receive comments. Current post policy gates every comment query and mutation.

`Reaction` is one user's current reaction to a post or comment, initial types LIKE, LOVE, CELEBRATE, SUPPORT, INSIGHTFUL. One reaction per user per target, not one per type. PUT replaces type; repeated same type is a no-op; DELETE removes it from current activity by setting active=false. Retain the stable ID/version for safe asynchronous ordering; later PUT reactivates it. Enforce typed nullable target FKs with an exactly-one check, not arbitrary unvalidated target UUIDs. Updates produce a semantic before/after event. Author may react to own content, but no self-notification. Counts are derived or eventual display data, never authority; inactive reactions and blocked/deleted actors do not appear in viewer-visible reaction summaries.

## Media, feed, notifications and moderation

`MediaAsset` is uploader-owned until binding to one post or avatar context; it has a durable processing lifecycle and immutable object versions. A post may have many media assets; an asset has at most one active binding. Reusing an asset elsewhere requires an explicit clone workflow later. Attachment access follows its binding context, never “knows UUID.” See [media](media.md).

`Feed` is initially a query subsystem, not a domain aggregate or table of duplicated posts. Candidate providers, ranking strategy and cursor contract are explicit seams with concrete chronological implementations. Source content remains authoritative at every read. See [feed](feed.md).

`Notification` is a recipient-owned semantic record with kind, target references, bounded grouping window and activity/read versions. Actor contributions are stored separately so repeat delivery is idempotent and block/privacy changes can suppress them. It is not a rendered English sentence. Preferences and access are evaluated at delivery/read time. SSE sends invalidation signals, not permanently trusted content bodies. See [events and jobs](events-and-jobs.md).

`Report` belongs to a reporter and references exactly one supported target (post/comment/community/user), category, optional detail, evidence snapshot and lifecycle OPEN, IN_REVIEW, RESOLVED, DISMISSED. It is not public content. Submission requires ability to access the target at submission time; disappearance afterward does not destroy the case. One unresolved report per reporter/target avoids spam; different reporters may report the same target.

`ModerationAction` is append-only actor, scope, target, action, reason, before/after references and time. Actions include REMOVE_CONTENT, RESTORE_CONTENT, SUSPEND_MEMBER, BAN_MEMBER, UNBAN_MEMBER, SUSPEND_ACCOUNT and RESOLVE_REPORT. Community staff act only within their community and role hierarchy. Global suspension requires separate platform ADMIN, assigned operationally, never via registration/profile input. The action, owner-module change, report transition and required audit commit atomically. Restoration does not undo an author's deletion; it only clears a moderation removal if still otherwise valid.

Audit entries record actor/action/resource/request/outcome with redacted metadata. They are distinct from business events, debug logs and notification records.
