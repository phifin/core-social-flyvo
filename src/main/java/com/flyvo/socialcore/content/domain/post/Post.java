package com.flyvo.socialcore.content.domain.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Post(
    UUID id,
    UUID author,
    UUID community,
    PostBody body,
    PostAudience audience,
    List<Attachment> media,
    String status,
    int revision,
    Instant published,
    Instant edited,
    Instant deleted,
    Instant removed,
    UUID removalAction,
    long version) {
  public record Attachment(UUID id, String altText) {
    public Attachment {
      if (id == null || altText != null && altText.length() > 1000)
        throw new IllegalArgumentException("INVALID_ATTACHMENT");
    }
  }

  public Post {
    media = List.copyOf(media);
    if (media.size() > 10 || media.stream().map(Attachment::id).distinct().count() != media.size())
      throw new IllegalArgumentException("INVALID_ATTACHMENTS");
    if (status.equals("PUBLISHED")) body.requireNonempty(media.size());
    if (!status.equals(deleted != null ? "DELETED" : removed != null ? "REMOVED" : "PUBLISHED"))
      throw new IllegalArgumentException("INVALID_POST_STATE");
  }

  public Post edited(PostBody next, List<Attachment> attachments, Instant now) {
    return new Post(
        id,
        author,
        community,
        next,
        audience,
        attachments,
        status,
        revision + 1,
        published,
        now,
        deleted,
        removed,
        removalAction,
        version + 1);
  }

  public Post audience(PostAudience next, Instant now) {
    return new Post(
        id,
        author,
        community,
        body,
        next,
        media,
        status,
        revision + 1,
        published,
        now,
        deleted,
        removed,
        removalAction,
        version + 1);
  }

  public Post deleted(Instant now) {
    return new Post(
        id,
        author,
        community,
        body,
        audience,
        media,
        "DELETED",
        revision,
        published,
        edited,
        now,
        removed,
        removalAction,
        version + 1);
  }

  public Post moderated(boolean restore, UUID action, Instant now) {
    if (restore && deleted != null) throw new IllegalArgumentException("AUTHOR_DELETED");
    if (restore ? !status.equals("REMOVED") : !status.equals("PUBLISHED"))
      throw new IllegalArgumentException("INVALID_MODERATION_STATE");
    Instant removal = restore ? null : now;
    return new Post(
        id,
        author,
        community,
        body,
        audience,
        media,
        deleted != null ? "DELETED" : restore ? "PUBLISHED" : "REMOVED",
        revision,
        published,
        edited,
        deleted,
        removal,
        restore ? null : action,
        version + 1);
  }
}
