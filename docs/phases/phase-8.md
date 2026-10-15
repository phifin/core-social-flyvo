# Phase 8 — search and scoped moderation operations

Status: slice C implemented/verified, D and final package checks remain. Not
accepted or READY_FOR_REVIEW. Phase7 internal gate passed before C, B code
`af0742750cd1f1b00a02a1afcd6aa3d80d13db74`; see [Phase7 evidence](phase-7.md).
Real C/D hashes are recorded in the fixed mailbox after their code commits.

## C — source-authorized PostgreSQL search

V0028 introduces source/account version vectors, immutable source identity,
restorable and confirmed-missing tombstones, restricted generated simple tsvector/
GIN/name/handle indexes and owner/scope indexes. Rebuild EXPORT/RECONCILE is bounded,
resumable and attributable ADMIN; source snapshots/upserts/count/checksum proof/
checkpoint/next job/fenced ack remain atomic. Standard SHA-256 is diagnostic,
not auth. Source API exports≤100 IDs, never private repositories/foreign SQL.
No new dependency, engine, extension, permission cache or alternate worker.

Authenticated users/communities/posts query plain bounded terms; name/handle
exact/prefix matching supplements simple full text. Posts optionally scope to a
currently authorized community. Viewer/query/type/filter/size-bound encrypted
rank/UUID/cutoff cursors expire30 minutes, advance examined keys and permit empty
continued pages/omissions under concurrent projection updates. DB time owns both
cutoff/updated_at. Refill100-row batches/≤1000/200ms work, page≤50, existing5s SQL
timeout and independent30/minute user/IP budgets.

Owner hydration precedes≤240-code-point snippets/names. Canonical content policy
and body/relations share a statement; profile scope/text load together before
current batched account/graph facts. Private community discovery never grants
post/member access. No stored projection fallback, stale highlights, public score
or hidden total. Vietnamese fixtures document accent preservation/no unaccent or
stemming; this is not language-specific relevance support.

Contracts/configuration: [search](../search-and-caching.md), [operations](../../ops/search.md),
[OpenAPI](../api/openapi.yaml), [plan](../work-packages/phase-7-8-plan.md).

### C verification and resource evidence

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`
passes317 tests (55 fast +262 integration), zero failures/errors/skips, no forced
fork shutdown. Log `.local/evidence/p78/search-full-final.log`,5:47 on2026-10-15.
All earlier feed/privacy/worker/media/auth tests remain:19 feeds,19 foundation,
30 authentication, plus Phase7. Fresh28 migrations and upgrade from27 preserve
all prior checksums/data; architecture/OpenAPI/SQL allowlist pass.

SearchIT15 real PG cases cover current privacy with worker stopped, private-body
coherence under concurrent edit, blocks/account status, private-card versus private
post membership/revocation, cursor ties/tamper/context/expiry, injection/query/rate
bounds, one-batch50-post hydration,1000 missing sources/refill/no fallback,
source/account version vectors, duplicate state, missing versions/tombstones,
concurrent stale/current upserts, old export versus live edit/restartable rebuild,
checkpoint rollback/retry, count/checksum Unicode corruption/repair, attributable
current-ADMIN access, audit-write rollback and no HTTP export route.

SearchMeasurementsIT2 uses actual default1000ms scheduler (not manual drain for
lag):10 posts/one author with notification+search deliveries reached index20,275ms,
max lag20.055668514s, under initial30s target. Query fixture10,000 ballast+50
authorized posts returns50 in6 SQL statements, one current owner batch; background
worker and implicit transaction commands are excluded, plain cutoff SQL included.
Actual bound EXPLAIN fresh sequential scan4.338ms/maintained selective GIN bitmap
scan0.098ms after normal VACUUM ANALYZE, no forced planner switches or timeout/pool
increase. Both plans retained; GIN pending entries affect fresh small-table choice.
Hardware/software as Phase7: i7-1165G7/8 CPUs,7.5GiB RAM/4GiB swap, Java21.0.12.1,
Boot4.1.1/PG18.6/shared desktop. No production SLA/sustained capacity claim.

Earlier targeted63 PASS precedes later integrity/operator cases. First full C
attempt failed two harness assumptions: an old contract test consumed new search
backlog instead of its own delivery, and checksum failure was translated by
Spring's repository boundary. The fixture now explicitly prioritizes its real
delivery/retry while preserving exact receipt/effect/dedup/lease assertions;
checksum test asserts translated denial and repairs the payload. Intermediate
compile/query/parser/harness failures remain in ignored evidence, not relabeled
passes. Heavy pool10/50 all-success feed benchmark, final Compose/scanners and D
are still package gates; no phase acceptance, push, deployment or Phase9 work.
