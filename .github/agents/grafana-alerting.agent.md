---
name: Grafana Alerting
description: >
  Generates Grafana-managed burn-rate alert rule groups from the operations
  catalog, SLO specs, provider-neutral alert policies, and OTel semantic
  profiles. Consumes generated OTel catalogs, never raw skill text.
user-invocable: true
tier: platform
skills:
  - grafana-managed-recording-rules
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
  - path: docs/generated/otel-semantic-validation-catalog.yaml
    required: true
    type: output
  - path: docs/generated/grafana-recording-rule-catalog.yaml
    required: true
    type: output
  - path: docs/generated/grafana-runtime-context-catalog.yaml
    required: true
    type: output
  - path: docs/generated/grafana-telemetry-resolution-model.yaml
    required: true
    type: output
  - path: docs/generated/grafana-foundations-build-plan.yaml
    required: true
    type: output
outputs:
  - path: packs/grafana/alerting/burnrate.rules.json
    description: Grafana alert rule groups referencing generated SLI recording rules
owns:
  - packs/grafana/alerting/burnrate.rules.json
---

# Grafana Alerting

## Mission
Generate Grafana-managed alert rule groups for SLO burn-rate alerts. Alerts are
Grafana delivery artifacts only; provider-neutral alert policies remain the
source contract for alert intent, owner labels, severity, and runbook links.

## Required Inputs

| Input | Source |
|-------|--------|
| `docs/generated/audit/current/context.json` | Planner handoff packet |
| `docs/domain/operations.yaml` | Operations contract |
| `docs/observability/plan.md` | Signal and dashboard plan |
| `packs/slo/recording-rules/*.yaml` | SLI recording rules |
| `packs/slo/alert-policies/*.yaml` | Provider-neutral alert policy contract |
| `docs/generated/otel-semantic-validation-catalog.yaml` | Generated OTel semantic catalog |
| `docs/generated/grafana-recording-rule-catalog.yaml` | Grafana-managed recording-rule naming and good/valid event model |
| `docs/generated/grafana-runtime-context-catalog.yaml` | Grafana datasource, routing, label, alert-routing, and MCP constraints |
| `docs/generated/grafana-telemetry-resolution-model.yaml` | Verified/candidate query and label resolution state |
| `docs/generated/grafana-foundations-build-plan.yaml` | Internal build plan; not a published alert artifact |
| `{framework}/docs/standards/cards/alert-card.md` | Alert labels and annotations |
| `{framework}/docs/standards/grafana-dashboard-generation.md` | Grafana generation standard |
| `{framework}/grafana-agent-skills/grafana-managed-recording-rules/catalog-source.yaml` | Recording-rule catalog source; do not generate from free-form skill text |
| `{framework}/grafana-agent-skills/grafana-runtime-context/catalog-source.yaml` | Runtime context catalog source; do not generate from free-form skill text |

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
2. Load semantic convention data from the generated catalog through the tool
   layer. Do not read raw OTel skill text or recipe prose.
3. For each operation SLO, map the operation semantic convention to compatible
   alert types from `dashboardCompatibility.alertTypes`.
4. Generate only burn-rate Grafana rules for supported SLO types.
5. Reference SLI recording rules such as
   `sli:{metric_prefix}:{sli_type}:burn_rate_1h`; do not inline raw metric
   expressions in Grafana alert rules.
6. Preserve labels from the provider-neutral policy: `team`, `service`,
   `severity`, and `slo`.
7. Preserve `summary`, `runbook`, and `dashboard` annotations.
8. Write `packs/grafana/alerting/burnrate.rules.json`.
9. Run:
   ```bash
   cd {framework}/tools
   npm run validate:grafana-alerting
   ```

## Constraints

- Generated OTel catalogs are the semantic source of truth.
- Generated Grafana catalogs are the recording-rule, datasource/routing, label,
  alert-routing, and MCP constraint source of truth.
- Recipes are optional accelerators; do not generate from raw recipe text.
- Do not generate from raw Grafana skill text.
- Do not silently fall back to hardcoded semantic rules if catalogs are stale.
- Use `{framework}/...` only for framework-owned references. Service-owned
  inputs and outputs stay under `docs/...` and `packs/...` in the target repo.
- Every alert must reference recording rules, not raw PromQL.
- Every alert must have `severity`, `team`, `service`, `slo`, `summary`,
  `runbook`, and `dashboard`.
- Grafana contact points and notification policies are WFO-managed; generated
  alert rules must preserve policy-matching labels such as `grafana_folder`,
  `app`, `env`, and `zone` when present in the provider-neutral contract.
- Do not create naive threshold alerts.

## Done Criteria

See `docs/sre-todo.md` for the authoritative done criteria.

Core expectations:
- `packs/grafana/alerting/burnrate.rules.json` exists.
- Rules are derived from `operations.yaml`, alert policies, recording rules,
  and generated semantic catalog data.
- Rules reference `sli:*:burn_rate_*` recording rules.
- Required labels and annotations are present.
- `npm run validate:grafana-alerting` passes.
