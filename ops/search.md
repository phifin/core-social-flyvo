# PostgreSQL search operations

Search never authorizes or supplies stored text as a fallback. Run the existing
worker/receipts/fencing/ten-attempt retry/DEAD, no broker/engine/Redis/extension.
Current source policy denies immediately with the worker stopped. See
[contract](../docs/search-and-caching.md).

After live consumer registration, current ADMIN starts USER/COMMUNITY/POST backfill
with POST /api/v1/admin/search/rebuilds {"kind":"POST"}, existing exact Origin/
CSRF proof and Authorization Bearer (never URL token). Status GET on the returned
ID is also attributable/current ADMIN. No raw export route or staff-role bypass.
EXPORT scans owner keysets; RECONCILE refreshes indexed IDs/absence. Each≤100-source
batch verifies count/checksums and commits index/checkpoint/next job atomically.
DONE/counts describe examined work, not a frozen source snapshot or public total.

Resume with kind/resumeId/observed expectedVersion; reread after412, use the existing
run after active-kind409. Unique durable resume keys and expected versions make
stale passes harmless. For DEAD, inspect sanitized metadata under existing audited
restricted recovery, restore availability/configuration and resume. Do not reset
source versions, delete tombstones/receipts, rewrite events, copy private text or
disable checksum comparison. Physical absence is sticky; ordinary inactive sources
instead retain restorable versions. Keep index/backups private and queries/bodies
out of telemetry. SQL-view allowlist remains content-only.

Recorded default1000ms scheduler workload:10 posts/one author, notification+search
deliveries,20,275ms completion/max20.056s lag (<30s initial target). Not a sustained
throughput/SLA claim. Java21.0.12.1, Boot4.1.1, PG18.6, i7-1165G7/8 CPUs,7.5GiB
RAM/4GiB swap, shared desktop. Skew10,000 ballast +50 eligible posts returns50
with6 SQL calls including plain cutoff SQL, one source batch (background/
implicit transaction work excluded). Actual bound EXPLAIN after bulk insert chose
sequential scan4.338ms; after normal VACUUM ANALYZE selective document_fts GIN
bitmap scan0.098ms. Preserve both; never force planner settings/increase timeouts.
Monitor autovacuum/GIN pending lists; small fresh tables may legitimately scan.

Bounds stay2..200 chars/1..8 terms/page≤50/refill≤1000/200ms work, existing5s SQL
timeout and30/minute user/IP; no wildcard body scan, accent folding or stemming.
Do not raise pool/timeouts or cache permissions to hide failures. Multi-instance
public rate limiting remains a separate gate. Monitor low-cardinality
social.search.projection.lag{kind}, query.duration{kind}, examined/returned{kind},
existing oldest/retry/DEAD and audited rebuild state. No query/actor/source labels.
Final package checks include the unchanged feed pool10/50 all-success benchmark.
