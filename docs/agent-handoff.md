# Agent handoff — Phases 7–8

## Routing

- Task: phase-7-8-notifications-search-moderation-ops
- Round: 1
- State: BLOCKED
- Last writer: development, 2026-10-16 — verified prerequisite plus P78-D1 decision request
- Next owner: main — decide proposed ADR-016, then return the remaining D/package gate to development
- New chat: `Develop Phases 7–8 — Notifications, Search & Moderation Ops`
- Accepted baseline code: `32c1b1eb8609eeb8c61278e29efbedba14008db9`.
- Main acceptance commit: `6dc06d6`.
- Prior findings P56-B1/R1/R2: resolved by main. Full history/evidence is preserved
  in [accepted Phases 5–6](handoffs/phase-5-6-interactions-reports-feeds.md).
- Assigned scope: [full Phases 7–8 package](work-packages/phase-7-8.md), A → B → C → D.
- Main accepted [ADR-015](adr/015-notification-stream-authentication.md): narrow
  notification-to-identity API proof for SSE lifecycle; implemented in B, not phase acceptance.
- Main is orchestrator/reviewer only and exclusively performs origin pushes.
  Development must not push, spawn agents, merge, release or deploy.

## Assignment

Read the complete linked package and required contracts before implementation.
Inspect status, actual baseline and existing APIs. The initial activation allocated
from V0027 after V0026; actual code now has V0027–V0029. Preserve all applied
V0001–V0029; next migration is V0030, verify actual HEAD again before resuming.

Implement sequentially in this single new chat: durable envelope + semantic inbox,
authenticated SSE + full Phase 7 internal gate, then PostgreSQL search and scoped
moderation operations + final package verification. Do not hand off per small slice.
Commit verified slices; passing an internal gate allows continuing only the assigned
package, not self-acceptance. No Phase 9 implementation. A concrete unresolved
contract/resource blocker must include evidence and a decision proposal.

Deliver separate Phase 7/8 evidence, exact tests and lag/resource measurements,
schema/API/security/operations/README updates. Commit code first; then a separate
mailbox commit with actual hashes, limitations and READY_FOR_REVIEW to main.

## Carried-forward limitations and regression gates

- Main independently verified 260 tests and 3,200/3,200 benchmark requests at the
  accepted baseline. Source/body coherence, current privacy, source-selective
  feed work, short owner read transactions and all prior tests remain mandatory.
- Dense feed p95 still misses 500ms (main 1.7–2.7s); no production capacity/SLA
  claim. Do not raise pool/timeouts, hide failures or add authorization caches.
  Rerun the heavy pool10/50 benchmark if owner query/transaction/resource changes
  affect feeds, as specified in the package.
- Storage/provider/decoder/key/retention/admin gateway and public-launch gates
  remain separate. ADR-015 does not authorize credential access or identity SQL.
- SQL view consumer allowlist remains content-only; notification/search call
  owner APIs. Existing worker receipts/fencing/atomicity remain authoritative.
- Two exact historical secret fingerprints only; no scanner weakening/history
  rewrite. Full-history scan plus synthetic positive control remains required.

## Implementation report

### Main remote checkpoint — 2026-10-14

Main performed normal fast-forward push to
`origin` = `https://github.com/phifin/core-social-flyvo.git`, branch `master`,
from `03f8a98` to `ff2e6f75c0e4ed20d4e34c494ab837aeef3be032` and verified that
exact remote hash with ls-remote. This delivers accepted Phases 5–6 plus the
documentation-only Phase 7/8 assignment/ADR, not implemented Phase 7/8.
Final pre-push full-history pinned redacted scan: 47 commits, no leaks; staged
coordination patch and changed-document links pass. Archive preservation was
checked against acceptance commit, allowing only relative-link adjustment.
Remote CI result has not been checked; local verification is recorded in the
accepted archive. No deployment/release or dev-thread push is authorized.

Development inspected clean `master` at `14c983b`, accepted executable baseline
`32c1b1e`, and migrations V0001–V0026. Docker Engine 29.8.2 is available. The
shell defaults to Java 8; verification will explicitly select the installed
`/usr/lib/jvm/java-21-openjdk-amd64`. No dependency or runtime version change.

Concrete execution plan: [development plan](work-packages/phase-7-8-plan.md).
Order is A → B → full Phase 7 gate → C → D → full package gate. Each coherent
verified slice is committed locally; no intermediate owner handoff. No findings
have been disposed of, no phase accepted and no remote operation authorized here.

Current development checkpoint (not a handoff): A commits `beebb0a`/`ecb11f6`;
B code `af0742750cd1f1b00a02a1afcd6aa3d80d13db74`. The full Phase7 internal gate
passed298 tests (54 fast +244 PG executions), zero failures/errors/skips and no
forced JVM exit. Spotless, architecture/OpenAPI, fresh/upgrade27 checksums/data
and pinned staged secret scan pass. [Phase7 evidence](phases/phase-7.md) preserves
all earlier failed native teardown/shutdown gates, actual fixes and measurements.
Single-instance SSE uses verified identity proof/current checks, aligned Tomcat
11.0.27 plus native backpressure abort, and early graceful-shutdown lifecycle.
That checkpoint allowed development C → D only; it did not accept either phase.

