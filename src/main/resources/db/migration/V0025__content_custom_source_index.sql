-- Measured direct-CUSTOM branch: 10k addressed IDs must not read post body heaps
-- just to recover chronology. This partial covering owner index is not a feed
-- projection; current status/audience/recipient/owner policy is still rechecked.
CREATE INDEX post_custom_source ON content.post(id) INCLUDE(author_id,published_at)
 WHERE status='PUBLISHED' AND community_id IS NULL AND audience='CUSTOM';
