# Phase 8 — search and scoped moderation operations

Status: C and all D implemented locally; final full340-test, app/media Compose,
pool10/50 all3200-request and secret-scan gates pass on2026-10-16. Pending main
review, **neither phase accepted**. Main's [ADR-016 acceptance](../adr/016-moderation-private-payload-retention.md)
at152a7c9 resolved P78-D1's design only. Phase7 internal gate passed before C, B code
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
were still package gates at C; their final evidence follows below. No phase
acceptance, push, deployment or Phase9 work.

## Historical D account-lifecycle prerequisite — cd51ecae

V0029 adds account-owned authentication epochs and identity-owned session issuance
epochs, without changing V0001–V0028. Suspension increments the epoch; guarded
versioned reinstatement never resets it. Existing ACTIVE sessions keep epoch0;
already SUSPENDED accounts begin at1 so upgrade cannot revive their old sessions.
Current Bearer/refresh/recent-auth checks compare epochs synchronously. No reverse
account→identity dependency, credential copy or asynchronous authorization cache.
Complete sorted actor/target exclusive account guards, current ADMIN, expected
source version, self/peer ADMIN denial and valid state precede mutation. Status,
epoch, audit and AccountStatusChanged v1 commit or roll back together.

This checkpoint was an owner API prerequisite only: no new platform moderation HTTP endpoint,
assignment/filter/history/hold/retention operation or key-rotation service is
claimed. Roles, ownership, community state and deleted content are not modified.

### Verification2026-10-16

- Targeted verify:14 fast +67 integration =81 PASS, zero failures/errors/skips,
  `.local/evidence/p78/d-lifecycle-targeted.log`. This precedes the extra upgrade
  fixture for accounts already suspended before V0029.
- Final `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`:
  **55 fast +268 integration =323 PASS**, zero failures/errors/skips, no forced
  fork exit,5:02 finishing18:32:55+07. Log `.local/evidence/p78/d-lifecycle-full.log`.
  Includes fresh29/upgrade28 with all old checksums and ACTIVE/SUSPENDED session
  data preserved, nonnegative epoch constraints, all22 report immutability/scope/
  atomicity regressions,19 feed tests and all prior Phases1–7/media/worker tests.
- New cases: old Bearer/refresh/recent proof denied after immediate reinstatement,
  fresh login/refresh accepted with new epoch; self/peer ADMIN, stale version,
  invalid state and revoked authority denied; independent audit/outbox failures
  roll status/version/epoch/effects back; password-verified login loses to suspension
  before its lifecycle guard; real native SSE cannot miss suspend→reinstate.
- Full-suite SSE suspend/reinstate closure5098ms (target<15s), replayed stream
  token401. Ordinary logout5086/session5077/logout-all5091/suspend5093ms;
  expiry2932ms, DB outage8004ms, slow readers250/201/220ms with queue≤2 and native
  resources0; open-stream graceful shutdown19ms. No resource assertion weakened.
- Existing actual search scheduler fixture reruns:10 posts/one owner/poll1000ms,
  completion20,244ms/max lag20.151612654s;50 hydrated results/6 SQL statements
  under10,000 ballast fixture. Not a capacity/SLA claim. Hardware/software and
  pool/timeout limits unchanged from C.

Neither phase was accepted. Full package app/media Compose startup, heavy pool10/50
all3200-success feed benchmark and post-D completion evidence remain required;
the integration suite is not a substitute for those operational gates.

Code: C `79da0ca692845a55e80cc7f67d6a4e5663845864`; D prerequisite
`cd51ecae3d92a402fa8c70b1102501cafb857ffa`. After this code commit, pinned redacted
gitleaks scans53 history commits with no leaks; staged patch is clean and synthetic
generic-api-key positive control detects its expected fixture. No scanner/history
weakening. Main subsequently accepted ADR-016's retention storage/hold boundary;
its implementation conditions and final gates are mandatory. No moderation
trigger, private payload or cleanup had changed at that checkpoint.

## D completed under ADR-016 — 2026-10-16

V0030 copies report ciphertext/key IDs byte-for-byte and complete action private
JSON into owned one-to-one payloads, proves counts/values before dropping old
columns, and aborts atomically on mismatch. V0031 adds assignment, explicit
engineering holds, standalone retention roots and append-only case history.
V0001–V0029 remain byte-for-byte unchanged. Existing action metadata and platform
audit rejection functions/triggers remain unconditional and unchanged. Deferred
completeness, no DELETE/reinsert/root reassignment/ciphertext substitution and
irreversible tombstones close absent-row/restoration paths. Legacy history keeps
unknown versions NULL rather than inventing them.

New action details use existing purpose-bound authenticated encryption. Existing
durable jobs encrypt at most100 legacy payloads in one root per transaction,
decrypt and compare the complete JSON before removing plaintext. Failure retains
legacy data. DB guards prove identity/shape/transitions, **not cryptographic
equivalence** or protection against database owner/superuser. Loaded local keys
introduce no network call under locks. Retain keys while any live ciphertext
references them; the existing single-key configuration is not an online keyring
or rotation service.

