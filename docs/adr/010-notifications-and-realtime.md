# ADR-010 — Semantic inbox and SSE

Status: Accepted, 2026-10-08.

Context: aggregated social notifications need deduplication, mutable actor contributions and correct unread races. A socket message alone is not an inbox.

Decision: persist semantic recipient/target/kind records plus actor contributions. Aggregate reactions in bounded windows, retain stable source versions, and use activityVersion/readVersion so new activity remains unread. Re-evaluate current source visibility, blocks/mutes/preferences and eligible actor counts at delivery/read. SSE provides authenticated invalidation hints; REST is authoritative. No WebSocket dependency for one-way delivery.

Consequences: aggregation costs more than a message-string column but supports localization, privacy changes and truthful grouped counts. SSE disconnect/replay loss is harmless because clients refresh REST. Multi-instance pub/sub is optional and never replaces durable storage. Access-token expiry closes streams; authorization-capable fetch streaming avoids tokens in URLs.

Revisit push/email social channels, complex grouping or websocket chat only with approved product requirements. Security emails remain separate from optional social preferences.
