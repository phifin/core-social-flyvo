package com.flyvo.socialcore.content.infrastructure.persistence;

import com.flyvo.socialcore.content.api.ContentCommands;
import com.flyvo.socialcore.content.api.ContentQueries;
import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy.Facts;
import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.content.domain.post.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcPosts implements PostRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final java.time.Clock clock;

  public JdbcPosts(JdbcTemplate jdbc, ObjectMapper json, java.time.Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(java.sql.ResultSet r, String name) throws java.sql.SQLException {
    var t = r.getObject(name, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  public Post find(UUID id, boolean lock) {
    if (lock
        && jdbc.queryForList("SELECT id FROM content.post WHERE id=? FOR UPDATE", id).isEmpty())
      return null;
    var posts = load(List.of(id));
    return posts.isEmpty() ? null : posts.getFirst();
  }

  public List<Post> load(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    String params = String.join(",", Collections.nCopies(ids.size(), "?"));
    var recipients = new HashMap<UUID, List<UUID>>();
    var media = new HashMap<UUID, List<Post.Attachment>>();
    var mentions = new HashMap<UUID, List<PostBody.Mention>>();
    jdbc.query(
        "SELECT * FROM content.post_recipient WHERE post_id IN (" + params + ") ORDER BY user_id",
        r -> {
          recipients
              .computeIfAbsent(r.getObject("post_id", UUID.class), k -> new ArrayList<>())
              .add(r.getObject("user_id", UUID.class));
        },
        ids.toArray());
    jdbc.query(
        "SELECT * FROM content.post_media WHERE post_id IN (" + params + ") ORDER BY position",
        r -> {
          media
              .computeIfAbsent(r.getObject("post_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new Post.Attachment(
                      r.getObject("media_id", UUID.class), r.getString("alt_text")));
        },
        ids.toArray());
    jdbc.query(
        "SELECT * FROM content.post_mention WHERE post_id IN ("
            + params
            + ") ORDER BY start_offset",
        r -> {
          mentions
              .computeIfAbsent(r.getObject("post_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new PostBody.Mention(
                      r.getObject("mentioned_user_id", UUID.class),
                      r.getInt("start_offset"),
                      r.getInt("end_offset")));
        },
        ids.toArray());
    return jdbc.query(
        "SELECT * FROM content.post WHERE id IN (" + params + ")",
        (r, n) -> {
          UUID id = r.getObject("id", UUID.class),
              author = r.getObject("author_id", UUID.class),
              community = r.getObject("community_id", UUID.class);
          return new Post(
              id,
              author,
              community,
              new PostBody(
                  r.getString("body"),
                  r.getString("link_url"),
                  mentions.getOrDefault(id, List.of())),
              new PostAudience(
                  author,
                  community,
                  PostAudience.Type.valueOf(r.getString("audience")),
                  recipients.getOrDefault(id, List.of())),
              media.getOrDefault(id, List.of()),
              r.getString("status"),
              r.getInt("revision_number"),
              instant(r, "published_at"),
              instant(r, "edited_at"),
              instant(r, "deleted_at"),
              instant(r, "removed_at"),
              r.getObject("removal_action_id", UUID.class),
              r.getLong("version"));
        },
        ids.toArray());
  }

  public void create(Post p) {
    jdbc.update(
        "INSERT INTO content.post(id,author_id,community_id,body,link_url,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,?,?,?,?,'PUBLISHED',1,?,?,?)",
        p.id(),
        p.author(),
        p.community(),
        p.body().text(),
        p.body().linkUrl(),
        p.audience().type().name(),
        time(p.published()),
        time(p.published()),
        time(p.published()));
    relations(p);
  }

  public void save(Post p) {
    jdbc.update(
        "UPDATE content.post SET body=?,link_url=?,audience=?,status=?,revision_number=?,edited_at=?,deleted_at=?,removed_at=?,removal_action_id=?,version=?,updated_at=? WHERE id=?",
        p.body().text(),
        p.body().linkUrl(),
        p.audience().type().name(),
        p.status(),
        p.revision(),
        time(p.edited()),
        time(p.deleted()),
        time(p.removed()),
        p.removalAction(),
        p.version(),
        time(clock.instant()),
        p.id());
    relations(p);
  }

  private void relations(Post p) {
    jdbc.update("DELETE FROM content.post_recipient WHERE post_id=?", p.id());
    for (var id : p.audience().recipients())
      jdbc.update("INSERT INTO content.post_recipient(post_id,user_id) VALUES (?,?)", p.id(), id);
    jdbc.update("DELETE FROM content.post_media WHERE post_id=?", p.id());
    for (int i = 0; i < p.media().size(); i++) {
      var m = p.media().get(i);
      jdbc.update(
          "INSERT INTO content.post_media(post_id,media_id,position,alt_text) VALUES (?,?,?,?)",
          p.id(),
          m.id(),
          i,
          m.altText());
    }
    jdbc.update("DELETE FROM content.post_mention WHERE post_id=?", p.id());
    for (var m : p.body().mentions())
      jdbc.update(
          "INSERT INTO content.post_mention(post_id,mentioned_user_id,start_offset,end_offset) VALUES (?,?,?,?)",
          p.id(),
          m.userId(),
          m.start(),
          m.end());
    jdbc.update("DELETE FROM content.post_hashtag WHERE post_id=?", p.id());
    for (var h : p.body().hashtags()) {
      UUID id =
          jdbc.queryForObject(
              "INSERT INTO content.hashtag(id,normalized_tag,display_tag) VALUES (?,?,?) ON CONFLICT(normalized_tag) DO UPDATE SET normalized_tag=excluded.normalized_tag RETURNING id",
              UUID.class,
              UUID.randomUUID(),
              h.normalized(),
              h.display());
      jdbc.update("INSERT INTO content.post_hashtag(post_id,hashtag_id) VALUES (?,?)", p.id(), id);
    }
  }

  public void revision(Post p, UUID editor, Instant now) {
    var audience =
        new ContentCommands.Audience(p.audience().type().name(), p.audience().recipients());
    var media =
        p.media().stream().map(m -> new ContentCommands.Attachment(m.id(), m.altText())).toList();
    jdbc.update(
        "INSERT INTO content.post_revision(post_id,revision_number,editor_id,body,link_url,audience_snapshot,media_ids_snapshot,mentions_snapshot,edited_at) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)",
        p.id(),
        p.revision(),
        editor,
        p.body().text(),
        p.body().linkUrl(),
        json.writeValueAsString(audience),
        json.writeValueAsString(media),
        json.writeValueAsString(p.body().mentions()),
        time(now));
  }

  public List<ContentQueries.Revision> revisions(
      UUID post, int limit, int before, Instant retainedSince) {
    return jdbc.query(
        "SELECT * FROM content.post_revision WHERE post_id=? AND revision_number<? AND edited_at>? ORDER BY revision_number DESC LIMIT ?",
        (r, n) ->
            new ContentQueries.Revision(
                r.getInt("revision_number"),
                r.getObject("editor_id", UUID.class),
                r.getString("body"),
                r.getString("link_url"),
                json.readValue(r.getString("audience_snapshot"), ContentCommands.Audience.class),
                List.of(
                    json.readValue(
                        r.getString("media_ids_snapshot"), ContentCommands.Attachment[].class)),
                instant(r, "edited_at")),
        post,
        before,
        time(retainedSince),
        limit);
  }

  private static final String FACTS =
      """
      WITH viewer AS (SELECT ?::uuid id, ?::timestamptz now)
      SELECT p.id,p.author_id,p.community_id,p.audience,p.status,p.published_at,
        (aa.user_id IS NOT NULL) author_active,(va.user_id IS NOT NULL) viewer_active,
        EXISTS(SELECT 1 FROM graph_api.blocks_v1 b WHERE (b.blocker_id=v.id AND b.blocked_id=p.author_id) OR (b.blocker_id=p.author_id AND b.blocked_id=v.id)) blocked,
        EXISTS(SELECT 1 FROM graph_api.follows_v1 f WHERE f.follower_id=v.id AND f.followed_id=p.author_id) following,
        EXISTS(SELECT 1 FROM graph_api.friendships_v1 f WHERE f.low_user_id=LEAST(v.id,p.author_id) AND f.high_user_id=GREATEST(v.id,p.author_id) AND v.id<>p.author_id) friends,
        EXISTS(SELECT 1 FROM content.post_recipient r WHERE r.post_id=p.id AND r.user_id=v.id) recipient,
        c.visibility community_visibility,c.status community_status,m.status membership_status,
        CASE WHEN c.owner_user_id=v.id THEN 'OWNER' ELSE m.role END membership_role,m.suspended_until
      FROM content.post p CROSS JOIN viewer v
      LEFT JOIN account_api.active_users_v1 aa ON aa.user_id=p.author_id
      LEFT JOIN account_api.active_users_v1 va ON va.user_id=v.id
      LEFT JOIN community_api.communities_v1 c ON c.community_id=p.community_id
      LEFT JOIN community_api.memberships_v1 m ON m.community_id=p.community_id AND m.user_id=v.id
      """;
  private static final String ELIGIBLE =
      """
      author_active AND status='PUBLISHED' AND (?::uuid IS NULL OR (viewer_active AND NOT blocked)) AND
      ((community_id IS NOT NULL AND audience='COMMUNITY' AND community_status IN ('ACTIVE','ARCHIVED')
        AND (?::uuid IS NULL OR (COALESCE(membership_status,'')<>'BANNED' AND NOT (COALESCE(membership_status,'')='ACTIVE' AND COALESCE(suspended_until> ?,false))))
        AND (community_visibility='PUBLIC' OR (community_visibility='PRIVATE' AND ?::uuid IS NOT NULL AND membership_status='ACTIVE' AND (suspended_until IS NULL OR suspended_until<=?))))
       OR (community_id IS NULL AND audience IN ('PUBLIC','FOLLOWERS','FRIENDS','CUSTOM','ONLY_ME') AND
        (author_id=? OR audience='PUBLIC' OR (?::uuid IS NOT NULL AND
          ((audience='FOLLOWERS' AND following) OR (audience='FRIENDS' AND friends) OR (audience='CUSTOM' AND recipient))))))
      """;

  public List<Key> candidates(
      UUID viewer, UUID author, UUID community, int limit, Instant asOf, Key after, Instant now) {
    var args =
        new ArrayList<Object>(
            Arrays.asList(
                viewer,
                time(now),
                viewer,
                viewer,
                time(now),
                viewer,
                time(now),
                viewer,
                viewer,
                time(asOf),
                author,
                author,
                community,
                community));
    String seek = "";
    if (after != null) {
      seek = " AND (published_at,id)<(?,?)";
      args.add(time(after.published()));
      args.add(after.id());
    }
    args.add(limit);
    return jdbc.query(
        "SELECT id,published_at FROM ("
            + FACTS
            + ") facts WHERE "
            + ELIGIBLE
            + " AND published_at<=? AND (?::uuid IS NULL OR author_id=?) AND (?::uuid IS NULL OR community_id=?)"
            + seek
            + " ORDER BY published_at DESC,id DESC LIMIT ?",
        (r, n) -> new Key(r.getObject("id", UUID.class), instant(r, "published_at")),
        args.toArray());
  }

  public Map<UUID, Facts> facts(UUID viewer, List<UUID> ids, Instant now) {
    if (ids.isEmpty()) return Map.of();
    var args = new ArrayList<Object>(Arrays.asList(viewer, time(now)));
    args.addAll(ids);
    var result = new HashMap<UUID, Facts>();
    jdbc.query(
        FACTS + " WHERE p.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
        r -> {
          result.put(
              r.getObject("id", UUID.class),
              new Facts(
                  r.getBoolean("author_active"),
                  r.getBoolean("viewer_active"),
                  r.getBoolean("blocked"),
                  r.getBoolean("following"),
                  r.getBoolean("friends"),
                  r.getBoolean("recipient"),
                  false,
                  r.getString("community_visibility"),
                  r.getString("community_status"),
                  r.getString("membership_status"),
                  r.getString("membership_role"),
                  instant(r, "suspended_until")));
        },
        args.toArray());
    return Map.copyOf(result);
  }

  public boolean pruneRevisions(UUID post, Instant cutoff) {
    return jdbc.update(
            "WITH old AS (SELECT post_id,revision_number FROM content.post_revision WHERE post_id=? AND edited_at<=? ORDER BY revision_number LIMIT 500) DELETE FROM content.post_revision r USING old WHERE r.post_id=old.post_id AND r.revision_number=old.revision_number",
            post,
            time(cutoff))
        == 500;
  }

  public void redactDeleted(UUID post, Instant now) {
    jdbc.update("DELETE FROM content.post_recipient WHERE post_id=?", post);
    jdbc.update("DELETE FROM content.post_media WHERE post_id=?", post);
    jdbc.update("DELETE FROM content.post_mention WHERE post_id=?", post);
    jdbc.update("DELETE FROM content.post_hashtag WHERE post_id=?", post);
    jdbc.update(
        "UPDATE content.post SET body='',link_url=NULL,audience=CASE WHEN community_id IS NULL THEN 'ONLY_ME' ELSE 'COMMUNITY' END,updated_at=? WHERE id=? AND status='DELETED'",
        time(now),
        post);
  }
}
