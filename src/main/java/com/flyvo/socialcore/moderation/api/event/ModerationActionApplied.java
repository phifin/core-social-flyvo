package com.flyvo.socialcore.moderation.api.event;

import java.util.UUID;

public record ModerationActionApplied(
    UUID actionId, UUID communityId, UUID userId, String action) {}
