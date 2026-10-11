package com.flyvo.socialcore.content.domain.comment;

import java.time.Instant;
import java.util.UUID;

public record Comment(
    UUID id,
    UUID post,
    UUID author,
    UUID root,
    UUID replyTo,
    String body,
    String status,
    Instant created,
    Instant edited,
    Instant deleted,
    Instant removed,
    UUID removalAction,
    long version) {
  public Comment {
    if (id == null
        || post == null
        || author == null
        || created == null
        || version < 0
        || id.equals(root)
        || !java.util.Set.of("VISIBLE", "DELETED", "REMOVED").contains(status))
      throw new IllegalArgumentException();
    if (!"DELETED".equals(status) || !"".equals(body)) validateBody(body);
    if (!status.equals(deleted != null ? "DELETED" : removed != null ? "REMOVED" : "VISIBLE")
        || (removed == null) != (removalAction == null)) throw new IllegalArgumentException();
  }

  public static void validateBody(String body) {
    if (body == null || body.isBlank() || body.codePointCount(0, body.length()) > 5000)
      throw new IllegalArgumentException();
  }

  public Comment edit(String body, Instant now) {
    if (!status.equals("VISIBLE")) throw new IllegalStateException();
    return new Comment(
        id,
        post,
        author,
        root,
        replyTo,
        body,
        status,
        created,
        now,
        deleted,
        removed,
        removalAction,
        version + 1);
  }

  public Comment delete(Instant now) {
    return new Comment(
        id,
        post,
        author,
        root,
        replyTo,
        body,
        "DELETED",
        created,
        edited,
        now,
        removed,
        removalAction,
        version + 1);
  }

  public Comment moderate(boolean restore, UUID action, Instant now) {
    if (deleted != null || restore != (removed != null)) throw new IllegalStateException();
    return new Comment(
        id,
        post,
        author,
        root,
        replyTo,
        body,
        restore ? "VISIBLE" : "REMOVED",
        created,
        edited,
        null,
        restore ? null : now,
        restore ? null : action,
        version + 1);
  }
}
