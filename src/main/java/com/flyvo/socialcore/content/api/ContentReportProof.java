package com.flyvo.socialcore.content.api;

import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.UUID;

/** Transaction-required ordinary source proof, never a staff read bypass. */
public interface ContentReportProof {
  record Proof(UUID communityId, UUID authorId, UUID parentAuthorId, String body, long version) {}

  Proof reportProof(ActorContext actor, UUID postId, UUID commentId);

  /** Metadata only, for moderation's structural guards before creating a standalone root. */
  java.util.List<UUID> structuralUsers(UUID postId);
}
