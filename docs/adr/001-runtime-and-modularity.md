# ADR-001 — Runtime and modular monolith

Status: Accepted, 2026-10-08.

Context: a small team needs comprehensible domain boundaries and future extraction options without operating distributed services. The inspected repository is a Java 21 / Spring Boot 4.1.1 / Maven starter with no implemented business features.

Decision: retain Java 21 and the Boot baseline, one Maven artifact and one relational database; rename to social-core during Phase 0. Use domain packages, exported APIs and ArchUnit allowlists. Start with Spring MVC. Docker Compose supports local infrastructure. Do not introduce Maven submodules, JPMS, Spring Modulith, Kafka or reactive persistence merely to signal architecture sophistication.

Consequences: package tests provide strong reviewable boundaries without multiple build artifacts. Some JVM-public classes remain semantically internal; tests/review enforce that. Extraction requires deliberate work. Retain Boot-managed JUnit Jupiter 6 as a documented exception to the JUnit 5 default: supported dependency alignment is safer than downgrading the test stack. The source programming model remains Jupiter. [Boot testing reference](https://docs.spring.io/spring-boot/reference/testing/index.html).

Revisit when independent release ownership, build times or extraction needs justify Maven modules/services; benchmark before switching to reactive I/O. Security patch updates within the compatible stack do not need a new architecture decision, but must be tested and documented.
