package com.flyvo.socialcore.content.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Content-owned, bounded source traversal for one erasure request. */
public interface ContentErasureStore {
  record Root(UUID id, UUID author, UUID community, List<UUID> media) {}

  record CommentRoot(UUID id, UUID post, UUID postAuthor) {}

  record Changed(UUID id, long version, String status, int revision) {}

  record ReferenceChange(Changed value, int examined) {}

  Root reference(UUID subject);

  ReferenceChange repairReference(UUID subject, UUID post, Instant now);

  List<Root> unmarkedPosts(UUID subject, int limit);

  Changed markPost(UUID subject, UUID post, Instant requestedAt, Instant now);

  List<CommentRoot> unmarkedComments(UUID subject, int limit);

  void guardPosts(List<UUID> ids);

  List<Changed> markComments(UUID subject, List<UUID> ids, Instant now);

  List<UUID> unerasedReactions(UUID subject, int limit);

  List<Changed> eraseReactions(UUID subject, List<UUID> ids, Instant now);

  UUID nextRedactionPost(UUID subject);

  int pruneRevisions(UUID post, int limit);

  List<UUID> postMedia(UUID post);

  void removeMedia(UUID post, List<UUID> expected);

  int removeRecipients(UUID post, Instant now);

  int removeMentions(UUID post, int limit);

  int finishPost(UUID subject, UUID post, Instant now);

  List<CommentRoot> unredactedComments(UUID subject, int limit);

  int redactComments(UUID subject, List<UUID> ids, Instant now);

  boolean residual(UUID subject);
}
