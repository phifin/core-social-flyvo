# Phase9A-I — Erasure Foundation & Identity

Implementation delivery for main review, not acceptance/launch. Assignment `edb3aad`, accepted
executable baseline `d2a8e3c`; accepted local design `eab4da6`, ADR-017 C01–C05.
Only I.1→I.2→I.3; no II/III, real erasure/restore, agents, push or deployment.

## Main review round 1 — 2026-10-18

**CHANGES_REQUESTED**, executable `ed125b719ed47d5cb97a33157ad3e49977587376`,
handoff `a9f4d78`. Main's code inspection found two open P2 issues:
P9AI-R1 (onboarding expiry checked with pre-lock time, requiring proof-chain bound
review) and P9AI-R2 (old intent export/recovery can affect a later request for the
same user). These are not newly reproduced runtime failures. Full scenarios,
regression gates and the combined fix assignment are in the
[fixed mailbox](../agent-handoff.md). Developer evidence below remains attributed
to development and does not constitute acceptance. No II/III activation or push.

Main independently ran Java21 full verify on the delivered executable:418 tests
(62 fast+356 IT), zero failures/errors/skips, BUILD SUCCESS in5m45s, finished
2026-10-18 12:52:57+07 (`/tmp/social-p9ai-main-verify.log`). Final345-file manifest,
Compose app/media config, migration diff, whitespace, pinned69-commit history
secret scan and synthetic positive control also pass. Main did not rerun the
benchmark/native deployment. Passing existing tests does not resolve the two
uncovered interleavings; the same development chat owns the combined fix package.

## Delivered scope and internal gates

I.1: account-owned request/intents/fixed12-step roster, scalar bounded checkpoints,
resource receipts, historical pending/deleted scans and explicit integrity blockers;
audited current ADMIN progress/resume, mandatory rollback and negative completion
guard. Later eight owner steps remain WAITING_DEPENDENCY, without jobs.

I.2: actual fsynced isolated synthetic append/verify archive, PREPARED before close,
COMMITTED/resource journal and serialized manifest digest; current authority/version/
ownership recheck after network, nonrecursive eleven-prerequisite certificate model,
fenced external ack and ten-attempt/DEAD/audited resume; typed scope/proof cancellation,
durable SMTP invocation drain and explicit cancellation outcome preserving errors.

I.3: ≤100 token/registration/OAuth/refresh/session stages, last-enumeration receipt
barriers, transient identity-owned mailbox correlation,50m anonymous proof bound
with original provider-expiry recheck, technical drain before unique claim release,
fresh-proof/new-UUID reuse. Profile/avatar/preference removal and versioned blank
absence export, canonical DB handle LIVE/RESERVED protocol and30d expiry cleanup.

Schema V0033 account, V0034 platform, V0035 identity, V0036 profile, V0037 profile
creation-lock correction. All V0001–V0032
checksums remain unchanged. Existing module DAG/view allowlist, pools/timeouts,
provider/CSRF/refresh semantics and exact secret ignores are preserved.

## Evidence in this development run

Java21.0.12.1 (`/usr/lib/jvm/java-21-openjdk-amd64`), Docker29.9.0, pinned PostgreSQL18.6/
Silo from the repository. All new destructive work uses isolated `p9a_*` Testcontainers
or a separately named synthetic Compose project, never existing developer data.
Read [protocol](../account-erasure.md), [operator guide](../../ops/erasure.md), and
the final mailbox for code hash/routing. Evidence is local, not production
authority. No application test is disabled to pass a gate. On the2026-10-18
continuation, prior `/tmp/social-p9a-*` logs and sandbox directories were absent.
Their failure results are preserved below from the development record; raw logs
cannot be recovered or claimed available. The final354-IT/62-fast XML reports
survived in `target` and were copied before rerunning checks. New evidence remains
outside `/tmp` at `/home/vonhuphi/social-p9a-evidence.5qQBe3/`; synthetic keys/data
are separate and are never committed.

