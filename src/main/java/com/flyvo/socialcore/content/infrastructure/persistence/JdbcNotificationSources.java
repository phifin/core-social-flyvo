package com.flyvo.socialcore.content.infrastructure.persistence;

import com.flyvo.socialcore.content.api.ContentNotificationSources;
import com.flyvo.socialcore.content.api.ContentNotificationSources.Type;
import com.flyvo.socialcore.content.application.port.NotificationSourceRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcNotificationSources implements NotificationSourceRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcNotificationSources(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private String sources(Type type, int size) {
    String ids = String.join(",", Collections.nCopies(size, "?"));
    return switch (type) {
      case POST ->
          "SELECT p.id,p.version,p.status='PUBLISHED' active,p.author_id actor_id,'POST' target_type,p.id target_id,p.id post_id,p.author_id target_author_id,p.author_id post_author_id,NULL::uuid root_author_id,NULL::uuid reply_to_user_id,COALESCE((SELECT jsonb_agg(DISTINCT m.mentioned_user_id) FROM content.post_mention m WHERE m.post_id=p.id),'[]') mentions,NULL::text reaction_type FROM content.post p WHERE p.id IN ("
              + ids
              + ")";
      case COMMENT ->
          "SELECT c.id,c.version,c.status='VISIBLE' active,c.author_id actor_id,'COMMENT' target_type,c.id target_id,c.post_id,c.author_id target_author_id,p.author_id post_author_id,root.author_id root_author_id,c.reply_to_user_id,'[]'::jsonb mentions,NULL::text reaction_type FROM content.comment c JOIN content.post p ON p.id=c.post_id LEFT JOIN content.comment root ON root.id=c.root_comment_id WHERE c.id IN ("
              + ids
              + ")";
      case REACTION ->
          "SELECT r.id,r.version,(r.active AND (c.id IS NULL OR c.status='VISIBLE')) active,r.actor_id,CASE WHEN r.post_id IS NULL THEN 'COMMENT' ELSE 'POST' END target_type,COALESCE(r.post_id,r.comment_id) target_id,p.id post_id,COALESCE(c.author_id,p.author_id) target_author_id,p.author_id post_author_id,NULL::uuid root_author_id,NULL::uuid reply_to_user_id,'[]'::jsonb mentions,CASE WHEN r.active THEN r.type END reaction_type FROM content.reaction r LEFT JOIN content.comment c ON c.id=r.comment_id JOIN content.post p ON p.id=COALESCE(r.post_id,c.post_id) WHERE r.id IN ("
              + ids
              + ")";
    };
  }

  public List<ContentNotificationSources.Source> inspect(Type type, List<UUID> ids) {
    return jdbc.query(
        sources(type, ids.size()),
        (r, n) ->
            new ContentNotificationSources.Source(
                r.getObject("id", UUID.class),
                r.getLong("version"),
                r.getBoolean("active"),
                r.getObject("actor_id", UUID.class),
                r.getString("target_type"),
                r.getObject("target_id", UUID.class),
                r.getObject("post_id", UUID.class),
                r.getObject("target_author_id", UUID.class),
                r.getObject("post_author_id", UUID.class),
                r.getObject("root_author_id", UUID.class),
                r.getObject("reply_to_user_id", UUID.class),
                Arrays.asList(json.readValue(r.getString("mentions"), UUID[].class)),
                r.getString("reaction_type")),
        ids.toArray());
  }

  public Set<UUID> eligible(UUID recipient, Type type, String kind, List<UUID> ids, Instant now) {
    var args = new ArrayList<Object>();
    var at = now.atOffset(ZoneOffset.UTC);
    args.addAll(
        Arrays.asList(
            recipient, at, recipient, recipient, at, recipient, at, recipient, recipient));
    args.addAll(ids);
    args.add(recipient);
    String route =
        switch (kind) {
          case "MENTION" ->
              "EXISTS(SELECT 1 FROM content.post_mention m WHERE m.post_id=s.post_id AND m.mentioned_user_id=v.id)";
          case "COMMENT_ON_POST" -> "s.post_author_id=v.id";
          case "REPLY_TO_COMMENT" -> "(s.root_author_id=v.id OR s.reply_to_user_id=v.id)";
          case "REACTION_ON_CONTENT" -> "s.target_author_id=v.id";
          default -> throw new IllegalArgumentException();
        };
    String actor =
        "EXISTS(SELECT 1 FROM account_api.active_users_v1 a WHERE a.user_id=s.actor_id) AND NOT EXISTS(SELECT 1 FROM graph_api.blocks_v1 b WHERE (b.blocker_id=v.id AND b.blocked_id=s.actor_id) OR (b.blocked_id=v.id AND b.blocker_id=s.actor_id)) AND NOT EXISTS(SELECT 1 FROM graph_api.mutes_v1 m WHERE m.muter_id=v.id AND m.muted_id=s.actor_id)";
    return new HashSet<>(
        jdbc.query(
            "WITH eligible AS (SELECT * FROM ("
                + JdbcPosts.FACTS
                + ") facts WHERE "
                + JdbcPosts.ELIGIBLE
                + "), sources AS ("
                + sources(type, ids.size())
                + "), viewer AS (SELECT ?::uuid id) SELECT s.id FROM sources s JOIN eligible e ON e.id=s.post_id CROSS JOIN viewer v WHERE s.active AND s.actor_id<>v.id AND "
                + route
                + " AND "
                + actor,
            (r, n) -> r.getObject(1, UUID.class),
            args.toArray()));
  }
}
