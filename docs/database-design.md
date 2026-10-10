# Preliminary relational schema and consistency contract

This is a migration design specification, not executable DDL. Implement only the tables required by the current [phase](roadmap.md). All IDs are application-generated random UUIDs (v4 initially); chronology uses timestamps plus UUID tie-breakers, never UUID ordering alone. Database names are snake_case. PostgreSQL schemas match module names. Global Flyway history remains in `public`.

## Notation and common rules

`id` means `uuid PRIMARY KEY`. `U` means UNIQUE, `FK` an actual database foreign key, `?` nullable; other listed fields are NOT NULL unless a state-dependent note says otherwise. Time columns are `timestamptz` mapped to `Instant`; `created_at` defaults to database current time. Mutable aggregates have `updated_at` and `version bigint NOT NULL DEFAULT 0` (denoted **V** below) with JPA optimistic locking. State enums use `varchar` plus CHECK constraints, not ordinal or PostgreSQL enum types. `hash` is `bytea`; text lengths and nonnegative numeric checks are migration constraints as well as input validation.

Cross-module FKs are intentional referential integrity in one database. Reference UUID columns, never cross-module JPA associations. Use `ON DELETE RESTRICT` between aggregates/modules, and hard-delete only through explicit retention workflows; dependent rows within an aggregate may use CASCADE after review. Index referencing FKs used for traversal/deletion: PostgreSQL does not automatically create such indexes. Tables not explicitly listed as soft-deleted are not to receive a generic `deleted` flag.

Rows without a public resource identity (follows, membership key, reaction associations) may have composite primary keys. Publicly addressable invitations/requests/notifications have UUID PKs. Uniqueness includes active/terminal lifecycle semantics explicitly; never add `deleted_at` to a unique key and assume NULLs enforce active uniqueness.

## Account, identity, profile

| Table | Important columns | Constraints / indexes |
|---|---|---|
| `account.user_account` | id, status, platform_role (`USER`,`ADMIN`), created_at, deletion_requested_at?, suspended_until?, **V** | status/role CHECK; index `(status,deletion_requested_at)` for cleanup. Soft tombstone retains ID |
| `account.erasure_step` (P9A) | user_id FK account, module, step, status, attempt_count int, checkpoint jsonb, completed_at?, last_error_code?, **V** | PK(user_id,module,step); bounded resumable erasure; index `(status,updated_at)`; no content/secrets in checkpoint |
| `identity.login_email` | user_id PK/FK account, email_original varchar(254), email_normalized varchar(254), verified_at, **V** | U(email_normalized); normalize trim + lowercase with Locale.ROOT, IDNA domain; no Gmail dot/plus rewriting. Explicit product policy treats email case-insensitively |
| `identity.local_credential` | user_id PK/FK account, password_hash text, password_changed_at, **V** | PHC/encoder format includes algorithm/cost; never reversible |
| `identity.auth_identity` | id, user_id FK account, provider, issuer, subject, observed_email?, created_at, last_login_at | U(provider,issuer,subject); U(user_id,provider) initially; index user_id; never key by email |
| `identity.registration_intent` | id, email_normalized, token_hash U, flow (`LOCAL`,`EXTERNAL`), provider_proof_encrypted?, browser_binding_hash, expires_at, consumed_at?, created_at | index `(email_normalized,created_at)` and expires_at; no user/FK until successful completion; no password from initial request |
| `identity.email_dispatch_window` | email_key bytea, purpose, window_start timestamptz, issued_count int | PK(email_key,purpose,window_start); atomic conditional UPSERT enforces per-address email issuance budget, key is HMAC(normalized email), expire old windows |
| `identity.auth_session` | id, user_id FK account, created_at, last_seen_at, authenticated_at, reauthenticated_at?, idle_expires_at, absolute_expires_at, revoked_at?, revoke_reason?, user_agent_summary?, ip_prefix_hash?, **V** | index `(user_id,revoked_at)` and expires; metadata bounded/redacted; active iff current time within both expiries and no revocation; rotation does not update authentication freshness |
| `identity.refresh_token` | id, session_id FK session, generation int, token_hash U, issued_at, expires_at, consumed_at?, replaced_by_id? FK self | U(session_id,generation); partial U(session_id) WHERE consumed_at IS NULL; retain consumed hashes until session expiry + 7 days; expire rows by timestamp |
| `identity.one_time_token` | id, user_id FK account, purpose (`RESET_PASSWORD`,`CHANGE_EMAIL`), token_hash U, target_email?, expires_at, consumed_at?, created_at | index `(user_id,purpose,created_at)`, expires_at; under account guard invalidate previous same-purpose tokens |
| `identity.oauth_transaction` | id, state_hash U, browser_binding_hash, provider, intent (`LOGIN`,`LINK`,`REAUTH`), initiating_user_id?, initiating_session_id?, nonce_hash?, pkce_verifier_encrypted?, return_path, expires_at, consumed_at? | LINK/REAUTH require user + session FK; fixed local return path; short expiry; callback consumes once |
| `profile.profile` | user_id PK/FK account, handle, handle_normalized, display_name varchar(80), bio varchar(1000), avatar_media_id? FK media, visibility, created_at, **V** | U(handle_normalized); CHECK handle pattern `[a-z0-9_]{3,30}`; index display-name search only when Phase 8 starts |
| `profile.preference` | user_id PK/FK account, locale varchar(35), time_zone varchar(64), default_post_audience, **V** | default audience FRIENDS; validate timezone in application; custom recipients are not a default |

