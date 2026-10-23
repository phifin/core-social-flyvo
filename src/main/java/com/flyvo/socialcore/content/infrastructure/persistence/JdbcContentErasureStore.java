package com.flyvo.socialcore.content.infrastructure.persistence;

import com.flyvo.socialcore.content.application.port.ContentErasureStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcContentErasureStore implements ContentErasureStore {
  private final JdbcTemplate jdbc;

  public JdbcContentErasureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private static Root root(ResultSet r, int n) throws SQLException {
    return new Root(
        r.getObject("id", UUID.class),
        r.getObject("author_id", UUID.class),
        r.getObject("community_id", UUID.class),
        List.of((UUID[]) r.getArray("media_ids").getArray()));
  }

  private static Changed changed(ResultSet r, int n) throws SQLException {
    return new Changed(
        r.getObject("id", UUID.class),
        r.getLong("version"),
        r.getString("status"),
        r.getInt("revision_number"));
  }

  private static String mediaArray() {
    return "ARRAY(SELECT media_id FROM content.post_media WHERE post_id=p.id ORDER BY media_id) media_ids";
  }

  public Root reference(UUID subject) {
    var rows =
        jdbc.query(
            "SELECT p.id,p.author_id,p.community_id,ARRAY[]::uuid[] media_ids"
                + " FROM content.post p JOIN ("
                + "SELECT post_id FROM content.post_recipient WHERE user_id=? "
                + "UNION SELECT post_id FROM content.post_mention WHERE mentioned_user_id=?"
                + ") refs ON refs.post_id=p.id ORDER BY p.id LIMIT 1",
            JdbcContentErasureStore::root,
            subject,
            subject);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public ReferenceChange repairReference(UUID subject, UUID post, Instant now) {
    int recipients =
        jdbc.queryForObject(
            "SELECT count(*) FROM content.post_recipient WHERE post_id=? AND user_id=?",
            Integer.class,
            post,
            subject);
    int mentions =
        jdbc.update(
            "DELETE FROM content.post_mention WHERE post_id=? AND mentioned_user_id=? AND start_offset IN "
                + "(SELECT start_offset FROM content.post_mention WHERE post_id=? AND mentioned_user_id=? ORDER BY start_offset LIMIT ?)",
            post,
            subject,
            post,
            subject,
            100 - recipients);
    if (recipients == 0 && mentions == 0) return null;
    if (recipients > 0) {
      int total =
          jdbc.queryForObject(
              "SELECT count(*) FROM content.post_recipient WHERE post_id=?", Integer.class, post);
      if (total == 1)
        jdbc.update(
            "UPDATE content.post SET audience='ONLY_ME' WHERE id=? AND audience='CUSTOM'", post);
      jdbc.update(
          "DELETE FROM content.post_recipient WHERE post_id=? AND user_id=?", post, subject);
    }
    var value =
        jdbc.queryForObject(
            "UPDATE content.post SET version=version+1,updated_at=? WHERE id=? "
                + "RETURNING id,version,status,revision_number",
            JdbcContentErasureStore::changed,
            time(now),
            post);
    return new ReferenceChange(value, mentions + recipients);
  }

  public List<Root> unmarkedPosts(UUID subject, int limit) {
    return jdbc.query(
        "SELECT p.id,p.author_id,p.community_id,"
            + mediaArray()
            + " FROM content.post p WHERE p.author_id=? AND NOT p.erasure_origin "
            + "ORDER BY p.id LIMIT ?",
        JdbcContentErasureStore::root,
        subject,
        limit);
  }

  public Changed markPost(UUID subject, UUID post, Instant requestedAt, Instant now) {
    var rows =
        jdbc.query(
            "UPDATE content.post SET erasure_origin=true,erasure_requested_at=?,"
                + "status='DELETED',deleted_at=COALESCE(deleted_at,?),version=version+1,updated_at=? "
                + "WHERE id=? AND author_id=? AND NOT erasure_origin "
                + "RETURNING id,version,status,revision_number",
            JdbcContentErasureStore::changed,
            time(requestedAt),
            time(requestedAt),
            time(now),
            post,
            subject);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static CommentRoot comment(ResultSet r, int n) throws SQLException {
    return new CommentRoot(
        r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class));
  }

  public List<CommentRoot> unmarkedComments(UUID subject, int limit) {
    return jdbc.query(
        "SELECT c.id,c.post_id,p.author_id FROM content.comment c JOIN content.post p ON p.id=c.post_id "
            + "WHERE c.author_id=? AND c.status<>'DELETED' ORDER BY c.id LIMIT ?",
        JdbcContentErasureStore::comment,
        subject,
        limit);
  }

  public void guardPosts(List<UUID> ids) {
    if (ids.isEmpty()) return;
    var found =
        jdbc.queryForList(
            "SELECT id FROM content.post WHERE id IN ("
                + params(ids.size())
                + ") ORDER BY id FOR UPDATE",
            UUID.class,
            ids.toArray());
    if (found.size() != ids.size()) throw new IllegalStateException("CONTENT_ERASURE_ROOT_MISSING");
  }

  private static String params(int size) {
    return String.join(",", Collections.nCopies(size, "?"));
  }

  public List<Changed> markComments(UUID subject, List<UUID> ids, Instant now) {
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    args.add(time(now));
    args.add(time(now));
    args.add(subject);
    args.addAll(ids);
    return jdbc.query(
        "UPDATE content.comment SET status='DELETED',deleted_at=COALESCE(deleted_at,?),"
            + "updated_at=?,version=version+1 WHERE author_id=? AND id IN ("
            + params(ids.size())
            + ") AND status<>'DELETED' RETURNING id,version,status,0 AS revision_number",
        JdbcContentErasureStore::changed,
        args.toArray());
  }

  public List<UUID> unerasedReactions(UUID subject, int limit) {
    return jdbc.queryForList(
        "SELECT id FROM content.reaction WHERE actor_id=? AND NOT erased_by_account "
            + "ORDER BY id LIMIT ?",
        UUID.class,
        subject,
        limit);
  }

  public List<Changed> eraseReactions(UUID subject, List<UUID> ids, Instant now) {
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    args.add(time(now));
    args.add(subject);
    args.addAll(ids);
    return jdbc.query(
        "UPDATE content.reaction SET active=false,type=NULL,erased_by_account=true,"
            + "updated_at=?,version=version+1 WHERE actor_id=? AND id IN ("
            + params(ids.size())
            + ") AND NOT erased_by_account RETURNING id,version,'INACTIVE'::varchar AS status,0 AS revision_number",
        JdbcContentErasureStore::changed,
        args.toArray());
  }

  public UUID nextRedactionPost(UUID subject) {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM content.post WHERE author_id=? AND erasure_origin "
                + "AND erasure_redacted_at IS NULL ORDER BY id LIMIT 1",
            UUID.class,
            subject);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  public int pruneRevisions(UUID post, int limit) {
    return jdbc.update(
        "DELETE FROM content.post_revision WHERE post_id=? AND revision_number IN "
            + "(SELECT revision_number FROM content.post_revision WHERE post_id=? "
            + "ORDER BY revision_number LIMIT ?)",
        post,
        post,
        limit);
  }

  public List<UUID> postMedia(UUID post) {
    return jdbc.queryForList(
        "SELECT media_id FROM content.post_media WHERE post_id=? ORDER BY media_id",
        UUID.class,
        post);
  }

  public void removeMedia(UUID post, List<UUID> expected) {
    if (!postMedia(post).equals(expected))
      throw new IllegalStateException("CONTENT_MEDIA_MANIFEST_MISMATCH");
    jdbc.update("DELETE FROM content.post_media WHERE post_id=?", post);
  }

  public int removeRecipients(UUID post, Instant now) {
    int count =
        jdbc.queryForObject(
            "SELECT count(*) FROM content.post_recipient WHERE post_id=?", Integer.class, post);
    if (count > 100) throw new IllegalStateException("CONTENT_RECIPIENT_BOUND_BROKEN");
    if (count == 0) return 0;
    jdbc.update(
        "UPDATE content.post SET audience='ONLY_ME',version=version+1,updated_at=? "
            + "WHERE id=? AND audience='CUSTOM'",
        time(now),
        post);
    jdbc.update("DELETE FROM content.post_recipient WHERE post_id=?", post);
    return count;
  }

  public int removeMentions(UUID post, int limit) {
    return jdbc.update(
        "DELETE FROM content.post_mention WHERE post_id=? AND (mentioned_user_id,start_offset) IN (SELECT mentioned_user_id,start_offset FROM content.post_mention WHERE post_id=? ORDER BY mentioned_user_id,start_offset LIMIT ?)",
        post,
        post,
        limit);
  }

  public int finishPost(UUID subject, UUID post, Instant now) {
    var tags =
        jdbc.queryForList(
            "SELECT hashtag_id FROM content.post_hashtag WHERE post_id=?", UUID.class, post);
    if (tags.size() > 10) throw new IllegalStateException("CONTENT_HASHTAG_BOUND_BROKEN");
    int examined = jdbc.update("DELETE FROM content.post_hashtag WHERE post_id=?", post);
    if (!tags.isEmpty())
      examined +=
          jdbc.update(
              "DELETE FROM content.hashtag h WHERE h.id IN ("
                  + params(tags.size())
                  + ") AND NOT EXISTS(SELECT 1 FROM content.post_hashtag ph WHERE ph.hashtag_id=h.id)",
              tags.toArray());
    int changed =
        jdbc.update(
            "UPDATE content.post SET body='',link_url=NULL,erasure_redacted_at=?,"
                + "version=version+1,updated_at=? WHERE id=? AND author_id=? AND erasure_origin "
                + "AND erasure_redacted_at IS NULL AND NOT EXISTS"
                + "(SELECT 1 FROM content.post_revision WHERE post_id=?) AND NOT EXISTS"
                + "(SELECT 1 FROM content.post_media WHERE post_id=?) AND NOT EXISTS"
                + "(SELECT 1 FROM content.post_recipient WHERE post_id=?)",
            time(now),
            time(now),
            post,
            subject,
            post,
            post,
            post);
    if (changed != 1) throw new IllegalStateException("CONTENT_REDACTION_INCOMPLETE");
    return examined + 1;
  }

  public List<CommentRoot> unredactedComments(UUID subject, int limit) {
    return jdbc.query(
        "SELECT c.id,c.post_id,p.author_id FROM content.comment c JOIN content.post p ON p.id=c.post_id "
            + "WHERE c.author_id=? AND NOT c.erased_by_account "
            + "ORDER BY c.id LIMIT ?",
        JdbcContentErasureStore::comment,
        subject,
        limit);
  }

  public int redactComments(UUID subject, List<UUID> ids, Instant now) {
    if (ids.isEmpty()) return 0;
    var args = new ArrayList<Object>();
    args.add(time(now));
    args.add(subject);
    args.addAll(ids);
    return jdbc.update(
        "UPDATE content.comment SET body='',status='DELETED',erased_by_account=true,version=version+1,updated_at=? "
            + "WHERE author_id=? AND id IN ("
            + params(ids.size())
            + ") AND status='DELETED' AND NOT erased_by_account",
        args.toArray());
  }

  public boolean residual(UUID subject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM content.post p WHERE p.author_id=? "
                + "AND (NOT p.erasure_origin OR p.erasure_redacted_at IS NULL OR p.body<>'' OR p.link_url IS NOT NULL)) "
                + "OR EXISTS(SELECT 1 FROM content.comment WHERE author_id=? AND (NOT erased_by_account OR status<>'DELETED' OR body<>'')) "
                + "OR EXISTS(SELECT 1 FROM content.reaction WHERE actor_id=? AND NOT erased_by_account) "
                + "OR EXISTS(SELECT 1 FROM content.post_recipient WHERE user_id=?) "
                + "OR EXISTS(SELECT 1 FROM content.post_mention WHERE mentioned_user_id=?)",
            Boolean.class,
            subject,
            subject,
            subject,
            subject,
            subject));
  }
}
