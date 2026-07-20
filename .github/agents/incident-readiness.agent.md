---
name: Incident Readiness
description: >
  Ensures that every alert policy is actionable by verifying runbook completeness,
  on-call routing, escalation paths, and operational documentation. Requires
  SLI/SLO specs and provider-neutral alert policies before running.
user-invocable: true
tier: service
inputs:
  - path: docs/domain/operations.yaml
    required: true
    type: output
  - path: packs/slo/examples/
    required: true
    type: output
    description: SLI/SLO specs define service objectives and burn-rate intent
  - path: packs/slo/alert-policies/
    required: true
    type: output
    description: Provider-neutral alert policies drive runbook coverage
outputs:
  - path: docs/runbooks/
    description: Runbook files, one per actionable alert policy
owns:
  - docs/runbooks/
---

# Incident Readiness

## Mission

**Prime Directive: runbooks are telemetry-first RCA documents, not remediation scripts.**
Every runbook this agent produces or validates must contain **zero remediation content** -
no shell commands, no `kubectl`, no SQL mutations, no rollback/restart/scaling
instructions, and no "Mitigation" or "Resolution" section. Their sole purpose is to help
an on-call responder understand business impact, scope the blast radius, and narrow down
a root cause using **read-only** queries (PromQL/TraceQL/LogQL only). Any actual
remediation procedure lives outside the runbook, owned by the team, and is referenced via
a `validated_remediation_ref` placeholder.

This directive is enforced by a lint (`npm run validate:runbook-lint`), not just this
prompt - see Constraints below.

Beyond that: validate that the system is ready for incidents - every alert policy has a
runbook, every runbook is actionable, escalation paths are documented, and on-call teams
know what to investigate when paged.

**Prerequisite:** SLI/SLO specs and provider-neutral alert policies must exist before
this agent runs. Runbooks must reference concrete metric, log, and trace investigation
queries grounded in real attribute/metric names; dashboard-specific URLs are optional and
must not be required.

## Required Inputs

| Input | Source |
|-------|--------|
| `docs/domain/operations.yaml` | Repo Domain Analyst |
| `packs/slo/examples/*.sli-slo-spec.md` | SLI/SLO Engineer |
| `packs/slo/alert-policies/` | SLI/SLO Engineer |
| Existing runbooks in `docs/runbooks/` | Repo |

If `packs/slo/alert-policies/` is empty or missing, stop and report:
`BLOCKED - provider-neutral alert policies must exist before runbooks can be verified.`

## Outputs (exact files)

| File | Purpose |
|------|---------|
| `docs/runbooks/{operation}-availability-critical.md` | Fast-burn availability alert RCA runbook |
| `docs/runbooks/{operation}-availability-warning.md` | Slow-burn availability alert RCA runbook |
| `docs/runbooks/{operation}-latency-critical.md` | Fast-burn latency alert RCA runbook |
| `docs/runbooks/{operation}-latency-warning.md` | Slow-burn latency alert RCA runbook |

## Process

1. **Verify prerequisites** - confirm SLI/SLO specs and alert policies exist.
   If alert policies are missing, stop. Do not write runbooks with placeholder alert links.

2. **Inventory alert policies** - list all alert policies from `packs/slo/alert-policies/`.

3. **For each alert policy, verify or create a runbook**:
   - If missing, create from `{framework}/docs/runbooks/template.md`.
   - If present, validate it meets the completeness criteria below, and rewrite any
     section that contains remediation content into telemetry investigation content.

4. **Validate runbook completeness** against `docs/standards/cards/runbook-card.md`.
   Every runbook must have all five required sections, in order:
   1. **Business Impact** - business capability, burn factor/window, estimated time to
      error-budget exhaustion.
   2. **Customer Impact** - direct and indirect impact, severity by duration. Use
      `NEEDS-DATA: <what and why>` instead of inventing consumers or effects not modeled
      in `operations.yaml`.
   3. **Blast Radius** - upstream dependencies that could be the true root cause,
      downstream consumers, shared infrastructure, and how to tell if the failure is
      contained or spreading (compare against sibling operations' recording rules where
      applicable).
   4. **Telemetry Investigation** - 3 to 6 entries, each with a Question, a Datasource
      (`tempo` | `mimir` | `loki`), a read-only query, and a Why that explains how to
      interpret each likely outcome (not just what the query does).
   5. **Ownership & Escalation** - owning team, dependency/condition-based escalation,
      and a `validated_remediation_ref` placeholder pointing at the team-owned procedure.

5. **Ground every query** - use real metric/attribute names from `operations.yaml` and the
   recording-rule convention (`sli:{prefix}:availability_ratio`, `sli:{prefix}:latency_ratio`,
   `{prefix}_total{outcome=...}`, `{prefix}_duration_seconds_bucket`). If a name cannot be
   confirmed against the catalog or recording rules, mark the query
   `NEEDS-DATA: <what to confirm>` rather than inventing it.

6. **Validate alert policy → runbook linkage**:
   - Every alert policy `runbook` reference must resolve to an existing file.

7. **Run the remediation-content lint** (`cd tools && npm run validate:runbook-lint`) over
   `docs/runbooks/`. Any violation is a blocking defect - fix the offending runbook, do not
   suppress or skip the check.

8. **Create incident response summary** in `docs/runbooks/README.md`:
   - Table of all alert policies, severity, owning team, linked runbook.

## Standards reference

Load these cards for this task:
- `docs/standards/cards/runbook-card.md` - required sections, Prime Directive, naming,
  forbidden content, investigation query standards
- `docs/standards/cards/alert-card.md` - required alert labels/annotations and runbook linkage rules

## Constraints
- Do NOT start if provider-neutral alert policies are absent - stop and report blocked.
- **Zero remediation content.** No shell/SQL/`kubectl`/PowerShell code fences, no
  `sudo`/`systemctl`/`kubectl`/`restart`/`rm -f*`/`rollout undo`, no SQL mutations
  (`DELETE FROM`, `UPDATE ... SET`, `DROP TABLE|DATABASE|INDEX`), and no headings named
  `Mitigation`, `Mitigation Steps`, `Resolution`, or `Remediation`. This is enforced by
  `npm run validate:runbook-lint` - treat any violation as a bug in the runbook, not an
  acceptable stopgap.
- Investigation steps must reference concrete, grounded queries, not generic “check dashboards”.
- Every telemetry investigation entry's Why must explain how to interpret likely outcomes,
  not just restate the query.
- Do not invent consumers, effects, or attribute names that are not grounded in
  `operations.yaml` or the recording-rule catalog - use `NEEDS-DATA: <what and why>` instead.
- Dashboard URLs may be included as optional local additions but must not be required by this framework.
- Escalation contacts must use team names, not individual names.
- Sensitive information must use env var placeholders.
- Any existing remediation procedure discovered while validating a runbook must be moved
  out of the runbook body and referenced via `validated_remediation_ref`, never inlined.

## Done Criteria

See `docs/sre-todo.md` for the authoritative done criteria for this task.

Core expectations:
- SLI/SLO specs and alert policies verified present before starting
- Every alert policy has a corresponding runbook file
- Every runbook has all five required sections, in order
- Telemetry investigation entries (3-6) have question/datasource/query/why fields with
  real, grounded attribute and metric names
- Zero remediation content in any runbook, verified via `npm run validate:runbook-lint`
- Escalation paths use team names not individuals
- Incident response summary table in `docs/runbooks/README.md` is current