Media FK/profile avatar column is added in Phase 4 after media schema exists. Phase 1 creates profile without it. Account creation, email claim, credential/external binding and initial profile commit together; competing claims are resolved by the unique email/identity indexes. `PENDING_VERIFICATION` is reserved for administrative/imported accounts; ordinary registration stores an intent and creates an ACTIVE account only after verification. Unverified intents cannot reserve a login email indefinitely.

### Phase 1 concrete schema refinements

`V0002`–`V0008` implement the preceding account/identity/profile design and the
platform durability tables below. `identity.external_onboarding` additionally
stores a hashed limited capability, browser hash, encrypted provider proof,
expiry and consumption time for provider flows without a confirmed recovery
email. It reserves neither email nor account. Only verified completion claims
the unique email/provider indexes. `email_original` currently stores the same
canonical address as `email_normalized`; self responses use the canonical value.

Account/profile/preferences use JPA versions. Identity mutable state uses owned
parameterized JDBC, account/session guards and explicit version increments/CAS,
not a second JPA representation. Audit is protected by an append-only trigger
as well as the production runtime-role requirement. Additional CHECKs enforce
OAuth initiators, secret hash sizes, session expiry order and running leases.
There are no SQL views, media FK or generic idempotency records in Phase 1:
auth secrets must not enter response caches, and no eligible creating API uses
generic idempotency yet. Cleanup is bounded to 500 records per category/claim
cycle; old refresh generations are removed oldest-first to respect successor FKs.

`auth_session.reauthentication_required` prevents mailbox completion of external
onboarding from renewing provider authentication freshness. Explicit password/
bound-provider reauthentication clears it; refresh rotation cannot clear it.

## Communities

| Table | Important columns | Constraints / indexes |
|---|---|---|
| `community.community` | id, slug_normalized, name varchar(120), description varchar(4000), visibility, join_mode, status, owner_user_id FK account, created_at, **V** | U(slug_normalized); `(visibility,status,created_at,id)`; composite FK `(id,owner_user_id)` -> membership `(community_id,user_id)` DEFERRABLE INITIALLY DEFERRED |
| `community.membership` | community_id FK community, user_id FK account, role (`ADMIN`,`MODERATOR`,`MEMBER`), status, joined_at, left_at?, suspended_until?, **V** | PK(community_id,user_id); `(user_id,status,community_id)`; `(community_id,status,role,user_id)`; owner effective role derives from community |
| `community.invitation` | id, community_id FK, invitee_id FK account, inviter_id FK account, status, expires_at, responded_at?, created_at, **V** | partial U(community_id,invitee_id) WHERE status='PENDING'; `(invitee_id,status,created_at,id)`; `(status,expires_at)` |
| `community.join_request` | id, community_id FK, user_id FK account, status, message? varchar(1000), decided_by? FK account, decided_at?, created_at, **V** | partial U(community_id,user_id) WHERE status='PENDING'; `(community_id,status,created_at,id)` |

