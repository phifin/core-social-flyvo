# ADR-012 — Testable architecture and release discipline

Status: Accepted, 2026-10-08.

Context: coding agents and student developers need automated, comprehensible constraints and evidence that correctness survives real infrastructure failures.

Decision: behavior/state tests plus PostgreSQL Testcontainers, API/security matrices, concurrency/fault tests and ArchUnit/SQL contract checks. Every schema change uses Flyway; every behavior change has tests; bug fixes have reproductions. CI formatting/build/test/security checks, structured redacted logs, request IDs, mandatory security/moderation audit and Actuator/Micrometer begin early. Public launch requires backup restore, key/provider/storage readiness, erasure and operational runbooks.

Consequences: initial effort exceeds a CRUD demo but prevents silent architectural drift. Tests must assert final persisted state, not only mocks/status codes. Documentation and phase completion evidence are part of the deliverable. Test success alone is not production approval, and documentation is not evidence that a feature exists.

Revisit tooling only when it improves signal/maintenance cost. Do not suppress security or architecture tests to fit an implementation. Accepted decision changes require an ADR and corresponding plan/test updates.
