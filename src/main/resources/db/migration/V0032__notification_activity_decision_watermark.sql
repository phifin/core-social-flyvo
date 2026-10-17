-- Ordering progress does not prove that an origin activity was materialized.
-- Existing NULL-FK rows may be suppressed or pruned, so never infer pending work
-- from that FK or replay already-acknowledged historical activity during upgrade.
ALTER TABLE notification.source_state ADD COLUMN activity_decided_version bigint;
UPDATE notification.source_state SET activity_decided_version=latest_source_version;
ALTER TABLE notification.source_state ADD CONSTRAINT source_activity_decision_bound
 CHECK(activity_decided_version IS NULL OR
       (activity_decided_version>=0 AND activity_decided_version<=latest_source_version));