Initial compile attempts retained: formatter failure (`social-p9a-i-first-compile.log`),
then checked SQLException in a worker lambda (`...compile-3.log`); fixes passed.
The first foundation verify was interrupted during compilation by user continuation,
not a completed/failed suite (`...foundation-1.log`). Foundation retry5 PASS/31.621s;
fresh/legacy upgrade gate27 IT PASS/65s. Expanded attempts retain one Boolean generic
compile failure and a2-failure/1-error fixture/owner-receipt run. The receipt check
was narrowed to the completing owner (global completion still checks all chunks).
Another expanded attempt retained an artificial equal-due-time starvation fixture
and a spy stubbing call outside MANDATORY transaction; corrected fixtures preserve
actual due ordering and use the underlying spy. No timeout/pool/denial was relaxed.

Subsequent integration125 IT PASS/67s (`social-p9a-i-integration-1.log`); controlled
PG handle races/new migration prefix/rollback19 IT PASS/38.606s
(`...races-1.log`); provider/reset/fresh-mailbox19 IT PASS/35.109s (`...provider-1.log`).
Full verify pass1 completed successfully in6m04s before final profile-source/
operator metadata refinements; it is not substituted for the final executable gate.
Final targeted attempt1 retains a raw native-query generic compile failure; the
owner adapter now uses typed bounded JDBC for its own tombstone scan.

Additional failed attempts, not erased by successful retries:

| Attempt | Actual failure and disposition |
|---|---|
| `expanded-2` |11 IT:2 failures/1 error; owner-specific receipt barrier fixed, invalid historical-timestamp fixture replaced with constraint-valid invalid-generation case, unrelated pending fixture mail postponed |
| `expanded-4` |15 IT:1 failure/1 error; artificial equal-due-time starvation and MANDATORY-proxy spy setup corrected without changing production timeouts/leases |
| `final-targeted-2` |37 IT:11 failures/1 error; sole JDBC UUID-array callback argument needed explicit `(Object)` to avoid varargs expansion; avatar fixture needed valid32-byte base64 checksum |
| `full-2` |60 fast+353 IT:0 failures/1 error,7m48s,2026-10-17 23:46:30+07. Unchanged `Phase4IT.deleteAndEarlyRejectionDeferQuotaReleaseWhileRealPutCapabilitiesRemainReusable` line603: fixture S3 `deleteObject`, `ApiCallTimeoutException`15000ms. Concurrent image build/idle sandbox existed; post-fault host memory available1136MiB/swap3784MiB. Correlation is not proven cause; distinct from carried JDBC timeout R01 |
|2026-10-18 `benchmark.log` |Failed14.893s during20,050-profile seed, before measured requests: PG `out of shared memory`, at V0036's per-profile advisory key. Full failed log/XML retained. New V0037 removes advisory-key growth for creation using the same unique canonical claim, with bulk/no-advisory and concurrent create/reservation/expiry regressions. No PG lock limit, benchmark, pool or timeout changed |
|Prior stopped-storage probe |curl000/exit7 after the sandbox was stopped; sequencing error, not storage-policy evidence. Raw `/tmp` log absent on continuation; replaced only as evidence by genuine running-bucket403 probes, not relabeled PASS |
|2026-10-18 native fresh probe attempts1–2 |First expected stale412 but got403; second fetched CSRF once and reached412, then got403 on close. The probe reused cached tokens after authenticated responses rotated/cleared cookies. Both logs remain (`probe-fresh.log`, `probe-fresh-retry.log`). Client now obtains the current CSRF cookie/header before each unsafe request; real missing-CSRF403 remains tested, no server policy/code/config relaxed |

The subsequent unchanged full run passed62 fast+353 IT in5m55s; the final
pre-continuation XML reports show62 fast+354 IT, zero failures/errors/skips.
These do not conceal the SDK timeout or certify its root cause. Timeout/pool/
assertions and all V0001–V0032/benchmark/secret-ignore files remain unchanged.

Final gate outcomes follow; successful retries do not erase the failures above.

### Final executable identity and matrix coverage

Final executable/config/API manifest:345 files, SHA-256
`ca69385e8d076d4884f4c66d27b0a2d218df7cd63ef1573ad65e98ce4f0ee488`;
runtime-only288-file manifest SHA-256
`c0f31ccd1bb53787b5cc4b5888527ff7f88ac7c6d9c95a7725615b693dd781d6`.
Rows are in `executable-manifest-final.sha256` under the evidence root above.
The earlier344-file digest `575ca6e12b4d04212c2927e1e73ade216d0026084d5499710b5aaeabc959da0a`
belongs to the pre-V0037 executable, not the delivered candidate.

