# ADR-006 — Graph and interaction model

Status: Accepted, 2026-10-08.

Context: follow, friendship, block and mute have different directionality/lifecycles. Unlimited threaded trees and boolean likes underspecify real behavior.

Decision: separate tables/commands for follow, friend requests, canonical friendships, blocks and mutes. A block removes conflicting current relationships atomically and denies later interactions; unblock never restores them. Friendship neither implies follow nor accepts an opposite request automatically. Two-level comments (root/replies) have separate pagination. One typed reaction per actor/target; PUT replaces, DELETE deactivates. Stable tombstone/version supports asynchronous reorder handling.

Consequences: more explicit tables but clearer constraints/indexes and teachable behavior. Pair locks and uniqueness prevent contradictory graph state. Comment tombstones preserve visible children without exposing hidden text/identity. Reactions remain in content, with real typed target FKs and no generic unverified target IDs. Counts are derived/batched initially.

Revisit tree depth, custom reaction catalogs or separate interaction services only with product/scale requirements; preserve parent visibility checks and stable source versions.
