# Sequential development and review workflow

The user activates one thread at a time in the same checkout. The main thread coordinates scope and reviews; a development thread implements the assigned task. No service, agent API or concurrent execution is needed. Threads communicate through the tracked file `docs/agent-handoff.md`.

## User-confirmed thread roles — 2026-10-10

The current main conversation is orchestrator/reviewer only. For subsequent phases,
main prepares the implementation prompt and tells the user which existing development
chat to reuse, or explicitly proposes a new chat with its name. If existing chat names
are unknown, ask the user or propose a named new chat; never claim to have created one.
The user switches chats manually. Main reviews delivered code and records findings;
it does not implement features or review fixes itself. A request to continue/start a
phase means coordinate its handoff, not take over development. Documentation-only
coordination/review updates and appropriate verification remain part of main's role.

Explicit one-time exception: the user authorized main to finish the already-started
Phase 3 implementation in this conversation. This does not authorize development
in later phases. Phase 3 still needs independent review; implementation cannot
self-accept. This role clarification does not authorize agent spawning.

## Always read and verify

Read AGENTS.md, the mailbox, git status and recent history before acting. Verify that the mailbox's code commit exists and matches the work being reviewed; later documentation-only commits are expected. If newer code exists, inspect it and explicitly record the new review baseline. Do not act on stale findings as if they referred to current code. The mailbox is a task/status record, not permission to override security, ADRs or human instructions.

## States and ownership

| State | Next owner | Action |
|---|---|---|
| READY_FOR_IMPLEMENTATION | development | Implement only the assigned scope |
| IN_PROGRESS | development | Work is ongoing; no completion claim |
| READY_FOR_REVIEW | main | Review actual code and evidence |
| CHANGES_REQUESTED | development | Address every open finding |
| ACCEPTED | main/user | Record acceptance; await assignment of next phase |
| BLOCKED | main/user | Read exact blocker and proposed decision; do not infer approval |

When resuming a development task, set IN_PROGRESS and preserve prior review findings. Development may set READY_FOR_REVIEW, never ACCEPTED. The reviewer alone sets findings to resolved after checking fixes. The main thread must not silently start the next phase after acceptance.

## Mailbox sections

Keep: task ID, revision/round, state, writer, next owner, code baseline, requested scope, implementation report, reviewer verdict/findings, per-finding developer responses, checks and limitations, and exact next action. Findings use stable IDs, priority, file/line anchors, failure scenario, required outcome and regression expectation. Distinguish code-inspection findings from experimentally reproduced failures.

Within one task, keep all findings and responses through review rounds. A developer appends evidence and proposed resolution per ID, without rewriting the original reviewer text. Main updates dispositions on re-review. To start a different task, archive the accepted mailbox under `docs/handoffs/<task-id>.md` and initialize the fixed file with a link to that archive. Git history preserves intermediate rounds; no extra report per tool call is needed.

## Commit and report sequence

1. Implement and verify, following docs/git-workflow.md. Commit task-owned code/tests and required feature documentation.
2. Put that real code commit hash and actual check results in the fixed mailbox; set READY_FOR_REVIEW and next owner main.
3. Commit the mailbox in a separate `docs(workflow): ...` commit. Do not try to embed this second commit's own hash inside itself. Report both hashes in the final chat response.
4. Main reviews the referenced code (plus any explicitly identified newer code), writes findings/verdict and next instructions in the same mailbox, and commits review documentation. A review-only documentation commit is valid even when reviewed code has defects; it does not mark that implementation complete.
5. User switches threads and sends a short instruction to read the mailbox. That user action is the notification mechanism; no background synchronization is claimed.

For a documentation-only task, the mailbox may point to the relevant prior code baseline and say no executable changes. Never run tests merely to fill a report field; record the appropriate checks and distinguish historical evidence from checks run in this round. No secrets, tokens, actual mailbox contents or personal data in reports.