### C delivered and D lifecycle prerequisite — 2026-10-16

- A envelope `beebb0a`; semantic inbox `ecb11f6`.
- B `af0742750cd1f1b00a02a1afcd6aa3d80d13db74`, Phase7 internal full gate298 PASS
  before C. [Phase7 evidence](phases/phase-7.md) retains prior failed attempts/fixes.
- C `79da0ca692845a55e80cc7f67d6a4e5663845864`, source-authorized search/rebuilds,
  full317 PASS (55 fast +262 integration), zero failures/errors/skips/forced exit.
  V0028 and all earlier checksum/data/permission/feed/worker regressions pass.
- D account-lifecycle prerequisite **only**:
  `cd51ecae3d92a402fa8c70b1102501cafb857ffa`. V0029 account/session epochs;
  guarded/versioned ADMIN owner suspend/reinstate, self/peer ADMIN denial,
  current epoch checks so reinstatement cannot revive old sessions. No reverse
  identity dependency, community/content/role mutation or new HTTP endpoint.

Latest implemented-code verification:

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:apply verify`
  **323 PASS =55 fast +268 integration**, zero failures/errors/skips, no forced
  fork exit,5:02, finishing2026-10-16 18:32:55+07. Log
  `.local/evidence/p78/d-lifecycle-full.log`; targeted81 PASS precedes the extra
  legacy-suspension upgrade fixture. [Phase8 evidence](phases/phase-8.md) contains
  exact test matrix, resource measurements and earlier C failures, not just totals.
- Fresh29 and upgrade28 preserve all prior checksums/data; existing ACTIVE sessions
  keep epoch0, already SUSPENDED accounts start at1 so their old sessions stay
  denied. All22 reports,19 feeds,34 auth and prior scope/privacy/worker/media tests
  pass. Independent audit/outbox failure rolls status/version/epoch/effects back;
  verified-password login loses to concurrent suspension before the guard.
- Native SSE suspend→immediate reinstate closes old session in5098ms (<15s),
  replayed stream401, native async/servlet allocations0. Other closure/resource
  assertions unchanged; full-suite search lag max20.151612654s on10 posts/one owner,
  actual default scheduler;50-result skew fixture6 SQL queries. No SLA/capacity claim.
- Spotless/Enforcer/architecture/OpenAPI pass. Pinned v8.28.0 redacted gitleaks:
  implemented code history **53 commits, no leaks**, staged code patch no leaks;
  synthetic positive control detects1 expected generic-api-key outside git and
  passes. Logs `.local/evidence/p78/d-history-code-scan.log`,
  `d-lifecycle-staged-scan.log`, `d-secret-positive-control.log`.
  The two exact historical fingerprints remain unchanged, no history rewrite.
- Blocker-only documentation: changed local links and git diff whitespace pass;
  its separate staged patch passes the same pinned redacted scanner
  (`.local/evidence/p78/d-blocker-docs-staged-scan.log`). No executable change
  follows the successful323-test run; no redundant suite is claimed for these docs.

## Development blocker P78-D1 — retention contract needs main decision

This is not a handoff after a small task or a phase completion claim. D cannot
finish under the current immutable-private-payload contract without selecting a
new retention security boundary. Proposed [ADR-016](adr/016-moderation-private-payload-retention.md)
records evidence, concrete recommended migration/policy, alternative and tests.

Evidence: V0010 moderation_action_immutable unconditionally rejects UPDATE/DELETE,
including private reason/before_state/after_state; V0023's report identity guard
rejects evidence/key changes and requires nonempty ciphertext. Accepted raw-SQL
immutability regressions still pass in the latest full suite. D nevertheless
requires365-day private payload redaction, explicit bounded holds and retained
attributable metadata, without an application audit-trigger bypass. The existing
"controlled owner-role process" blueprint does not select an executable
moderation exception. Development must not silently redefine accepted immutability
or invent a privileged runtime role/hold policy.

Decision requested: main approve ADR-016's owned private-payload split and explicit
engineering holds (current scoped staff/global ADMIN; versioned audited controls;
explicit expiry≤365 days per decision; renewal explicit, not automatic), or specify
the separately privileged owner-role alternative and its exact worker boundary.
Recommended split keeps action metadata and platform audit triggers unconditional,
migrates legacy private data losslessly, encrypts new action details, and permits
only verified legacy encryption/eligible irreversible payload redaction.
ADR remains **Proposed, not accepted or implemented**. No legal compliance claim.

No moderation payload/trigger/hold/cleanup change has been made. Assignment,
filtering, attributable history/queue reads, holds/retention, separate account
moderation HTTP operations and dashboards remain unfinished. Full app/media
Compose startup, committed pool10/50 benchmark all3200-success and final post-D
package gates remain required, not waived or reported as run. Existing unmet
feed p95<500ms and external launch limitations above remain unchanged.

After main's decision, the user returns to this development chat; reread mailbox,
verify HEAD, set IN_PROGRESS, implement the remaining D and run all package gates.
Only then create real-hash READY_FOR_REVIEW handoff. Current state is deliberately
BLOCKED, **not READY_FOR_REVIEW**; neither phase accepted. No push, agents or Phase9.

## Review findings

No findings yet for this package. Only main may resolve findings or accept phases.
