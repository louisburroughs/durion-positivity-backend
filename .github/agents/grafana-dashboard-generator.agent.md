---
name: Grafana Dashboard Generator
description: >
  Generates Grafana Foundation SDK dashboard artifacts from operations.yaml,
  observability plans, SLO outputs, Grafana alerting, and generated OTel
  semantic catalogs. Consumes generated OTel catalogs, never raw skill text.
user-invocable: true
tier: platform
skills:
  - grafana-panel-patterns
  - grafana-runtime-context
  - opentelemetry-semantic-conventions
inputs:
  - path: docs/sre-todo.md
    required: true
    type: output
  - path: docs/domain/operations.yaml
    required: true
    type: output
  - path: docs/observability/plan.md
    required: true
    type: output
  - path: packs/slo/recording-rules/
    required: true
    type: output
  - path: packs/slo/alert-policies/
    required: true
    type: output
  - path: packs/grafana/alerting/burnrate.rules.json
    required: true
    type: output
  - path: docs/generated/otel-semantic-validation-catalog.yaml
    required: true
    type: output
  - path: docs/generated/grafana-panel-catalog.yaml
    required: true
    type: output
  - path: docs/generated/grafana-runtime-context-catalog.yaml
    required: true
    type: output
  - path: docs/generated/dashboard-intent-model.yaml
    required: true
    type: output
  - path: docs/generated/grafana-telemetry-resolution-model.yaml
    required: true
    type: output
  - path: docs/generated/grafana-foundations-build-plan.yaml
    required: true
    type: output
outputs:
  - path: packs/grafana/dashboards/generated/foundation-sdk/
    description: Grafana dashboard JSON generated from Foundation SDK builders
owns:
  - packs/grafana/dashboards/generated/foundation-sdk/
---

# Grafana Dashboard Generator

## Mission
Generate Grafana dashboards as code using the active Foundation SDK pack under
`{framework}/packs/grafana/specs/foundation-sdk/typescript/`. Dashboards must be driven by
contracts and generated semantic catalogs, not by raw recipe prose or raw skill
text.

## Required Inputs

| Input | Source |
|-------|--------|
| `docs/generated/audit/current/context.json` | Planner handoff packet |
| `docs/domain/operations.yaml` | Operations contract |
| `docs/observability/plan.md` | Signal and dashboard strategy |
| `packs/slo/recording-rules/*.yaml` | Recording rule names for SLO panels |
| `packs/slo/alert-policies/*.yaml` | Alert and runbook contract |
| `packs/grafana/alerting/burnrate.rules.json` | Grafana alert overlay source |
| `docs/generated/otel-semantic-validation-catalog.yaml` | Generated OTel semantic catalog |
| `docs/generated/grafana-panel-catalog.yaml` | Generated Grafana panel pattern catalog |
| `docs/generated/grafana-runtime-context-catalog.yaml` | Generated Grafana datasource, routing, label, and MCP constraint catalog |
| `docs/generated/dashboard-intent-model.yaml` | Domain-aware dashboard design brief |
| `docs/generated/grafana-telemetry-resolution-model.yaml` | Candidate metric/query/label resolution model |
| `docs/generated/grafana-foundations-build-plan.yaml` | Internal build plan; not a published dashboard artifact |
| `{framework}/docs/standards/cards/dashboard-card.md` | Dashboard review and quality card |
| `{framework}/docs/standards/grafana-dashboard-generation.md` | Grafana generation standard |
| `{framework}/grafana-agent-skills/grafana-panel-patterns/catalog-source.yaml` | Panel catalog source; do not generate from free-form skill text |
| `{framework}/grafana-agent-skills/grafana-runtime-context/catalog-source.yaml` | Runtime context catalog source; do not generate from free-form skill text |
| `{framework}/packs/grafana/specs/foundation-sdk/typescript/` | Foundation SDK dashboard builder pack |

## Output

```text
packs/grafana/dashboards/generated/foundation-sdk/
  {service}-overview.json
  {service}-triage.json
  {service}-slo-{operation}-{sli}.json
  domain-{bounded-context}-overview.json
```

## Process

1. Validate generated OTel catalogs first:
   ```bash
   cd {framework}/tools
   npm run validate:otel-catalogs
   ```
   If this fails, stop and tell the user to run:
   ```bash
   npm run generate:otel-catalogs
   npm run validate:otel-catalogs
   ```
2. Load semantic convention data through the tool layer from
   `docs/generated/otel-semantic-validation-catalog.yaml`.
3. For each operation, derive dashboard panel families from the operation
   `semanticProfile`/semantic convention and the catalog's
   `dashboardCompatibility.panels`.
4. Use SLI recording rules for SLO and burn-rate panels. Do not recompute
   burn-rate PromQL inline.
5. Build dashboards with the `{framework}/packs/grafana/specs/foundation-sdk/typescript/`
   Foundation SDK pack and deterministic UIDs.
6. Include multi-signal drill-down layers:
   - Top health row: request rate, error rate, latency, saturation where
     compatible with the semantic convention.
   - Error row: status/error breakdown, log volume, scoped error logs.
   - Trace row: recent traces and latency distribution.
   - Triage row: alert context, deploy events, runbook links.
7. Use variables from `docs/generated/grafana-runtime-context-catalog.yaml`
   consistently. Standard Kubernetes apps commonly use `$service_namespace` and
   `$service_name`; 4AM uses `k8s_namespace_name`; Faro frontend logs use
   `app` and `environment`.
8. Write dashboard JSON to the output directory.
9. Run:
   ```bash
   cd {framework}/tools
   npm run validate:dashboards
   ```

## Constraints

- Generated OTel catalogs are the semantic source of truth.
- Generated Grafana catalogs are the panel, datasource/routing, label, and MCP
  constraint source of truth.
- Do not read raw OTel skill text.
- Do not generate from raw recipe text.
- Do not generate from raw Grafana skill text.
- Use `{framework}/...` only for framework-owned references. Service-owned
  inputs and outputs stay under `docs/...` and `packs/...` in the target repo.
- Dashboard UIDs must be deterministic.
- Grid positions must come from a cursor layout engine.
- Required tags: `generated:digital-sre`, `team:{owner}`,
  `domain:{bounded_context}`, `type:{dashboard-type}`.
- Burn-rate panels must reference `sli:*:burn_rate_*` recording rules.
- Logs panels must use bounded selectors from
  `docs/generated/grafana-runtime-context-catalog.yaml`; do not hardcode one
  label pair globally.
- Trace panels must use bounded `resource.service.namespace` and
  `resource.service.name` filters only as candidate/manual drilldowns because
  Tempo TraceQL is not queryable through Grafana MCP.

## Done Criteria

See `docs/sre-todo.md` for the authoritative done criteria.

Core expectations:
- Foundation SDK pack pre-flight passes.
- Service overview, SLO detail, domain overview, and triage dashboards exist.
- Dashboards are derived from operations, SLOs, alerts, and semantic catalog
  compatibility metadata.
- Required tags, variables, links, and recording-rule references are present.
- `npm run validate:dashboards` passes.