| Contract matrix | Package-I evidence and boundary |
|---|---|
|T01–T04 |Fixed roster, current admin/CSRF/If-Match, genuine archive faults and current-authority rerecheck, worker lease/version/round/audit/checkpoint rollback, DEAD/resume; no successful completion claim |
|T05–T06 |205-row credential/proof chains, stale provider/reset/mailbox denials, fresh-proof new UUID, avatar receipt/release and versioned absence, reserved/expired/concurrent handle claims and20,050-profile bulk lock regression |
|T15 |Real historical pending reconciliation without event dependency; corrupt generation visible, no fabricated t0; historical DELETED residual implementation waits II |
|T18–T20 |Archive/cancellation/SMTP drain and SDK-failure records, Java21/real PG/architecture/OpenAPI/migration prefixes, final Compose/security/benchmark gates below |
|T07–T14,T16–T17 |Full social/content/media/projection/moderation/large-account/restore matrix remains II/III; only existing regressions and the explicitly scoped identity/profile subset run in I |

Commands use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and its `bin` first
in PATH. Relevant targeted command:
`./mvnw -B -ntp spotless:apply verify -Dtest=ErasureActivationTest,ErasureCertificateTest,ArchitectureTest,OpenApiContractTest -Dit.test=ErasureFoundationIT,ErasureMigrationIT`:
13 fast+25 IT PASS,48.242s before the extra SQLSTATE assertion; final full verify
also exercises that assertion. Prior final-source full gate before V0037:
`./mvnw -B -ntp verify`,62 fast+354 IT PASS,5m58s,
2026-10-18 12:03:48+07 (`verify.log`), not substituted for the post-fix gate.

Final post-V0037 `./mvnw -B -ntp verify`: **62 fast+356 IT=418 PASS**, zero
failures/errors/skips,6m04s,2026-10-18 12:13:48+07 (`verify-final.log`). Includes
all22 erasure foundation,3 migration (each prefix32→37/checksums/failure rollback/
bulk claims), existing Phase4 storage/media, SQL-view policy parity, lifecycle,
refresh/CSRF/provider, architecture and OpenAPI checks. `spotless:apply` then
the verify-bound `spotless:check` passed; no code changes follow this candidate.
Current full-gate reports are copied to `full-final-failsafe/` and
`full-final-surefire/`, excluding stale benchmark XML. Unfiltered directory copies
(`delivered-*-reports/`) and failed benchmark reports are also retained; stale XML
from earlier invocations must not be mistaken for that invocation's aggregate.

### Unchanged shared-resource benchmark

`./mvnw -B -ntp -Dfeed.benchmark=true -Dtest=FeedBenchmarkTool test`:
**3200/3200 successful requests**,16 pool/scenario outcomes, zero failures/error
rate0,5m14s,2026-10-18 12:19:07+07 (`benchmark-final.log` and copied benchmark XML).
The earlier failed14.893s seed remains in `benchmark.log` and
`benchmark-failed-surefire/`; V0037 fixes its proven advisory-lock cause.
No pool/statement/socket timeout/PG lock limit/benchmark assertion changed.

Fixture:1M posts,10k follows/reader,50 concurrent readers, production-default pool10
and separately labeled diagnostic50. Java21,8 visible processors, JVM max
2,011,168,768 bytes, host RAM7,848,012kB/available1,774,048kB at observation,
PG18.6/database980,506,303 bytes; seed231.668s. Raw log preserves SQL/EXPLAIN,
physical-row plans, connection-wait/occupancy/JVM diagnostics and every outcome.
Dense SQL15/attempt; sparse5–12/attempt except overlap15. Percentiles are
success-only; here all attempted requests succeeded.