Deferred FK resolves owner/member creation order. A deferred constraint trigger on community/membership verifies, at commit, that every ACTIVE community's owner membership is ACTIVE and unsuspended. Archive permits retention of a former owner. Trigger is narrow and tested; all membership/ownership commands also lock the community row and enforce this invariant for useful errors. Invitation-versus-request mutual exclusion and expiry are application invariants under the same community guard; cannot be a time-dependent CHECK/index predicate. Expire a stale PENDING row before inserting a replacement.

### Phase 2 concrete community schema

`V0009` implements community/membership/invitation/request tables and both
community_api v1 views. JDBC snapshots use explicit version increments under
the owning row guard, without a duplicate JPA model. Community version sequences
all user command transitions; membership/admission rows keep their own versions.
Stable slug is 3–80 lowercase ASCII characters. All FKs use scalar UUIDs; indexes
cover ownership closure, membership lookup/lists, invitation inbox/expiry and request
review. The deferred effective-owner trigger locks its community before checking
current owner membership, including raw SQL commit-time violations. Archive retains
the owner FK but permits self-leave per the detailed domain exception.

`V0010` adds moderation.action for member sanctions only: no report table/FK until
Phase 5. Reason and before/after snapshots are private; append-only trigger and
scoped audit supplement restricted runtime roles. `V0011` adds platform resource
idempotency (actor/route/key hash, request hash, logical response UUID, 24h expiry),
without cached bodies/secrets or a redundant response-status column. Scheduled
invitation expiry reuses platform.job; no community-specific job queue/table.

Resource idempotency claim uses atomic UPSERT/RETURNING under the same originating
transaction, eliminating the cleanup race between INSERT and SELECT. Only expired
keys renew; live replay leaves logical fields/expiry unchanged. Pruning selects
at most 500 expired keys in deterministic expiry/primary-key order with FOR UPDATE
SKIP LOCKED, then deletes only still-expired rows. In-use/renewed keys survive;
cleanup may lag without reducing a committed replay window. No migration is needed
for this protocol fix; V0001–V0011 remain unchanged.

No account/graph SQL views are introduced before their consuming phases. The
community views expose exactly the published columns below, current source state,
and stored membership role; effective OWNER derives from the community owner ID.
See [community subsystem](community.md) for transition/guard and retry semantics.

## Social graph

| Table | Columns | Constraints / indexes |
|---|---|---|
| `graph.follow` | follower_id FK account, followed_id FK account, created_at | PK(follower_id,followed_id); CHECK different; reverse `(followed_id,follower_id)` and outgoing chronological `(follower_id,created_at,followed_id)` |
| `graph.friend_request` | id, requester_id FK account, addressee_id FK account, low_user_id, high_user_id, status, expires_at, resolved_at?, created_at, **V** | canonical pair generated from LEAST/GREATEST UUIDs; CHECK different; partial U(low_user_id,high_user_id) WHERE status='PENDING'; incoming `(addressee_id,status,created_at,id)`; outgoing equivalent |
| `graph.friendship` | low_user_id FK account, high_user_id FK account, established_at, request_id FK request | PK(low_user_id,high_user_id); CHECK low < high; reverse `(high_user_id,low_user_id)`; U(request_id) |
| `graph.block` | blocker_id FK account, blocked_id FK account, created_at | PK(blocker_id,blocked_id); CHECK different; reverse `(blocked_id,blocker_id)` |
| `graph.mute` | muter_id FK account, muted_id FK account, created_at | PK(muter_id,muted_id); CHECK different; no reverse index until an actual reverse query needs it |
| `graph.pair_revision` | id, low_user_id FK account, high_user_id FK account, version bigint | U(low_user_id,high_user_id); CHECK low < high; durable monotonic event sequence for all changes to a pair, retained when relation rows disappear |

