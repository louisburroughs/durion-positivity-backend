# digital-sre Repo: Copilot Instructions (Repo-wide)

You are working in the `digital-sre` repository. This repo provides:
- Copilot custom agents for observability/SRE tasks
- Templates and standards (SLIs/SLOs, recording rules, alert policies)
- Grafana dashboard and alerting knowledge sources, catalogs, and builders
- Tooling that validates outputs and enforces consistency

## Deployment modes

This repo supports two operating modes. Check for `sre.config.yaml` at the workspace root.

### Standalone mode (no `sre.config.yaml`)

You are working inside digital-sre itself (development, testing, examples).
- Framework references (standards, recipes, skills): workspace root
  - e.g., `docs/standards/`, `packs/recipes/`, `opentelemetry-agent-skills/`
- Output paths (agent-produced artifacts): `examples/` directory
  - e.g., `examples/docs/domain/operations.yaml`, `examples/packs/slo/alert-policies/`
- Validators: `tools/` at workspace root
- Agents: `.github/agents/` at workspace root

### Consumed mode (`sre.config.yaml` exists)

You are working in a target service repo that consumes digital-sre.
- Framework references: `{framework.path}/docs/standards/`, `{framework.path}/packs/recipes/`, `{framework.path}/opentelemetry-agent-skills/`, `{framework.path}/grafana-agent-skills/`
  - Where `{framework.path}` = value of `framework.path` in `sre.config.yaml` (default: `.sre/`)
- **Agents: `.github/agents/` at the consumer repo root** (copied from `{framework.path}/.github/agents/`)
- Output paths: defined by `outputs.root` in `sre.config.yaml` (default: `.` = repo root)
  - e.g., `docs/domain/operations.yaml`, `packs/slo/alert-policies/`
- Validators: `{framework.path}/tools/`
- Skills: `{framework.path}/opentelemetry-agent-skills/`

### Path shorthand used in agent files

Agent files use `{framework}/` as shorthand for the framework path:
- Standalone: `{framework}/` = workspace root (empty prefix)
- Consumed: `{framework}/` = value of `framework.path` from `sre.config.yaml` (default `.sre/`)

## Non-negotiables
1) Prefer deterministic, repeatable outputs. Avoid ad-hoc structures.
2) Any new alert/spec must have:
   - stable naming
   - minimal cardinality risk
   - clear ownership metadata
   - runbook linkage for alerts
3) Tooling must be robust:
   - TypeScript (node >=18)
   - strict type checking
   - helpful error messages
   - zero secrets in repo (use env vars)
4) Provide docs and examples so users can adopt quickly.
5) Do not generate dashboards, alert rules, or recording rules from raw skill text or raw recipe text. Use generated catalogs and structured contracts.

## Style
- Use clean, minimal markdown; avoid fluff.
- Prefer small, composable functions with tests (where reasonable).
- Favor explicit configuration over magic.
- Use consistent naming:
  - files: kebab-case
  - TS symbols: camelCase, PascalCase types
  - agent ids: kebab-case

## Observability principles
- Transport spans (HTTP/RPC) stay standard.
- Business spans are manual and named with pattern `{Verb} {BusinessObject}`.
- Span names must not contain IDs or high-cardinality values.
- Attributes must follow a taxonomy and must avoid unbounded cardinality.

## Required validation
Every PR should pass:
- tools: `npm run validate` (resolves to `validate:workflow` - lightweight by default)
- docs: operations.yaml schema validation
- slo: recording rules + alert policy validation
- grafana: `npm run validate:grafana` or the targeted Grafana validation script for the changed artifact

Run `npm run validate:full` only when preparing a release or explicitly requested.

## Package asset boundary
Do not read `tools/assets/**` unless the task is packaging, publishing, release validation, or verifying packaged output. Treat `tools/assets/**` as generated/package content, not source of truth.

## Agent contract (must follow)
- Check for `sre.config.yaml` first to determine operating mode (see Deployment modes above).
- **Agent location:**
  - Standalone: agents run from `.github/agents/` at workspace root
  - Consumed: agents are copied to `.github/agents/` at consumer repo root (not in framework.path)
- In consumed mode: read `operations.yaml` and `plan.md` from `outputs.root` (default repo root).
- In consumed mode: read standards from `{framework.path}/docs/standards/`.
- In standalone mode: read `operations.yaml` from `examples/docs/domain/operations.yaml` and standards from `docs/standards/`.
- Do not invent new business operations; add them to `operations.yaml` first if missing.
- Do not write generated artifacts under `{framework.path}` in consumed mode. Framework paths are read-only references; write outputs under `outputs.root`.

## Alert policy generation policy (prevent drift)
- Recording rules and alert policy YAML should be generated from specs/contracts and
  stored under the output `packs/slo/recording-rules/**` and `packs/slo/alert-policies/**`.
- Do not manually edit generated outputs; update specs/contracts and regenerate.

## Grafana generation policy
- Grafana agents are `grafana-alerting.agent.md` and `grafana-dashboard-generator.agent.md`.
- Grafana knowledge sources live under `{framework}/grafana-agent-skills/`; generators consume generated catalogs, not free-form `SKILL.md` prose.
- Required generated catalogs and models include:
  - `docs/generated/grafana-panel-catalog.yaml`
  - `docs/generated/grafana-recording-rule-catalog.yaml`
  - `docs/generated/grafana-runtime-context-catalog.yaml`
  - `docs/generated/dashboard-intent-model.yaml`
  - `docs/generated/grafana-telemetry-resolution-model.yaml`
  - `docs/generated/grafana-foundations-build-plan.yaml`