Retention uses authoritative DB time: terminal resolved_at+365days or standalone
created_at+365days; unresolved/unexpired-held roots excluded. Current exact-scope
staff/global ADMIN hold create/renew/release requires If-Match, bounded reason,
explicit expiry≤365days and mandatory audit. Case-linked actions share the case
root, never a competing action hold. Root-before-payload serialization covers
both race orders. NONE→STARTED→DONE cannot reverse; STARTED rejects later holds;
one-root/≤100-payload passes resume until no private bytes remain before DONE.
Reads return explicit redacted fields, never reconstructed evidence/content.

Assignment validates currently eligible staff, grants no permission and shares
case version with resolution/hold. Queue supports status/assignedTo/unassigned;
history is bounded, current-scope-authorized and attributable. Metadata scope
proof precedes **any** private payload query or report root lock, including legacy
history and evidence. Complete account guards precede community/source/root
locks. Separate account HTTP operations use the existing exclusive versioned
owner command; an audited ADMIN GET supplies minimal state/version+ETag, never
credentials or auth epoch. Suspend/reinstate retains current roles, ownership,
community state and deleted content; old credentials remain invalid after restore.

Protected ADMIN metrics reuse the existing Prometheus registry, with attributable
current authorization and no-store responses; actuator internals remain denied.
Committed-only crypto/redaction throughput, failures, legacy/partial-root progress
and observation age accompany existing worker/search/SSE/feed metrics. An actual
12-panel static [dashboard](../../ops/dashboards/phase-7-8.json) and
[runbook](../../ops/moderation.md) cover controlled write-stop migration, bounded
resume/retry/DEAD, key lifetime and restricted data-source setup. No monitoring
service, long-lived scraper identity or public gateway is provisioned.

### Final functional, migration and rollback evidence

Authoritative command (same executable source as Compose/benchmark):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify
```

**340 PASS =57 fast +283 PostgreSQL integration**, zero failures/errors/skips,
5:19, ending2026-10-16 20:07:05+07. No forced fork termination. Log
`.local/evidence/p78/package-full-verify-scope-final.log`. Spotless, Enforcer,
7 architecture/3 persistence-boundary tests and OpenAPI path/schema/ref assertions
pass. Existing SQL consumer allowlist is unchanged. All19 feeds,34 authentication,
23 reports,21 foundation and prior Phases1–7/privacy/media/worker tests pass.

New ModerationOperationsIT13 includes real V0029 legacy fixtures upgraded to31:

- Fresh31/upgrade29 preserve checksums, Unicode/nested/null legacy JSON,
  ciphertext/key bytes, report versions and exact action/audit guard definitions.
  Raw SQL attacks the **new** payloads and metadata, not removed-column errors:
  substitution/delete/root reassignment/absent payload/new plaintext/restoration,
  premature/unresolved/held redaction, independent linked-action root and early DONE.
- 205 legacy rows: crypto/key/decrypt-verification/audit failures roll back; real
  100/100/5 passes converge to zero. Complete values decrypt equal originals.
- 206 case payloads:100/100/6 passes, unresolved/active holds excluded, release/
  expiry tested, repeat is no-op, late hold denied. Both real hold-wins and
  cleanup-wins races serialize on the common root.102-payload worker fixture proves
  audit failure, next-job insertion failure and lost lease/fenced ack roll back
  payload/root/history/next-job **and committed metrics**, then retry converges.
- Current/revoked/cross-scope staff, invalid assignee, filters, terminal assignment,
  real assignment-versus-resolution race and stale versions. Wrong-scope evidence/
  resolve/history/action spies prove no ciphertext/legacy query, owner payload
  read or root lock. Hold edits/queue/history/evidence/metrics fail closed on audit
  failure; independent outbox failure rolls assignment back; assignment keeps holds.
- HTTP ADMIN minimal account state+ETag and suspend/reinstate: missing version428,
  stale412, self/peer403, old sessions401 after restore, no role/ownership/content
  restoration; injected action-payload failure rolls owner status/version/epoch,
  audit/outbox back. Real metrics exporter anonymous401/ordinary user403, no target
  labels; maintenance/assignment/hold/history require current auth/CSRF/scope/version.

Targeted final scope gate50 PASS (14 fast+36 PG), log
`d-scope-proof-targeted.log`. Earlier full339 passes preceded that final regression
and are not substituted for340. Retained failed D attempts cover Java generic
test inference and two fixture assumptions (identity attack hit version guard
first; prior failed fixture changed selected retention root). Corrected attacks
advance version to reach identity guard, and deterministic root selection is
asserted before rollback injection. No denial/assertion/scanner weakened; all
failed attempts remain under ignored `.local/evidence/p78`.

Latest actual scheduler search:10 posts/one owner,1000ms polling, simultaneous
notification+search deliveries:20,264ms completion/max lag20.131551974s (<30s);
10,000-ballast/50-result hydration uses6 SQL statements. Native SSE old-session
suspend→reinstate closure5060ms; logout5100/session5083/logout-all5096/suspend5092;
expiry2315ms, outage8064ms, slow readers259/203/217ms, queue≤2, native async/servlet
resources0; open-stream shutdown17ms. Existing caps/timeouts/privacy checks unchanged.

### Real app/media Compose gate

The tracked Dockerfile/Compose and provided key/bootstrap scripts run under isolated
project `social-p78-gate` with app/media profiles, MEDIA_ENABLED=true and a separate
Silo data bind, PG volume and ports (app18078/PG15478/storage19078). Original
storage/data/keys are untouched; no rotation or deletion. Final logs:
`compose-final-silo.log`, `compose-final-bootstrap.log`, `compose-final-app.log`,
`compose-final-stop.log` under `.local/evidence/p78`.

Actual `silo` startup, `silo-init`, image build and postgres/mailpit/app startup
all exit0. HTTP readiness/liveness both UP (the app image has no Docker healthcheck,
so running alone is not readiness proof). App and Silo UID/GID1000:1000, read-only
root FS,1GiB/2CPU limits. Bootstrap verifies private/encrypted/versioned buckets
and separated API/worker/admin identities; anonymous bucket listing403. Anonymous
protected moderation metrics401 and actuator Prometheus401. Real PG reports31
successful migrations and0 legacy action payloads. App is explicitly media-enabled.
App stopped before dependencies after probes; containers/volumes/isolated data
remain stopped and recoverable. This is local Compose proof, not production rollout.

### Final pool10/50 benchmark — all3,200 outcomes

Exact committed FeedBenchmarkTool invoked with `-Dfeed.benchmark=true`, default
pool10 and comparison50,50 concurrent readers,1,000,000 seeded posts. Java21.0.12.1,
PG18.6 pinned image, Boot4.1.1/Tomcat11.0.27, i7-1165G7/8 logical CPUs,7.5GiB RAM/
4GiB swap, shared desktop. JVM max2,011,168,768 bytes; seed210.721s, DB976,819,903
bytes. Task Compose stopped before benchmark; no pool/timeout/cache changes.
Maven command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply test -Dtest=FeedsTest,ChronologicalRankerTest,OpenApiContractTest,ArchitectureTest,PersistenceBoundaryTest failsafe:integration-test failsafe:verify -Dit.test=FeedBenchmarkTool -Dfeed.benchmark=true
```