|Pool|Scenario|p50 ms|p95 ms|p99 ms|
|---|---|---:|---:|---:|
|10|first_burst|1233.537|3081.382|3874.103|
|10|post_burst_steady|1035.150|2184.790|2877.368|
|10|no_sources|57.134|131.763|146.006|
|10|rare_old|171.494|431.893|479.572|
|10|last_page|313.414|761.758|842.198|
|10|mostly_rejected|149.328|321.809|376.891|
|10|overlap|941.501|1656.708|1940.951|
|10|exhausted|116.263|268.257|285.283|
|50|first_burst|1167.930|1912.042|2289.285|
|50|post_burst_steady|840.515|1418.507|1526.526|
|50|no_sources|24.485|50.048|61.086|
|50|rare_old|125.941|208.706|242.834|
|50|last_page|206.690|373.563|444.401|
|50|mostly_rejected|105.200|231.880|263.155|
|50|overlap|803.061|1128.326|1161.663|
|50|exhausted|100.501|216.378|238.861|

Dense p95 remains1.419–3.081s, above500ms; pool10 last_page/overlap also exceed
the goal. R02 stays open for target-environment capacity/performance review;
all-success correctness is not a latency SLA or launch approval.

### Real isolated runtime, upgrade and security

Stock `docker compose --env-file /dev/null config --quiet` and resolved isolated
app/media config PASS. Project `social-p9a-delivery` created its own network/PG
volume. All keys, Silo bytes and archive binds come from the newly allocated
`/tmp/social-p9a-delivery.ugUiTz/`, not developer `.local` or old Compose data.
`docker compose ... build app` PASS; runtime image
`sha256:fe5a3f2f889e3320d612b7ff8c5d16be462393fef075bad0f2d38c69dca5aa90`.
Actual runtime inspect:UID/GID1000,read-only root,1GiB/2CPU limits. Private,
encrypted/versioned Silo bootstrap PASS; running anonymous bucket listings both403.

Fresh `p9a_runtime` startup/native probe PASS (`probe-fresh-final.log`): readiness/
liveness200, metrics401, real Mailpit registration202/completion200, ordinary
admin403, missing CSRF403, stale If-Match412, receipt-backed close204, revoked
session401, current ADMIN progress200/no-store/ETag. Each closed account has
12 steps with8 unimplemented owners waiting; SQL result `37|0|12` means37 applied
migrations,0 DELETED,12 persisted steps. Earlier probe failures remain recorded.

Separate fresh `p9a_upgrade` bootstrapped with the same candidate at Flyway target
0032, workers/media/erasure off; actual web startup PASS,32 applied checksums saved.
After stopping that bootstrap, seed one synthetic ACTIVE account/profile; start
the candidate with default latest target, explicit synthetic activation and a
different stable lineage/archive directory. Actual startup applied exactly five
migrations0033–0037;32 prior checksums compare byte-for-byte equal, seeded LIVE
handle backfill retained. Same native probe PASS (`probe-upgrade.log`, `37|0|12`).
This is fresh-prefix upgrade, **not a backup restore** or old-binary rollback test.

Logs: `compose-build.log`, `compose-*-runtime.log`, dependency/bootstrap/start/stop
logs, `upgrade-*-checksums.txt`, `upgrade-backfill-result.txt`, runtime identity and
running-bucket response codes under the evidence root. App was stopped first,
then only this project's PG/Mailpit/Silo; no sandbox container is left running.
Synthetic volumes/data/keys are retained, not deleted; no developer volume changed.

Pinned Gitleaks8.28.0 image digest remains exactly
`cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854`.
Redacted baseline full-history scan67 commits PASS; synthetic external positive
control PASS (expected scanner exit1/one generic-api-key, wrapper exit0).
Exact ignores/rules remain unchanged; final staged/history outcomes and real code
commit are recorded in the mailbox. Markdown local links and whitespace pass;
executable manifest is rechecked unchanged before commit. No remote CI/push claim.

No target-latency, production archive/backup/key/provider/IAM/legal/admin authority,
remote CI, exact previous-binary compatibility or public-launch waiver follows
from these local checks. Full owner proofs/finalization and recovery attestations
remain unimplemented until main separately activates II/III.

## Binding review-condition responses

- C01: no moderation policy/trigger/redaction alteration. Its owner row waits;
  pending-case review/resolution remains existing ADR-016 behavior, not fake DONE.
  Guarded pending-case inventory/proof invalidation and its tests remain II.
- C02: certificate input contains request/protocol/policy/roster/round/manifest and
  eleven current prerequisite proofs only, never its own receipt/version/event.
  Negative app/DB completion guards are exercised. No successful finalization in I.
- C03: eight unimplemented roster steps stay waiting. Real worker tests show no
  unknown owner dispatch or empty-account DELETED shortcut; DEAD is not auto-resumed.
