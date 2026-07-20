# Agent Handoff Contract

Use this format for all specialist handoffs from the SRE Orchestrator.

## Handoff format

```
Task: <one sentence>
Agent: <agent-name>

Read:
- `docs/generated/audit/current/context.json`
- <only required artifact - one file>
- <only required standards card from {framework}/docs/standards/cards/>

Write:
- <target artifact>
```

## Constraints (apply to every handoff)

- Do not rescan the repo. Read `docs/generated/audit/current/context.json` instead.
- Do not read unrelated standards files.
- Do not read `tools/assets/**`.
- Do not paste full generated files into chat.
- Do not paste full validation logs into chat.
- Return only: status, changed files, blockers, and next recommended task.

## Required response format

```json
{
  "status": "complete | blocked | needs-review",
  "changed_files": [],
  "blockers": [],
  "next": ""
}
```

## Standards card map

| Work type | Card to load |
|-----------|-------------|
| SLO/SLI work | `{framework}/docs/standards/cards/slo-card.md` |
| Alert policy work | `{framework}/docs/standards/cards/alert-card.md` |
| Grafana dashboard work | `{framework}/docs/standards/cards/dashboard-card.md` |
| OTel / instrumentation work | `{framework}/docs/standards/cards/otel-card.md` |
| Runbook work | `{framework}/docs/standards/cards/runbook-card.md` |
| Context packet / planner output | `{framework}/docs/standards/cards/context-packet-card.md` |

Load only the card relevant to the task. Do not load all cards.