Use `LEAST/GREATEST` PostgreSQL UUID ordering everywhere; do not use Java UUID signed comparison for canonical pair calculation. One pending request is prevented by the unique index. Friendship creation/acceptance and block cleanup additionally require a pair lock so different tables cannot race into contradictory state.

## Content

| Table | Important columns | Constraints / indexes |
|---|---|---|
| `content.post` | id, author_id FK account, community_id? FK community, kind, body text, link_url? varchar(2048), audience, status, revision_number int, published_at, edited_at?, deleted_at?, removed_at?, removal_action_id? uuid, created_at, **V** | CHECK `(audience='COMMUNITY') = (community_id IS NOT NULL)`; CHECK body length <=10000; `(published_at DESC,id DESC)` WHERE status='PUBLISHED'; `(author_id,published_at DESC,id DESC)` partial; `(community_id,published_at DESC,id DESC)` partial |
| `content.post_recipient` | post_id FK post, user_id FK account | PK(post_id,user_id); reverse `(user_id,post_id)` for custom candidates; count limit and audience match under post lock |
| `content.post_revision` | post_id FK post, revision_number, editor_id FK account, body, link_url?, audience_snapshot jsonb, media_ids_snapshot jsonb, edited_at | PK(post_id,revision_number); validated versioned snapshots, never queried as current audience |
| `content.post_media` | post_id FK post, media_id FK media.asset, position smallint, alt_text? varchar(1000) | PK(post_id,media_id); U(media_id); U(post_id,position); position 0..9 |
| `content.post_mention` | post_id FK post, mentioned_user_id FK account, start_offset int, end_offset int | PK(post_id,mentioned_user_id,start_offset); `(mentioned_user_id,post_id)`; valid ordered offsets checked |
| `content.hashtag` | id, normalized_tag varchar(50), display_tag varchar(50) | U(normalized_tag) |
| `content.post_hashtag` | post_id FK, hashtag_id FK | PK(post_id,hashtag_id); reverse `(hashtag_id,post_id)` |
| `content.comment` | id, post_id FK post, author_id FK account, root_comment_id? uuid, reply_to_user_id? FK account, body varchar(5000), status, created_at, edited_at?, deleted_at?, removed_at?, removal_action_id?, **V** | U(id,post_id); FK(root_comment_id,post_id) -> comment(id,post_id); CHECK root != id; roots `(post_id,created_at,id)` WHERE root_comment_id IS NULL; replies `(root_comment_id,created_at,id)` |
| `content.reaction` | id, actor_id FK account, post_id? FK post, comment_id? FK comment, type, active boolean, created_at, **V** | CHECK num_nonnulls(post_id,comment_id)=1; partial U(actor_id,post_id) WHERE post_id IS NOT NULL (including inactive rows); equivalent comment index; `(post_id,type)` and `(comment_id,type)` WHERE active for totals. DELETE deactivates; PUT reactivates the same ID/version sequence |

A constraint trigger enforces that a non-null root references a root (root_comment_id IS NULL); depth and post scope cannot change on edit. All comment creation also checks root state through the owning application command. DB cannot CHECK “post has nonempty text OR an attachment in another table”; command validates after binding before commit. `removal_action_id` is a correlation UUID with no reverse FK (moderation owns an independently retained action); removal requires moderation API/audit transaction, not arbitrary setter access.

No synchronous hot-row reaction/comment counters initially. Compute grouped counts for a bounded page, then introduce a rebuildable projection after evidence. `count(*)` is viewer-filtered for block/account restrictions; an eventual global count must never replace it on private/blocked-sensitive surfaces.

## Media

