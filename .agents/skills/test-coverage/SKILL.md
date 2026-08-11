---
name: test-coverage
description: Drive comprehensive test creation for a feature, spec, module, or PR. Decide what must be tested, pick the testing strategy at the lowest sufficient cost, drive the test producers to author the tests, run them, and record the session — what was tested, by what test type, and what passed — in specs/<feature>/test-spec.md and test-report.md. Drives the producers create-yaml-tests and create-agent-tests.
---

# Test Coverage

Test-creation workflow for one feature, spec, module, PR, or ticket at a time.
Use after a feature is implemented (or alongside it, or after a PR) to decide
what needs testing, choose the right testing strategy, drive the producers to
write comprehensive tests, run them, and record the result.

It produces human-readable Markdown (`test-spec.md`, `test-report.md`) and test
code via the producers. The report records facts — what was tested, by what test
type, and what passed — not a graded confidence verdict.

## What This Skill Owns

- `specs/<feature>/test-spec.md` — the durable "testing what" contract: the
  behaviors, invariants, risks, and the declared priority of each.
- `specs/<feature>/test-report.md` — the session record: what was tested, by
  what test **type**, what ran, and what passed, failed, or was blocked.
- Repo-root `TESTING.md` (optional) — project testing-strategy notes (preferred
  modalities, gate/posture by priority, context labels). Standalone-safe: a
  baked-in default applies when absent.

## Two Layers

1. **Testing what** — the behaviors, properties, requirements, and invariants
   that must be verified to trust the feature, each carrying a declared
   **priority** (P0–P3).
2. **Testing how** — the evidence used to verify them: unit, contract,
   integration, e2e, agent, manual, telemetry, static, smoke, script.

Comprehensive testing is not test count. Optimize for justified confidence per
unit of cost (author + run + maintain), stability, latency, and diagnostic
value. Buy sufficient confidence at the lowest cost, spending the scarce
expensive-test budget (e2e, agent) where **priority is highest**.

## Priority Is A Declared Fact

Each testing-what item carries a `priority` (P0–P3) read from an upstream dev
artifact — the PRD, the feature breakdown, or the spec — never invented here.
Priority is the effort lever: P0 gets the strongest posture, P3 the lightest.
Per-behavior priority defaults to the feature's declared P-level; record a finer
level in `test-spec.md` only when the spec or owner declares one. If nothing
upstream declares a priority, mark it `UNKNOWN` and surface it for the owner
rather than guessing — a guessed priority is not a fact.

There is no separate 1–5 risk weight. Priority is the single governed importance
signal.

## Test Type Is A Fact, Recorded Here

When you create or identify a test, its **type** (`unit`, `contract`,
`integration`, `e2e`, `agent`, `manual`, `telemetry`, `static`, `smoke`,
`script`) is a fact about the artifact — the producer that makes it fixes the
type. Record the type per behavior in `test-report.md`. Do **not** author a
strength label (`depth`, `reliability`) or a `HIGH/MEDIUM/LOW` verdict — the
report records facts (priority, test type, run result), not a graded confidence
verdict.

## Testing Strategy And Budget Policy

Resolve the strategy posture for each behavior, first match wins:

1. A per-behavior note in `test-spec.md` (one-off tuning).
2. Repo-root `TESTING.md`, if present (project-wide posture notes).
3. The baked-in default in `assets/default-testing-strategy.md`.

The default holds the modality economics, the decision principle, the three
guardrails (capability-before-cost; priority-floor / budget-ceiling;
defense-in-depth), and the non-negotiable floors (unit coverage on core/changed
logic; at least one gate on every P0 check). Standalone-safe: never require
`TESTING.md`; fall back to the baked-in default when absent.

`TESTING.md` is prose guidance the agent reads — not a schema'd config, and not
read by Quality Center scoring (scoring stays transparent). It exists to apply
consistent project-wide posture across features; confirm a `TESTING.md` change
with the user since it shifts every feature's posture.

## Capability Map — Which Modality Proves Which Check

Capability before cost: "cheapest" means cheapest *among modalities that can
actually prove the check*. A unit test cannot prove a real-browser flow.

- Deterministic pure logic → unit tests.
- Public boundaries, server actions, route handlers, authz, schema validation →
  contract tests.
- DB state, transactions, audit rows, migrations, jobs, cross-module invariants
  → integration tests.
- Browser-rendered behavior, routing, session/role-gated UI, rendered
  regressions → project-standard e2e, agent, or manual browser checks.
- Third-party callbacks, staging-only auth, production SLOs, live signals →
  agent tests, manual checks, or telemetry.

## Specialized Test Authoring

This skill decides strategy and drives creation; it delegates the actual
authoring to the producer skills and project workflows, then records what was
made.

