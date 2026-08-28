# Issue #1541 — Subagent Execution Plan

Move the 291 ungranted service interfaces into `internal.service` and add the ADR-0026 D5
producer-side rule. Governing decision: ADR-0026 amendment 2026-08-27 (D1–D5) in
`durion/docs/adr/0026-service-contract-boundary-policy.adr.md`.

Issue: https://github.com/louisburroughs/durion-positivity-backend/issues/1541
Branch: `claude/execute-1541-subagents-zlnapi` (both repos; durion is touched only in close-out).

## Why this parallelizes cleanly

- Cross-module imports of any module's `service` package are **zero** platform-wide (ADR-0044
  did its job) — every module migration touches only its own directory tree.
- Modules are independently mergeable per the issue; commits are per module.
- The only shared files are `pos-archunit` tests (Wave 0 + close-out, serialized) and
  `CLAUDE.md`/`AGENTS.md` (close-out only).

## Orchestration mechanics (all waves)

- Orchestrator (main session) dispatches subagents; **agents never run `git commit`** — the
  orchestrator commits each module serially after the agent's verification passes, avoiding
  index contention.
- Max **3 concurrent** migration agents (Maven runs share `~/.m2`; use only `test` goals,
  never `install`, while parallel).
- Per-module verification gate before commit:
  1. `./mvnw -pl pos-X spotless:apply` (then check `git status` — spotless formats main *and* test)
  2. `./mvnw -pl pos-X -am test` (includes the module's own `ArchitectureTest`)
  3. `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test` — D5 report count must drop
     by exactly the module's expected type count
  4. Repo-wide grep for the old FQNs (`com.positivity.X.service.Foo`) including
     `src/main/resources`, YAML, SpotBugs exclude files — string references don't refactor
     themselves.
- Commit convention: `refactor(pos-X): move ungranted service interfaces to internal.service (ADR-0026 D3, #1541)`
- Each migration agent also updates its module's `ArchitectureTest`
  (`only_service_layer_should_be_public_api`, `service_package_should_define_interfaces_only`
  keep shape assertions, gain the D4 dependency assertion) — not deferred to close-out.
- Subagent prompts must direct backend code research to `mcp__tokensave-backend__*` tools where
  the agent definition provides them (per durion `CLAUDE.md`).

## Waves

### Wave 0 — D5 producer-side rule, report mode (serial, 1 agent: anvil)

- Add to `pos-archunit` `ArchitectureTests`: no type in a module's public `service` /
  `service.model` package may depend on that module's `internal.*`.
- **Anchor the package exactly** — `..service..` also matches `internal.service`
  (`ArchitectureTests.java` already leans on that ambiguity at :199/:234). Match
  `com.positivity.(*).service` and `com.positivity.(*).service.model` explicitly or via a
  `DescribedPredicate` resolving the module root.
- Report per-module leak counts; **no `-D` threshold** — this gates at 0 after migration.
- Regression-guard `SupplierStockService` D4 compliance (grant-surface types depend only on
  `service.model`, `pos-shared-dtos`, `pos-domain-events`, JDK).
- Deliverable: report output confirming the issue's census (291 ungranted / 226 leaking).

### Wave 1 — reference migration: pos-price (serial, 1 agent: Domain Data Coder)

9 types, already has `service.model`. Produces the **recipe** all later agents follow.
Must resolve and record:

- Whether ungranted `service.model` types move alongside their interfaces (census ambiguity;
  D1 names `service.model` as grant surface, so an ungranted module keeps no `service.model`).
- Relocation of pos-price's non-interface type (`EligibilityDecision` — one of the 8 flagged
  by the census).
- The exact per-module `ArchitectureTest` D4 assertion wording.

The orchestrator freezes the recipe into the standard migration prompt before Wave 2.

### Waves 2–6 — parallel migrations (Domain Data Coder, ≤3 concurrent)

