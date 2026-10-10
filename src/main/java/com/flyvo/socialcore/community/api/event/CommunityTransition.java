package com.flyvo.socialcore.community.api.event;

import java.time.Instant;
import java.util.UUID;

/** Semantic source references; no names, messages or moderation reasons. */
public record CommunityTransition(
    UUID communityId,
    UUID userId,
    UUID sourceId,
    String status,
    String role,
    Instant suspendedUntil,
    Long membershipVersion) {}