- C04: named local policy, default-off maintenance, explicit isolated URL/archive,
  real durable file assertions and no fallback. Missing archive fails close503;
  local checks do not prove production latest-cut/failure-domain authority.
- C05: immutable≤100 resource-ID chunks/receipts precede proof/avatar mapping loss;
  failed exports keep retryable mapping and become visible/resumable. Actual media
  purge/restore/no-resurrection proof remains II/III, not claimed by source release.

## Limitations and next owner

Main reviews the complete package before any II activation. No account reaches
DELETED, no production erasure policy/latest-cut/restore barrier is certified and
U01–U06 remain open. SMTP invocations whose completion cannot be proven remain
waiting; expired leases are not drain. Historical late baseline cannot certify old
backups. An exact previous compatible Phase9 artifact does not yet exist: after
activation, keep service closed and forward-fix rather than roll back to V0032.
Carried initial Phase4 fixture timeout remains unproven-cause; dense feed500ms
target remains a measured release gate, not satisfied by unchanged configuration.

## Repair response to main review9170d53 — not reviewer acceptance

Scope: P9AI-R2 then P9AI-R1 only, against implementation `ed125b7` and main's
CHANGES_REQUESTED review. Original findings remain OPEN in the mailbox until main
dispositions them. No II/III, DELETED, real data, restore, agents or push. No schema,
dependency, operational config or public HTTP shape change; all V0001–V0037 stay
unchanged. Runtime/benchmark checks below are new repair-round evidence, not an
attribution of old checks to this candidate.

### P9AI-R2 implementation and regressions

An intent matches only its own request ID/user/lifecycle generation. Non-PREPARED
journals require that match and capture its round/t0, never another request's
timestamp. Recovered PREPARED creates COMMITTED only for the same pending request
and round. A's unmatched prepared record remains conservative uncommitted history.

External leases now carry the job's immutable producer execution key, including
expired-tenth failure observation. Export preparation/ack/failure compares the
current export execution, exact journal identity/request/generation/round and,
for recovery success, the captured step context/version after its guard. Stale
execution effects cannot acknowledge or charge the successor. Immutable invalid
old artifacts keep their own failure history without mutating another request.
Payload shape,60s leases/ten-attempt budgets and platform module boundaries stay
unchanged; no account policy or foreign SQL enters platform.

Three added real-PG/real-worker/archive tests exercise rejected A after append,
valid B at a new version with held COMMITTED, delayed A/no fabricated COMMITTED,
legacy invalid COMMITTED(A) failure/receipt isolation, and superseded success/
failure after real append. B advances only from its own receipt; existing same-
intent historical backfill, duplicate, DEAD/resume and fenced rollback tests pass.

### P9AI-R1 clock/expiry contract and regressions

All anonymous LOGIN/onboarding/mailbox issuance and expiry samples now use owned
identity SQL `clock_timestamp()`, the same DB authority as account retention.
Unlike `now()`, it is not frozen at transaction start. Onboarding is locked before
sampling time; checks repeat after dispatch/account waits and final registration
effects. A denied command rolls back consumption, new intent/mail and account/
profile/session/audit effects; callback state already consumed before external
exchange remains consumed on later expiry, without successor capability/session.

Let L be LOGIN issuance, E=L+600s, O≤E+600s and M≤O+1800s. Callback must finish
before E, registration before O; both successors preserve inherited absolute
limits. Then M≤L+3000s≤t0+3000s for pre-t0 chains. Equal expiry is invalid. No wait
constant, provider proof, CSRF or refresh denial is relaxed. Trusted DB time and
source-issued TTLs are explicit assumptions, not proof of arbitrary imported/
old-binary capabilities, restored lineage or clock rollback. Public/activation
U04/U06 gates remain; existing developer data is never enabled here.

New `ErasureProofChainIT` has21 cases: five real row/guard/window boundary groups
and provider-network latches at−1µs/exact/+1µs, inherited full anonymous-chain
limits, post-provision whole-command rollback and a real erasure/claim-release/
fresh-proof/new-UUID flow. The owned email/provider claims stay until50m−1µs;
at50m only genuine technical/identity drain releases them, stale LOGIN/onboarding/
mailbox/reset proofs fail, fresh mailbox/provider proof creates another UUID.
The original subject remains PENDING with8 unimplemented owner steps waiting.

