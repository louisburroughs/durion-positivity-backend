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
- Minimum counts (≥100 tool-selection / ≥50 rag-retrieval / ≥30 write-safety): **met** —
  105 / 56 / 34 (seed + `generated.json`). The `minimumFixtureCountsMet` test is **enabled** and green.
  Generated fixtures are grounded in the 16 facade tool names + their V18 gating permissions, the RAG
  doc ids/scopes, and the `KNOWN_ROLES` allowlist; regenerate with `scripts`-style tooling if the tool
  set or permission seeds change.
- Baseline metrics (hit@5 / MRR / recall@k): **pending** live backend — captured by `BaselineCaptureIT`
  against a running model + pgvector, not part of the structural CI test.