- `create-yaml-tests`: deterministic Shiplight YAML E2E tests (Playwright +
  agentic SDK).
- `create-agent-tests`: coding-agent-driven Markdown cases for live-environment
  verification (browser, API, DB, logs, cloud, telemetry).
- The project's own unit, contract, integration, browser, mobile, load,
  migration, or telemetry workflow for other kinds.

For code-tied tests (unit, contract, integration, api), author them inline or
drive the base coding agent toward deep, high-coverage tests on the
highest-priority gaps. Keep edits scoped to tests, fixtures, test scripts, and
the minimal support code needed for testability.

When an agent test produces a report, record its `PASS`/`FAIL`/`BLOCKED`/
`ABORTED` status and auditable artifacts (HTML reports, screenshots, videos,
traces, logs) in `test-report.md`. Text-only browser claims are not sufficient;
require an auditable artifact for browser-driven cases. Treat `ABORTED` as an
orchestration interruption to rerun, not as product evidence.

## Workflow

### 1. Resolve Target And Inputs

- Identify the feature target (a `NNN-kebab-case` slug; reuse an existing
  `specs/NNN-*` slug when one exists).
- Read the PRD / feature breakdown / spec for declared priorities.
- Read changed implementation and existing tests (use the branch merge base when
  available).
- Locate prior `specs/<feature>/test-spec.md` and `test-report.md` if present.

### 2. Define Or Refresh Testing What

Write `specs/<feature>/test-spec.md` from `assets/test-spec-template.md`.
Capture product behaviors, system/API/schema/data invariants, risk-based
behaviors, operational/release behaviors, and stakeholder confidence goals.
Carry each behavior's declared `priority` as a fact. List out-of-scope behaviors
explicitly.

### 3. Resolve Strategy And Inventory Existing Evidence

Resolve each behavior's posture via the policy chain. Inventory what already
exists (unit, contract, integration, e2e, agent, manual, telemetry, static, CI)
and which behaviors it covers.

### 4. Analyze Gaps And Select Proofs

Size each gap as *required posture minus existing evidence*. Choose the cheapest
modality **capable** of closing it. Spend the expensive-test budget on the
highest-priority gaps; consider confidence gained, flake risk, runtime, fixture
complexity, cleanup, diagnostic value, and maintenance. For P0 behaviors,
consider defense-in-depth (stacked layers) over a single cheapest proof.

### 5. Drive Creation

Drive the producers (or author code-tied tests inline) to close worthwhile gaps,
honoring the non-negotiable floors regardless of budget. Add tests only when
they materially raise confidence; do not add brittle tests to inflate count.
When budget forces a behavior to stop short of its ideal proof, record the
chosen allocation and the knowingly accepted gap — never under-test silently.

### 6. Run Verification

Run targeted checks first, then broader suites when justified: new/changed
tests, relevant existing tests, typecheck/lint/build, migration checks, and
agent checks required by the spec. Record exact commands and outcomes. If a
capability is missing, mark it `BLOCKED` or `NOT MEASURED`; never claim it
passed.

### 7. Write Or Update Test Report

Write `specs/<feature>/test-report.md` from `assets/test-report-template.md`.
Record commands run, tests added/updated, and the coverage matrix — one row per
behavior with its **priority**, the **test type** written for it, and the
session result. Put blocking findings first. Do not author a confidence verdict
or any strength label.

## Artifact Skeletons

| Artifact | Template |
| --- | --- |
| `specs/<feature>/test-spec.md` | `assets/test-spec-template.md` |
| `specs/<feature>/test-report.md` | `assets/test-report-template.md` |
| repo-root `TESTING.md` (optional) | `assets/TESTING.template.md` |

Status vocabularies (test type, result status, coverage status) live in
`references/vocabularies.md`.

## Operating Rules

- May edit tests, test fixtures, test scripts, `specs/<feature>/test-spec.md`,
  `specs/<feature>/test-report.md`, and repo-root `TESTING.md` (only with user
  confirmation, since posture changes affect every feature).
- Never author `depth`, `reliability`, a 1–5 risk weight, or a `HIGH/MEDIUM/LOW`
  confidence verdict. Record facts (priority, test type, run result) only.
- Avoid unrelated refactors and unrelated production-code changes.
- Never include secrets, cookies, tokens, database URLs, or private customer
  data in specs, reports, logs, or artifacts.
- Never report pass/fail without command output or an auditable artifact.
- Stay standalone-safe: never require `TESTING.md`; fall back to the baked-in
  default when absent.

## When Not To Use

- When the user wants project orchestration, a PRD, or a feature breakdown:
  that is project-level work outside this skill's scope.
- When the user only wants a code review with no test creation: use `review`.
- When implementation does not exist and the user only wants planning.