15 prerequisite tests +1 heavy tool PASS, zero failures/errors/skips,4:51 total,
ending20:20:31+07. `.local/evidence/p78/package-feed-benchmark.log` retains
environment, bound plans, SQL shapes, outcomes, pool waits and heap snapshots.
Both pools each run500 first+500 steady+6×100 sparse attempts: **3,200 attempted,
3,200 successful,0 failed**, all16 scenario outcome lines independently summed.
Percentiles are success-only, with no failed attempts to omit.

| Pool/scenario | p50 ms | p95 ms | p99 ms | SQL/attempt |
|---|---:|---:|---:|---:|
|10 first500|1092.108|2259.538|3118.709|15|
|10 steady500|888.059|1884.616|2452.184|15|
|50 first500|1191.109|1898.888|2225.828|15|
|50 steady500|986.081|1411.877|1564.864|15|

| Sparse scenario (100 each/pool) | Pool10 p95 ms | Pool50 p95 ms | SQL/attempt |
|---|---:|---:|---:|
|no_sources|112.466|49.564|6|
|rare_old|345.723|194.893|12|
|last_page|540.792|408.434|11|
|mostly_rejected|305.287|227.355|6|
|overlap|1673.081|1304.184|15|
|exhausted|272.354|234.833|5|

Dense examined/returned10,000/10,000 each scenario, ratio1. Community/timeline
single-call samples61.334/38.032ms,20 results each, **not percentile measurements**.
Initial dense p95<500ms remains unmet; all-success gate passes, not latency/SLA or
production capacity. Sparse overlap and pool10 last_page also exceed500ms. No
performance result is hidden or treated as an accepted production target.

### Integrity/scans, delivery and remaining external gates

Executable SHA-256 manifest `.local/evidence/p78/final-source-sha256.txt` covers
src/pom/Dockerfile/Compose/local ops/dashboard and passes before/after Compose and
benchmark. No executable change follows full340; only evidence/status docs follow.
Pinned gitleaks v8.28.0 at the existing image digest, redacted full-history and
staged scans pass; synthetic generic-api-key positive control detects its expected
outside-git fixture. Both exact historical fingerprints are unchanged. Real
code/mailbox hashes and final history counts are recorded in the fixed mailbox
after commits, never a fabricated self-reference.

Controlled production write-stop, external object-storage/provider/decoder proof,
shared-key lifecycle/backup restore-erasure, audit metadata retention, protected
admin/metrics gateway and public-launch policy remain separate gates. Engineering
holds are not legal holds; this implements no Phase9 erasure or key rotation.
Neither phase self-accepted; no push, deployment, agents or remote CI claim.
