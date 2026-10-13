-- Source-selective HOME traversal: covering personal chronology avoids reading
-- unrelated community bodies and random post heaps for high-degree author unions.
-- Account/graph/audience authorization remains current in the candidate statement.
CREATE INDEX post_personal_author ON content.post(author_id,published_at DESC,id DESC)
 INCLUDE(audience) WHERE status='PUBLISHED' AND community_id IS NULL;
