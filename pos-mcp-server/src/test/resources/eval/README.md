# Eval fixtures & harness (Gate 0)

Source of truth for formats: `../../../../docs/phase0-fixtures-and-telemetry.md`.

## Layout
```
eval/
  schema/        JSON Schema for each suite (authoring reference)
  tool-selection/*.json   hit@5 / MRR fixtures
  rag-retrieval/*.json    recall@k fixtures
  write-safety/*.json     write-gate invariant fixtures
  tool-response/*.json    coverage fixtures: realistic-response vs no-tool-available (#1164)
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

## Tool-response coverage suite (#1164)

`tool-response/seed.json` holds ~100 questions authored **from the roles** (advisor, tech, parts,
cashier, manager, warehouse, accountant, HR, owner, fleet, executive, marketing, compliance, IT) —
deliberately **not** derived from the 16-facade tool surface, so requests that fall through it are
visible. Each fixture is labeled `expected.outcome ∈ {realistic-response, no-tool-available}` plus a
`gap_hypothesis ∈ {new-tool, description-gap, n/a}` author prior. Personas without a `KNOWN_ROLES`
entry are mapped to the closest known role and carried as a persona tag.

Scoring lives in the `tool_response_coverage` block of `scripts/eval_live.py` (runtime-gated: needs
the alpha DB + Ollama; **not** a hard gate, never contributes to threshold failures). Classification
is cheap by design — gated ANN top-K with a cosine floor (`EVAL_TOOL_RESPONSE_MIN_SIM`, default 0.5):
empty candidate set or a sub-floor top hit ⇒ `no-tool-available`. No grounded judge; this suite is
deliberately lighter than rag-retrieval (#783/#1124), measuring coverage rather than correctness.
It emits `realistic_rate`, `no_tool_rate`, `outcome_confusion`, and `gap_candidates` grouped by
`gap_hypothesis` — the triage worklist: `new-tool` entries feed the tool roadmap; `description-gap`
entries feed a `*FacadeTool` `@Tool` description-tightening pass, then a re-run should move the
fixture to `realistic-response`. First live alpha run + gap triage write-up: pending (#1164 AC3-5).
