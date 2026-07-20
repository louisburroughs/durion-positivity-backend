# OTel Skills Reference

This file is the authoritative index for OTel skills and instrumentation state semantics.
Referenced by `.github/copilot-instructions.md` and OTel instrumentation agents.

## Skill table

Skills live under `{framework}/opentelemetry-agent-skills/{skill-name}/SKILL.md`.
Load the relevant SKILL.md before any instrumentation, SDK setup, or semantic convention work.
Never rely on model memory for OTel topics.

| Skill | Read when |
|-------|-----------|
| `opentelemetry-manual-instrumentation` | Planning, adding, or reviewing any span instrumentation |
| `opentelemetry-sdk-setup` | Setting up or reviewing TracerProvider, exporters, processors, propagators |
| `opentelemetry-sdk-versions` | Choosing the correct SDK or package version for any language |
| `opentelemetry-semantic-conventions` | Naming any span or attribute on a known boundary type |
| `span-events-to-logs-migration` | Any code that uses `AddEvent`, `RecordException`, or `recordException` |
| `telemetrygen` | Generating test telemetry or validating pipeline connectivity |
| `michelin-collector-config` | Migrating from localhost/DevStack to Michelin production endpoints; resource attributes, TLS, multi-tenant routing (`X-Scope-OrgID`), Faro RUM, CORS propagation |

Load companion reference files on demand - each SKILL.md lists which are available.

## Instrumentation state semantics

Produced by `detect-auto-instrumentation.ts` and stored in `docs/sre-todo.md`.
**No downstream agent re-detects.** Agents read the value from `sre-todo.md`.

| State | Meaning | Agent action |
|-------|---------|-------------|
| `greenfield` | No existing instrumentation | Full SDK setup + spans |
| `api-only` | OTel API only; SDK managed by auto-agent | Add spans using API only; no SDK init in app code |
| `preserve` | Existing valid manual spans | Patch gaps only; preserve conformant spans |
| `double-init` | Multiple SDK inits - CI blocker | Resolve before any span work |
| `mixed-conflict` | Multiple incompatible setups | Requires brownfield analysis first |

State `double-init` is always a CI blocker. Agents must resolve it before adding spans.
State `api-only` forbids all SDK init code in application source.

## operations.yaml instrumentation block (schema v1.2)

```yaml
instrumentation:
  state: api-only        # greenfield | api-only | preserve | double-init
  agent: otel-auto-node  # from detector output
  agent_version: "x.y.z"
  resource_config: env-vars   # env-vars (agent) | code (SDK)
```

See `{framework}/docs/standards/auto-instrumentation-api-pattern.md` for the full pattern.
