# Eval fixtures & harness (Gate 0)

Source of truth for formats: `../../../../docs/phase0-fixtures-and-telemetry.md`.

## Layout
```
eval/
  schema/        JSON Schema for each suite (authoring reference)
  tool-selection/*.json   hit@5 / MRR fixtures
  rag-retrieval/*.json    recall@k fixtures
  write-safety/*.json     write-gate invariant fixtures
  baseline.json           metric baseline snapshot
```

## Harness halves
1. **Structural** — `EvalFixtureValidationTest` (CI-safe, no model backend). Validates shape, ids,
   enums, role names, uniqueness. Runs on every build.
2. **Metric** — hit@5 / MRR / recall@k + write-safety assertions. Drives the live tool-selection
   and retrieval paths, so it requires a running model backend (Ollama/alpha). Runtime-gated; not
   part of the structural CI test. Results land in `baseline.json`.

## Gate 0 exit status
- Structural harness: **active**.
- Minimum counts (≥100 tool-selection / ≥50 rag-retrieval / ≥30 write-safety): **NOT met** —
  currently seed-only (4/4/4). The `minimumFixtureCountsMet` test is `@Disabled` until authored;
  it must be enabled and green before Gate 0 is signed Pass.
- Baseline metrics: **pending** live backend.
