# Eval fixtures & harness (Gate 0)

Source of truth for formats: `../../../../docs/phase0-fixtures-and-telemetry.md`.

## Layout
```
eval/
  schema/        JSON Schema for each suite (authoring reference)
  tool-selection/*.json   hit@5 / MRR fixtures (scored)
  tool-selection-pending/*.json  same schema, NOT scored — questions whose backing endpoint is unbuilt
  rag-retrieval/*.json    recall@k fixtures
  rag-lexical/*.json      dense-vs-hybrid lexical fixtures (#784/#1178) — separate from the dense gate
  write-safety/*.json     write-gate invariant fixtures
  tool-response/*.json    coverage fixtures: realistic-response vs no-tool-available (#1164)
  gap-harness/            synthetic question set + calibration for scripts/gap_harness (#1125)
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
  110 / 56 / 34 (seed + `generated.json` + `analytics-gate.json`). The `minimumFixtureCountsMet` test is
  **enabled** and green. `tool-selection-pending/` is deliberately excluded from the count.
  Generated fixtures are grounded in the 16 facade tool names + their V18 gating permissions, the RAG
  doc ids/scopes, and the `KNOWN_ROLES` allowlist; regenerate with `scripts`-style tooling if the tool
  set or permission seeds change.
- Baseline metrics (hit@5 / MRR / recall@k): **pending** live backend — captured by `BaselineCaptureIT`
  against a running model + pgvector, not part of the structural CI test.

## Lexical regression suite (#784 / #1178)

`rag-lexical/` is scored by the `rag_lexical_hybrid_784` block of `scripts/eval_live.py` under
dense-only vs dense+lexical (RRF), mirroring the production hybrid path (`LexicalDocumentRetriever`
with the #1170 OR-semantics tsquery and the #1169 master-searches-all-scopes rule; dense pass at the
primary 0.6 floor). Three fixture classes:

- **bare-token** (`glossary-identifiers.json`, `returns-refunds.json`, `codes-catalogs.json`) —
  exact enum constants, `domain:resource:action` permission codes, and event ids with no semantic
  hook, per the per-doc fixture spec in `../../../../docs/rag-corpus-growth-plan-1124.md`.
- **dense-miss NL questions** (`dense-miss-questions.json`) — natural-language identifier questions;
  the VIN and invoice-number entries are live-verified dense misses (#1170) recovered only by the
  lexical path. These give the suite a non-zero `dense_misses` denominator.
- Fixtures where dense alone misses feed the **regression gate**: hybrid must recover at least
  `EVAL_MIN_LEXICAL_RECOVERY` (default 0.5) of them or the eval exits non-zero. Breaking the lexical
  path (tsquery construction, `content_tsv` loss on re-embed, RRF wiring) turns the suite red;
  a corpus where dense recalls everything leaves the gate vacuously green. The gated
  `rag_retrieval.recall_at_k` floor is untouched by all of this.

After changing these fixtures, verify on alpha per
`../../../../docs/rag-corpus-growth-and-flip-threshold-1124.md`: re-run `eval_live.py`, confirm
`rag_lexical_hybrid_784.dense_misses > 0` and `hybrid_recall_at_k > dense_recall_at_k`, and update
each fixture's `dense-miss-candidate` tag to `dense-miss` (or adjust the query) based on the
observed per-fixture detail.

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

## Analytics gate suite (`docs/analytics-capability-plan.md` §6)

The twenty business questions in plan §6 are the acceptance gates for the three analytics waves.
Each question has a tool-selection fixture so a selection regression is caught in CI, with no
database and no model-quality judgement.

- `tool-selection/analytics-gate.json` — the questions answerable on today's tool surface: Q13
  (Wave 1 full pass, `AccountingFacadeTool.getAgedReceivables`) plus the aging halves of Q5, Q10
  and Q14, and the `q13-admin-user-account-active` counterpart. **Scored** by `BaselineCaptureIT`
  and `scripts/eval_live.py`.
- `tool-selection-pending/analytics-gate-pending.json` — the other sixteen questions (Wave 2/3).
  Same schema (`schema/tool-selection.schema.json`), structurally validated by
  `EvalFixtureValidationTest.toolSelectionPendingFixturesValid`, but **not scored**: both scorers
  discover suites by directory glob, so an unbuilt endpoint would otherwise show up as a hit@5
  regression. Every fixture carries the `pending` tag plus a `waveN` tag and names its blocking
  plan item (E1–E13 / W3.2 composition) in `notes`. Promotion = move the fixture into
  `analytics-gate.json` in the PR that ships its endpoint, and drop the `pending` tag.

**Admin fast-path regression (#1588).** A live alpha smoke of Q13 selected `AdminFacadeTool`
alone: `ToolRegistryService`'s admin fast path matches the bare keyword `accounts`
(`ADMIN_QUERY_KEYWORDS`) and returns `AdminFacadeTool` as the sole candidate, suppressing every
other tool. `q13-ar-pareto` (expects `AccountingFacadeTool`, forbids `AdminFacadeTool`) and
`q13-admin-user-account-active` (expects `AdminFacadeTool`) pin both sides of that fix. The Q13
actor deliberately holds `security:user:view` — without it `AdminFacadeTool` is gated out and the
fast path cannot fire, so the fixture would not reproduce the incident. These fixtures fail against
a selector that still carries the un-narrowed keyword list; that is the point.

Ground-truth SQL and the fixture dataset for the *answer* half of the gate live under
`analytics-gate/` (see its README) — plan §7 treats a fixture change without a matching
ground-truth change as a review blocker.
