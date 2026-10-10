package com.flyvo.socialcore.community.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Semantic source references; no names, messages or moderation reasons. Status is the committed
 * community status for CommunityCreated/Updated, membership status for MemberJoinedCommunity/
 * MembershipChanged, and invitation/request status for admission events. SourceId is the community
 * ID except for admissions, which use their own UUID. Role/suspension/membershipVersion describe
 * the subject user's current membership, when present; the envelope version is the community
 * version.
 */
public record CommunityTransition(
    UUID communityId,
    UUID userId,
    UUID sourceId,
    String status,
    String role,
    Instant suspendedUntil,
    Long membershipVersion) {}