| Table | Important columns | Constraints / indexes |
|---|---|---|
| `media.asset` | id, owner_id FK account, purpose (`POST`,`AVATAR`), kind (`IMAGE` initially), state, quarantine_key U, uploaded_version_id?, declared_mime, declared_bytes bigint, actual_mime?, actual_bytes?, sha256?, width?, height?, processing_generation int, failure_code?, upload_expires_at, orphan_expires_at, finalized_at?, ready_at?, created_at, **V** | bytes >0 and within configured ceiling; `(state,upload_expires_at)` and `(state,orphan_expires_at)`; `(owner_id,created_at,id)` |
| `media.variant` | id, asset_id FK asset, name (`DISPLAY`,`THUMBNAIL`), object_key U, object_version_id, mime, bytes, width, height, generation | U(asset_id,name,generation); only current READY generation is servable |
| `media.binding` | asset_id PK/FK asset, context_type (`POST`,`AVATAR`), context_id uuid, bound_at, released_at? | at most one binding; `(context_type,context_id)`; logical context reference validated by owner-module command in same transaction |
| `media.quota` | owner_id PK/FK account, reserved_bytes bigint, retained_bytes bigint, **V** | nonnegative CHECKs; lock before assets for reserve/release/READY transitions; configured limits checked under lock, reconciliation compares actual charged assets/variants |

Binding logical references are an explicit exception to cross-module FKs: adding reverse post/profile dependencies into media would form a cycle. Forward post_media/profile FKs plus `MediaCommands.bind/release` and reconciliation provide integrity. Binding writes are not publicly exposed HTTP commands. Released bindings are retained until purge; assets are not rebound to another context.

Quota reservation counts pending declared bytes against pending and total budget. READY transitions replace that reservation with actual delivered variant bytes under the quota lock; generated output that exceeds remaining budget is not committed READY. Rejected/abandoned assets release reservations only after quarantine cleanup, preventing rapid failed uploads from escaping accounting. Quarantine temporary/version bytes also require bucket lifecycle limits, rate controls and reconciliation; database quotas alone cannot constrain malicious repeated presigned uploads.

## Notifications, search and moderation

| Table | Important columns | Constraints / indexes |
|---|---|---|
| `notification.notification` | id, recipient_id FK account, kind, target_type, target_id uuid, grouping_key text, group_window_start?, activity_version bigint, read_version bigint default 0, last_activity_at, suppressed_at?, created_at, **V** | U(recipient_id,grouping_key); reaction key includes kind/target/hour, other keys include event ID; read_version <= activity_version; `(recipient_id,last_activity_at DESC,id DESC)`; partial unread index WHERE read_version < activity_version |
| `notification.contribution` | id, notification_id FK, source_event_id uuid, actor_id FK account, source_type, source_id uuid, source_version bigint, active boolean, occurred_at | U(notification_id,source_event_id); U(notification_id,source_type,source_id,actor_id); update only for newer source version |
| `notification.source_state` | recipient_id FK account, kind, source_type, source_id uuid, actor_id FK account, latest_source_version bigint, active boolean, notification_id? FK notification | PK(recipient_id,kind,source_type,source_id,actor_id); serializes updates across grouping windows, rejects old create after newer delete; retain through replay horizon even if notification purged |
| `notification.preference` | user_id FK account, kind, in_app_enabled boolean | PK(user_id,kind); defaults enabled except self-events |
| `search.document` | target_type, target_id uuid, source_version bigint, title text, body text, search_vector tsvector, updated_at, deleted boolean | PK(target_type,target_id); GIN(search_vector); optional pg_trgm GIN on normalized name only after explain/benchmark |
| `moderation.report` | id, reporter_id FK account, target_type, target_id uuid, community_id? FK, category, detail? varchar(4000), evidence_encrypted?, status, assigned_to? FK account, resolved_at?, created_at, **V** | partial U(reporter_id,target_type,target_id) WHERE status IN ('OPEN','IN_REVIEW'); `(community_id,status,created_at,id)` |
| `moderation.action` | id, report_id? FK report, actor_id FK account, community_id? FK, target_type, target_id, action, reason varchar(2000), before_state jsonb, after_state jsonb, request_id, created_at | append-only; `(target_type,target_id,created_at)` and `(community_id,created_at,id)` |

Notification/search/report targets are semantic references, not unchecked permission grants. They intentionally survive target deletion and can represent multiple modules without reverse FKs. Validate existence/access on creation; consumers tolerate gone targets and recheck authorization. Search stores only required searchable content and is operationally private. Never return index snippets before source authorization.