| Wave | Agent batches (types to move) |
|---|---|
| 2 | A: pos-tax 4, pos-documents 1, pos-image 2, pos-vehicle-fitment 2, pos-warranty 1, pos-events 1 · B: pos-event-receiver 4, pos-marketing 4, pos-people-contact 6 · C: pos-vehicle-inventory 6, pos-order 6, pos-bulk-loader 7 |
| 3 | A: **pos-supplier 7** (SupplierStockService **stays** — sole granted type) · B: pos-location 10 · C: pos-shop-manager 11 |
| 4 | A: pos-invoice 12 · B: pos-people 14 · C: pos-security-service 17 |
| 5 | A: pos-customer 18 · B: pos-catalog 18 · C: pos-mcp-server 20 |
| 6 | A: pos-workorder 25 · B: pos-accounting 39 · then solo: **pos-inventory 47** |

- pos-inventory gets a dedicated agent: `internal.service` already holds 131 classes — split
  by subdomain the way pos-supplier does (`internal/{subdomain}/service`), don't flatten to 178.
- Non-interface census types in pos-events, pos-invoice, pos-mcp-server are relocated inside
  their module's own migration (to `internal` with their interface; `service.model` only if the
  module keeps a grant surface — today only pos-supplier does).
- **No signature, DTO, or behaviour changes** anywhere; imports only.

### Wave 7 — close-out (serial, 1 agent + Code Review Agent)

- Flip the D5 rule from report mode to build-failing at 0.
- Update backend `CLAUDE.md` §Module Structure and `AGENTS.md` — both still describe `service/`
  as "PUBLIC API surface usable by other modules" per the superseded §1–§3.
- Audit that every migrated module's `ArchitectureTest` carries the D4 assertion.
- durion repo (same branch name): note the ADR-0026 amendment in ADR-0044 §7's cross-reference
  (§7's "extend, and do not alter" claim stands — reference note only).
- Full gate: `./mvnw -DskipTests=false clean test` + `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test`.
- **Code Review Agent** pass over the full branch diff against ADR-0026 D1–D5 before any PR.

## Explicitly out of scope (from the issue)

- Reopening ADR-0044 R1; retiring the interface/implementation split.
- Migrating DTOs into `service.model` for interfaces becoming internal.
- The 31 `internal.entity` signatures — ordinary design concern once internal; separate issues.

## Standard migration prompt (template, finalized after Wave 1)

> Module(s): pos-X (N ungranted types). Branch `claude/execute-1541-subagents-zlnapi` is checked
> out; do NOT commit — verify and report.
> Move every type in `com.positivity.X.service` (and `service.model` per the Wave-1 recipe)
> to `com.positivity.X.internal.service..` (subdomain split if `internal.service` is large),
> updating all imports. No signature/DTO/behaviour changes. Keep interface/impl split: impls
> implement the moved interfaces; `internal.controller` depends on them at the new location.
> Update the module `ArchitectureTest` per the recipe. Grep the whole repo for old FQN strings
> (resources included). Then run the four verification gates and report: files moved, leak-count
> delta in the D5 report, test results. Backend code research: use `mcp__tokensave-backend__*`
> tools, not Read/grep, where available.

## Risks

| Risk | Mitigation |
|---|---|
| D5 rule pattern matches `internal.service` | Anchor exactly (Wave 0's one subtle task; anvil, evidence-first) |
| FQN strings in config/SpotBugs excludes/OpenAPI | Mandatory repo-wide grep in every migration |
| Concurrent Maven corrupting `~/.m2` | ≤3 agents, `test` goals only |
| Spotless staging surprises | `git status` check before orchestrator commits |
| pos-inventory flattening | Dedicated agent, pos-supplier subdomain pattern mandated |
| Census drift since 2026-08-27 | Wave 0 re-verifies counts from the report rule, not the issue text |

## PR strategy (decision point)

The issue wants per-module independent mergeability, but this session pins one branch. Default:
one branch, one commit per module, single PR at close-out (opened only on request, via the Pull
Request Agent). If earlier landing is wanted, cut per-wave PRs from the same commit series.

## Estimated effort

~14 subagent runs: 1 (rule) + 1 (reference) + 11 (migration batches) + 1 (close-out) + 1 (review).
