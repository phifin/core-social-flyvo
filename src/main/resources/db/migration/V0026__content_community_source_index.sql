-- Measured joined-community proof requires author facts from the candidate row.
-- Cover them while preserving chronology: avoid sorting/fetching a hot community's
-- entire body heap before owner privacy and the eligible LIMIT.
CREATE INDEX post_community_source ON content.post(community_id,published_at DESC,id DESC)
 INCLUDE(author_id) WHERE status='PUBLISHED' AND audience='COMMUNITY';