Time control: test-only spies freeze both DB-time ports and the fixture's JVM
clock to one logical Instant, while PG locks, transactions, worker effects and
archive files remain real. `pg_blocking_pids`/latches prove the wait before moving
time; no arbitrary sleep simulates expiry and no production clock override is
added. Frozen-time test driving invokes each real handler fairly, avoiding an
artificial equal-due/random-UUID starvation loop. This tests the fixed chain;
the RED stale-expiry reproduction is not claimed as a reproduced end-to-end takeover.

### Repair-round evidence (2026-10-18)

Evidence root `/home/vonhuphi/social-p9ai-fixes.pwd74E/`, outside ephemeral `/tmp`.
Java21.0.12.1, real pinned PG18.6/Testcontainers, explicitly isolated synthetic
`p9a_foundation`/`p9a_proof_chain` fixtures only; workers are driven explicitly.
Source/config/API346-file SHA-256:
`4518697616d7c069f55f083c75ac6a4422455c10a51e155804763e3a1f9a3ae0`;
runtime-only288-file digest:
`ed7b71f954e3f955ff8ac52befb26acec35f9a3d495043324785a4b7f634dca2`.
Full manifest rows are retained in `executable-manifest.sha256` and checked again
before code commit. Current source froze after formatter/combined targeted gate.

| Command / log | Actual result |
|---|---|
|`r2-red.log` / reviewed code plus new regressions|2 IT,2 FAIL/21.925s: fabricated COMMITTED(A) and B recovery poisoned by A failure. Raw reports retained |
|`r1-red.log` / pre-R1 fix|3 boundary IT,2 FAIL/22.323s: at/after expiry still mint intent/mail; before case PASS. Raw reports retained |
|`r2-targeted-final.log`|10 fast+25 IT PASS/32.351s, including certificate/architecture and new execution cases |
|`r1-targeted-1.log`|66 IT,1 FAIL/52.453s: prior old-provider fixture shifted only app clock, no longer the new DB clock source; corrected to share the controlled DB port, no denial relaxed |
|`r1-chain-1.log`|21 IT,1 ERROR/29.557s: spy setup invoked MANDATORY profile proxy outside TX; stub underlying spy instead. Real command still runs through its guarded transaction |
|`combined-targeted.log`|87 IT,1 FAIL: frozen-clock/forced-due fixture tied job times and starved resource export. Fair per-kind real-handler driving fixes the fixture, not scheduler/timeouts |
|`combined-targeted-final.log`|13 fast+87 IT=100 PASS/1m30s,2026-10-18 14:10:38+07. Formatter, architecture/OpenAPI, erasure/certificate/default-off,21 expiry/chain cases, all migration prefixes, auth/provider/CSRF/refresh regressions |
|`verify.log` / `./mvnw -B -ntp verify`|62 fast+380 IT=442 PASS, zero failures/errors/skips,7m48s,2026-10-18 14:19:10+07. Includes formatter, architecture/OpenAPI, all37 migrations/prefix upgrades/checksums and actual PG/storage regressions. Curated XML/text copied into `full-surefire`/`full-failsafe` before benchmark |
|October19 `benchmark-interrupted.log`|User/turn interruption terminated the process after pool10's8 scenarios/1600 successes, before pool50 outcomes. No completed benchmark/build verdict; not PASS and not a request failure. Partial raw outcomes/plans/diagnostics retained; full unchanged benchmark rerun separately |

The2026-10-19 continuation rechecked all346 executable manifest rows against this
same source before starting operational gates. The earlier unstarted runtime
`/tmp` sandbox had disappeared; a new unique project/volume/key/archive directory
was prepared, never reusing developer data. No full-suite rerun is attributed to
October19. Benchmark/runtime/security results follow only after completion; a
pending command is not PASS. Earlier phase/main evidence and failures above remain
attributed to their original executable, not silently replaced.

### Repair operational gates — 2026-10-19

