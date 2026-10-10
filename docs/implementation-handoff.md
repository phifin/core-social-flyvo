# HANDOFF TO IMPLEMENTATION MODEL

This blueprint is the architectural source of truth. Phases 0–2 are implemented locally; Phase 2 awaits main review. See [foundation evidence](phases/phase-0.md), [identity evidence](phases/phase-1.md), [community evidence](phases/phase-2.md) and README for the actual surface. Phase 3 onward remain planned. Implement the **requested phase only**, preserving the decisions below.

## Read first

Read the current [thread handoff](agent-handoff.md) and [thread workflow](thread-workflow.md) before this reading sequence. The mailbox sets current scope and review state; accepted architecture/security documents remain authoritative.

1. Root [AGENTS.md](../AGENTS.md), then [README](../README.md), inspect current code, git status and any deeper AGENTS files. Preserve unrelated/staged user changes. Check completed phase records; the blueprint may describe work not yet implemented.
2. [Architecture](architecture.md) including dependency allowlist and SQL-view exception, then [ADR index](adr/README.md) and records relevant to the phase.
3. [Domain model](domain-model.md), [security](security.md), [database design](database-design.md), [API conventions](api-conventions.md), and [testing strategy](testing-strategy.md).
4. The exact phase in [roadmap](roadmap.md), its dependencies, subsystem docs (media/feed/events/search as applicable) and prior `docs/phases/` completion evidence.

Within documentation, subsystem contracts are authoritative for detailed behavior; roadmap is authoritative for sequence/scope; accepted ADRs for decisions; AGENTS for workflow. If documents conflict, identify the exact conflict and propose a narrow resolution before coding that part. Do not silently choose whichever design is easier.

## Frozen decisions

- Java 21, Spring Boot, Maven, Spring MVC REST, PostgreSQL/Flyway/JPA; one modular monolith/artifact initially. Use Boot-managed Jupiter (currently 6) as the explicit test-stack exception; do not downgrade to satisfy a guessed JUnit version.
- Neutral social/community domain, with the module ownership/dependency DAG in architecture.md. No family-specific entity/relationship, generic relationship table or premature SaaS tenant hierarchy.
- API/application/domain direction; no cross-module repositories/entities/JPA associations. Only documented published SQL views are the sanctioned cross-module SQL read seam.
- Account identity is UUID; credential/external identity/profile have separate ownership; no automatic email-based linking; safe registration completion, strict refresh rotation and current DB session/account checks.
- Resource policies in application modules, current source authorization, blocks override normal reads, mute affects home/notifications, COMMUNITY audience inherits community scope, custom audience max 100 initially.
- Distinct graph relations, one effective community owner, two-level comments, typed single-current reactions with stable lifecycle version; comments/reactions owned by content.
- Private versioned upload quarantine, validation before READY/binding, immutable processed variants, scoped short-lived media capabilities. No raw-object URL escape.
- Durable outbox/jobs from first required external effect; no guarantee of exactly-once external delivery; source versions/receipts/reconciliation are mandatory.
- Read-time chronological feed first, signed keyset cursors, visibility after candidate retrieval; PostgreSQL search initially. Redis/broker/engine introduction follows the gates in roadmap.
- Every schema change is a migration; real PostgreSQL tests; no prod auto-DDL, H2, universal response wrapper, generic base services/controllers, or field injection.

These are not opportunities to substitute personal preferred architecture. A requested change to them requires an explicit proposed ADR and updated affected documents/tests before implementation. Raise material unresolved policy conflicts; make routine local implementation decisions yourself and record them.

## Flexible implementation choices

Class/method names within the stated responsibilities, splitting a large use-case class for clarity, query SQL shape within approved contracts, DTO helper placement, parameterized limit tuning, compatible security/dependency patches, formatter/plugin patch versions and index refinements supported by EXPLAIN are flexible. Concrete interface extraction requires a real boundary or second implementation, not speculation. Defaults such as image dimensions/quality can be tuned with evidence; visibility semantics cannot.

Do not create every proposed class/table now. The schema is a credible target: translate the current phase into exact DDL, including all relevant constraints/FKs/indexes, record justified refinements and keep semantic invariants. Add a new module/dependency/infrastructure component only when the current phase needs it and the architecture allows it.

## Execute one phase

1. Inspect what actually exists; compare dependencies/acceptance criteria with completed phases. Fix a small missing prerequisite explicitly or report a material blocker. Never mark later-phase stubs complete.
2. Write a brief work plan: vertical capabilities, owned tables/migrations, published API additions, privacy checks, concurrency guards and test matrix. For a large phase, break it into coherent internal slices; keep integration green after each slice.
3. Agree with the documented contracts in code: define request/response and module API records; add migrations; implement aggregates/use cases/policies, infrastructure adapters, then thin controllers. Do not bypass policies to get an endpoint working temporarily.
4. Implement success and denial paths together. Add the exact constraints and transactional boundaries before claiming concurrency correctness. Required external effects get durable jobs/outbox in the originating transaction.
5. Run targeted tests while working, then formatter check and complete `./mvnw verify`. Run appropriate phase-specific media/provider/migration/concurrency checks. A blocked test is reported as blocked, not skipped-to-green. Do not repeatedly broaden testing after all appropriate checks pass without a reason.
6. Review diff for cross-module imports/SQL, privilege fields, secrets/logging, accidental dependency changes, unsafe retry behavior and unrelated edits. Validate docs/API links and update implemented contract examples.
7. Write `docs/phases/phase-N.md`: scope delivered, migrations, public APIs/events, exact check results, measured assumptions, deviations with ADR references, external readiness gates and a concrete next phase. Report completion honestly. Stop at the requested phase; do not silently proceed to all later phases.

8. Follow [Git workflow](git-workflow.md): commit completed, verified task-owned changes locally without asking again; report commit hashes and any remaining working-tree changes. Do not push or merge without separate authorization.

Suggested future invocation:

> Implement Phase N from docs/roadmap.md. Read AGENTS.md and docs/implementation-handoff.md first. Preserve accepted architecture. Complete the phase's features, migrations, security/concurrency tests, documentation and acceptance evidence. Do not start Phase N+1. Report any material blocker precisely.

## Definition of done

- Phase behavior is accessible through documented interfaces, with useful error semantics and required lifecycle/security checks. No “TODO authorization” or production mock provider/storage implementation.
- Migration works on empty database and upgrade path, constraints enforce documented invariants, Hibernate validates, no N+1/OSIV dependency, critical query plans checked.
- Unit/application/repository/API tests appropriate to the behavior pass, including denied actor, missing/inaccessible object, duplicate request and named race/failure tests. Architecture tests pass without unjustified exceptions. Bug fixes have regressions.
- Required effects survive rollback/crash/retry without forbidden duplication or information leaks. Privacy is tested through list/search/feed/media/notification paths applicable to the phase.
- CI uses real infrastructure, logs/metrics/audit exist for new failure modes, configuration is documented with secret-free examples, cleanup/recovery behavior exists for new durable state.
- README status, OpenAPI implemented surface, schema/domain/subsystem docs and phase record reflect reality. Any architectural change has an accepted/superseding ADR, not an undocumented code divergence.
- Final report distinguishes implemented/tested behavior from provider/production checks still requiring environment setup. No production launch is implied by merging code.

Documentation changes are required whenever a published API/event/view changes, migration refines this schema, permission/lifecycle behavior changes, a dependency/service is introduced, operational limits/defaults change, or a run command/check becomes inaccurate. Routine private refactoring needs no ADR if contracts remain unchanged.