## Platform durability and audit

| Table | Important columns | Constraints / indexes |
|---|---|---|
| `platform.outbox_event` | id, event_type, schema_version int, aggregate_type, aggregate_id, aggregate_version bigint, event_sequence int, occurred_at, payload jsonb, correlation_id, causation_id?, created_at | U(aggregate_type,aggregate_id,aggregate_version,event_sequence); zero-based sequence distinguishes multiple facts in one transition; payload excludes secrets/content by default |
| `platform.outbox_delivery` | event_id FK outbox, consumer_name, status, attempts, available_at, lease_owner?, lease_until?, lease_generation bigint, last_error_code?, completed_at? | PK(event_id,consumer_name); partial `(available_at,event_id)` WHERE status IN ('PENDING','RETRY'); lease expiry index |
| `platform.job` | id, kind, dedup_key, payload jsonb, status, attempts, available_at, lease_owner?, lease_until?, lease_generation bigint, last_error_code?, created_at, completed_at? | U(kind,dedup_key); due/lease indexes; no raw reset/verification token in payload |
| `platform.secret_mail_payload` | job_id PK/FK job, ciphertext bytea, key_id, expires_at | restricted reads; delete after delivery or expiry; application encryption key external to DB |
| `platform.consumer_receipt` | consumer_name, event_id uuid, processed_at | PK(consumer_name,event_id); consumer side effect and receipt same transaction |
| `platform.audit_entry` | id, actor_id? FK account, action, target_type, target_id?, scope_id?, outcome, reason_code?, request_id, metadata jsonb, created_at | append-only role; `(target_type,target_id,created_at)` and `(actor_id,created_at)`; sensitive values forbidden |
| `platform.idempotency_record` | actor_id FK account, route_key, key_hash, request_hash, response_status, response_reference?, encrypted_response? bytea, expires_at, created_at | PK(actor_id,route_key,key_hash); conflicting request hash ->409; do not store tokens; expire at 24h |

Consumer registrations are code-defined: producer append inserts delivery rows for currently enabled durable consumers atomically. New projections require explicit backfill; they do not magically receive retained events. Internal mail/cleanup/processing jobs are submitted directly in the business transaction and do not need a domain event merely to schedule work. Phase 1 introduces these minimal durable primitives; no separate job framework initially.

## Published SQL read contracts

| View | Columns / meaning | Consumer |
|---|---|---|
| `account_api.active_users_v1` | user_id for currently ACTIVE accounts | content query adapter |
| `graph_api.follows_v1` | follower_id, followed_id | content query adapter |
| `graph_api.friendships_v1` | low_user_id, high_user_id | content query adapter |
| `graph_api.blocks_v1` | blocker_id, blocked_id | content query adapter |
| `graph_api.mutes_v1` | muter_id, muted_id | content query adapter, home-feed mode only |
| `community_api.communities_v1` | community_id, visibility, status, owner_user_id | content query adapter |
| `community_api.memberships_v1` | community_id, user_id, role, status, suspended_until | content query adapter |

Views expose current source rows; never materialize privacy/block/membership views. The content adapter uses EXISTS/NOT EXISTS with indexed paths and viewer parameters. Write permission checks use owner-module APIs/guards instead of treating SQL views as commands. `ContentQueries` supports home/community/timeline candidate queries, authorized batch hydrate, target checks, and explicit moderation reads. No unbounded IN-list, OSIV or one query per post.

## Concurrency recipes and lock order