Unchanged heavy command:
`./mvnw -B -ntp -Dfeed.benchmark=true -Dtest=FeedBenchmarkTool test` with Java21.
`benchmark.log`: BUILD SUCCESS,6m18s, finished21:49:38+07. All16 outcomes,
3200 attempts/3200 successes/0 failures; no acquisition failures. Percentiles are
success-only, with no failed requests omitted here. No benchmark, pool, timeout,
PG lock limit, query or assertion changes. The interrupted attempt remains separate.

Real PG18.6,1M posts,10k follows/reader,50 readers, default pool10 versus diagnostic50.
8 visible CPUs, JVM max2,011,168,768 bytes, host RAM7,848,000kB/available978,240kB
at the recorded observation; cgroup CPU/memory max files unavailable on this host.
Database980,514,495 bytes, seed274.322s. Raw log retains physical SQL/EXPLAIN,
connection waits/occupancy and JVM diagnostics. Dense SQL15/attempt, sparse5–12
except overlap15; no runtime/build workload ran during measurement.

|Pool|Scenario|p50 ms|p95 ms|p99 ms|
|---|---|---:|---:|---:|
|10|first_burst|1625.306|3454.683|4369.609|
|10|post_burst_steady|1308.630|3044.185|3868.077|
|10|no_sources|92.256|228.612|236.553|
|10|rare_old|270.578|482.759|581.980|
|10|last_page|361.156|772.005|909.378|
|10|mostly_rejected|154.617|374.736|429.202|
|10|overlap|896.670|1827.545|2028.929|
|10|exhausted|122.691|279.404|295.744|
|50|first_burst|1323.847|2731.070|3568.447|
|50|post_burst_steady|934.450|1497.447|1755.569|
|50|no_sources|37.908|70.973|77.907|
|50|rare_old|144.221|221.072|267.837|
|50|last_page|194.553|374.920|432.029|
|50|mostly_rejected|87.906|226.084|244.128|
|50|overlap|726.687|1316.969|1544.267|
|50|exhausted|125.275|216.864|237.671|

Dense p95=1.497–3.455s still exceeds500ms; pool10 last_page and both overlap
scenarios also exceed it. Correctness PASS does not close R02, certify capacity,
or approve public launch. Host variability is not a proven regression/root cause.

Fresh native project `social-p9ai-review-fixes`, keys/Silo/archive binds under
`/tmp/social-p9a-review-fixes.E181zn/`, unique PG volume and `p9a_runtime` DB only.
Explicit `--env-file /dev/null`, random loopback ports, named isolated policy and
new lineage; no existing developer `.local`, database, bucket or backup used.
Stock/resolved Compose config, build, private encrypted/versioned Silo bootstrap
and running anonymous bucket403 probes PASS. Image:
`sha256:d3ebd7fc3463171e5869b9bbf18952148cd55d324fb45d9403375315a4662f8d`.
Actual inspect:UID/GID1000, read-only root,1GiB memory/2CPU limits unchanged.

`probe-runtime.log` PASS against actual app/media runtime: readiness/liveness200,
anonymous metrics401, real Mailpit registration202/mailbox completion200,
ordinary admin403, missing CSRF403, stale If-Match412, receipt-backed close204,
revoked session401, current admin progress200/no-store/ETag. Twelve persisted
steps/eight unimplemented owners waiting; SQL `37|0|12` confirms0 DELETED.
Real worker recovery proof equals its own request's COMMITTED receipt/digest,
generation and round (`own_request_committed_receipt_and_recovery_proof=1`).
No runtime attempt failed. Logs/config/inspect and probe script are retained in
this repair evidence root; app stopped first, then only this project's PG/Mailpit/
Silo. Volumes/keys/data retained, not removed; no sandbox container left running.
No native upgrade/restore is newly claimed: unchanged V0001–V0037 prefix/checksum
tests passed in442-test full verify; native V0032→V0037 evidence above belongs
to the original implementation round. No backup restore was performed.

Pinned Gitleaks8.28.0 positive-control wrapper PASS (expected scanner exit1/one
synthetic generic-api-key); baseline full history through9170d53:70 commits,
no leaks. Exact ignores/rules unchanged. Final staged/history scan results and
actual code hash belong in the separately committed mailbox. Markdown local
links/whitespace and all346 manifest rows are checked before code commit; no
remote CI, push or deployment claim. P9AI-R1/R2 remain OPEN for main disposition;
C01–C05 and U01–U06 boundaries are unchanged.