- Foundation SDK reference packs are read from `{framework}/packs/grafana/specs/foundation-sdk/typescript/`.
- Generated Grafana outputs stay in the service repo:
  - `packs/grafana/alerting/burnrate.rules.json`
  - `packs/grafana/dashboards/generated/foundation-sdk/`
- PDC/Michelin datasource routing, label mappings, and Grafana MCP limits are catalog data from `grafana-runtime-context-catalog.yaml`; do not hardcode them in agents.
- Do not publish silent no-data panels. Missing metrics, labels, logs, or traces must become skipped panels or telemetry gap entries.

## Determinism and stability
- Keep alert policy IDs stable.
- Keep policy names stable.
- Use bounded labels only (env/service/operation). No user IDs.

## Alert requirements
- All alerts must include:
  - labels: `team`/`owner`, `severity`, `service`
  - annotations: `summary`, `runbook`

## Agent system
Agents live in `.github/agents/*.agent.md`. They must:
- define mission, inputs, outputs
- define done criteria
- include constraints that prevent bad telemetry (cardinality, privacy)
- produce the standard outputs in docs/ and packs/

## Idempotency (mandatory for all agents)
- Always **overwrite** output files completely. Never append to existing outputs.
- Sections marked `<!-- @sre-managed: false -->` are user-owned; preserve them exactly.
- Running the same agent twice with the same inputs must produce byte-identical outputs (excluding timestamps).
- If you cannot guarantee idempotency for a file, emit a warning comment at the top of that file.

## I/O contracts and file ownership
Every agent frontmatter declares `inputs`, `outputs`, `owns`, and `proposes`.
- `owns` - exclusive write access. Only one agent may own a file. Write the file directly.
- `proposes` - shared file. Write your changes to `{file}.patch.{agent-id}` sidecar. The Orchestrator merges after the parallel phase completes.
- If two agents both `own` the same file, that is a configuration error - fail immediately with a clear message identifying both agents.
- Before delegating to an agent, the Orchestrator must verify all `required: true` inputs exist. If any are missing, skip the agent and mark the phase blocked in `docs/sre-todo.md`.

## Conflict resolution for parallel phases
When phases 3a, 3b, and 3g run in parallel, all three `propose` changes to `docs/domain/operations.yaml`.
Each agent writes to `docs/domain/operations.yaml.patch.{agent-id}`. The Orchestrator merges them after all three complete by applying each patch's additions (do not overwrite existing fields).

When adding new skills:
- add a new agent file
- add docs/standards entry if needed
- add validation rules in tools/ if the skill produces artifacts

## HTTP span contract (enforceable)
- All HTTP spans must conform to `{framework}/docs/standards/http-span-contract.md`.
- Span names: `{METHOD} {parameterized_route}` - no IDs, no query strings.
- Validated by:
  - `{framework}/tools/span-contract/validate-span-contract.ts` (static analysis)
  - `{framework}/tools/span-contract/conformance-test.ts` (runtime)

## Recipes (atomic building blocks)
- Reusable span/SLI/panel recipes live in `{framework}/packs/recipes/`.
- Operations from `operations.yaml` map to recipes.
- Generators compose recipes into concrete artifacts.
- Always reference existing recipes before creating new instrumentation.
- Grafana panel and recording-rule catalogs are the enforcement source for visualization and recording-rule choices.

## AI as Orchestrator
- AI agents propose diffs to structured artifacts only.
- Deterministic generators (not AI) produce concrete outputs.
- See `{framework}/docs/standards/ai-orchestrator-pattern.md`.

## Onboarding
- New services start by invoking `@sre-orchestrator` in Copilot Chat.
- The orchestrator delegates to specialist agents that produce real, grounded artifacts.
- For validation only: `make validate` or `cd tools && npm run validate`.

When adding new skills:
- add a new agent file
- add docs/standards entry if needed

## Subagent orchestration
The SRE Orchestrator agent coordinates the full workflow using subagents.
- Planner and Progress Tracker agents are subagent-only (`user-invocable: false`).
- GenAI Evaluator and GenAI Cost Guardian are subagent-only (called by GenAI Observability Assistant).
- Grafana Alerting runs after SLI/SLO specs, provider-neutral alert policies, and recording rules exist.
- Grafana Dashboard Generator runs after Grafana alerting and consumes the dashboard intent, telemetry resolution, and build-plan models.
- All specialist agents can be used standalone or as subagents.
- The Orchestrator restricts its `agents` list to only SRE workflow agents.
- Subagents inherit context from the Orchestrator session but run with their own
  tool permissions and instructions.
- The Orchestrator must always validate outputs via the Progress Tracker before
  proceeding to the next phase.
- All detection (stack, instrumentation, frontend, GenAI) runs once in the Planner.
  Downstream agents read from `docs/sre-todo.md` - they do not re-detect.
- Done criteria live in `docs/sre-todo.md` only. Agent files reference it.

## OpenTelemetry agent skills (mandatory for all OTel agents)

Skills live under `{framework}/opentelemetry-agent-skills/{skill-name}/SKILL.md`. Load the
relevant SKILL.md at the start of each task before any instrumentation, SDK setup, or
semantic convention work. Never rely on model memory for OTel topics.

See `.github/agents/references/otel-skills-reference.md` for the full skill table and
companion file index.

## Instrumentation state detection (centralized in Planner)

The SRE Planner runs `detect-auto-instrumentation.ts` and
`detect-genai-instrumentation.ts` once during Phase 0. Results are stored
in `docs/sre-todo.md` under the `instrumentation_state` section.

**No downstream agent re-runs detection.** Instrumentation agents read their
state from `docs/sre-todo.md` and act accordingly.

See `.github/agents/references/otel-skills-reference.md` for state semantics and
the `auto-instrumentation-api-pattern.md` standard.
