package com.flyvo.socialcore.graph.api.event;

import java.util.UUID;

/** Pair revision orders facts across deletion/recreation; no profile/identity data. */
public record RelationshipChanged(
    UUID lowUserId,
    UUID highUserId,
    UUID actorId,
    UUID requestId,
    String requestStatus,
    boolean lowFollowsHigh,
    boolean highFollowsLow,
    boolean friends,
    boolean lowBlocksHigh,
    boolean highBlocksLow,
    boolean lowMutesHigh,
    boolean highMutesLow) {}