| Conflict | Required handling |
|---|---|
| Same email/provider claimed twice | UNIQUE + transaction rollback translated to safe conflict; never catch constraint failure and continue the aborted transaction |
| Parallel credential/link/unlink/reset changes | Exclusive account lifecycle row guard serializes usable-method checks, credential changes and session revocation. Two unlink commands cannot each observe the other as the last remaining method |
| Refresh rotation/replay | Lock session row, check status, consume current token, insert next generation. Reuse sets session revoked and commits; only then return 401 (throwing a rollback exception would undo revocation) |
| Follow/friend/block races | Graph API acquires transaction advisory lock for canonical pair; all mutations exclusive. Check blocks after lock; block deletes both follows/friendship and resolves pending requests atomically |
| Community membership/owner changes | Exclusive community row lock for transitions. Content writes take a shared guard on community, recheck membership. Prevent check-then-revoke races; unrelated reads do not lock |
| Comment/reaction vs block | Shared transaction advisory pair guards against post author and, for replies/reactions, target comment author; graph mutation takes exclusive same pair guard. Acquire all pairs sorted |
| Comment/reaction vs post removal | Shared post row guard for interaction; post edits/removal take exclusive row lock. Target comment lock if needed. Transactions stay short |
| Reaction upsert/delete | Request the final lock mode at first acquisition: post reaction takes exclusive post guard; comment reaction takes shared post then exclusive target comment guard. Never take shared then upgrade on the same row. UNIQUE constraints + deterministic UPSERT serialize target writes initially; narrow only after measured contention/tests |
| Optimistic edit collisions | Require expected version, update using @Version, return 412 if stale; never silently overwrite |
| Media finalize/bind/cleanup | Version/state compare-and-set plus asset row lock. Cleanup claims DELETING before storage deletion; binding only accepts READY and unbound under same lock |
| Notification aggregation | Lock source_state, then affected old/new group rows in deterministic grouping-key order, contribution uniqueness/source version + receipt; increment activity_version only for effective new activity |
| Duplicate jobs/events | Lease + receipt/idempotent effect; duplicate execution is normal, not exceptional |

Pair advisory keys use a stable, documented 64-bit hash of the canonical UUID pair (not Java `hashCode`). Collisions cause extra serialization, never an authorization shortcut. Shared interaction guards and exclusive graph guards use the same key namespace. PostgreSQL transaction advisory locks release at transaction end. [PostgreSQL locking documentation](https://www.postgresql.org/docs/18/explicit-locking.html).

Global acquisition order: account lifecycle guards (user UUID order) -> session (identity only) -> community (UUID order) -> graph pair guards (key order) -> post -> comment -> media quota rows (owner UUID order) -> media assets (UUID order). Account suspend/delete and credential mutations use an exclusive account-row guard; other authenticated mutations take shared guard(s) for actor and relevant target authors before changing state. Community creation/owner transfer also guards the proposed owner's ACTIVE account; this serializes against the no-active-ownership check in account closure. Pre-read IDs to determine the complete lock set, acquire guards in order, then reload/revalidate; retry if immutable scope assumptions changed. Bulk jobs use the same order and bounded batches. Never call another module that acquires an earlier lock while holding a later one. Read-only permission queries need no locks; guard methods are explicit and transaction-required. Post moderation obtains any relevant community guard before the post. Retry deadlock/serialization failures at most twice only for idempotent commands; otherwise surface a retryable problem.

## Migration and retention rules

Use global `V0001__platform_foundation.sql`, `V0002__account_identity.sql` style versions, allocated after inspecting existing migrations. These names illustrate order, not a preassigned list. All changes use Flyway, including indexes/views/triggers/extensions/backfills. Do not edit an applied version. Concurrent index builds need an explicitly nontransactional Flyway migration and deployment runbook. Hibernate `ddl-auto=validate`, UTC JDBC timezone, OSIV disabled.

Use expand/backfill/contract for deployed changes; no destructive column rename in a rolling deployment. Test an empty migration and upgrade from last released schema. Large backfills run as resumable jobs with checkpoints. Verify query plans with representative skew (large community/high-degree user), not empty databases.

Default retention proposals: expired registration/OAuth intents 24h; consumed reset/email tokens 7 days; terminal refresh tokens session expiry +7 days; delivered outbox/jobs 30 days; idempotency 24h; security audit and moderation cases 365 days; soft-deleted content/revisions 30 days before redaction; unbound media 24h. Legal retention/erasure requirements must be reviewed before public launch; these are engineering defaults, not legal advice. Tombstone UUIDs can remain without PII; evidence holds must be explicit, access-restricted and time-bounded. Backups expire under the same documented retention policy; restore must replay erasure manifests.
