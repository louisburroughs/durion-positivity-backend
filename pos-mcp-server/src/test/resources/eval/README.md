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
  110 / 60 / 38 (seed + `generated.json` + `analytics-gate.json`). The `minimumFixtureCountsMet` test is
  **enabled** and green. `tool-selection-pending/` is deliberately excluded from the count.
  Generated fixtures are grounded in the 16 facade tool names + their V18 gating permissions, the RAG
  doc ids/scopes, and the `KNOWN_ROLES` allowlist; regenerate with `scripts`-style tooling if the tool
  set or permission seeds change.
- Baseline metrics (hit@5 / MRR / recall@k): captured by `BaselineCaptureIT` against a running model +
  pgvector, not part of the structural CI test. **Currently RED** — see "Live baseline status" below.

## Corpus size vs. scored size — why 110 and 80 are both correct (#1606 finding 3)

The two gates count different things, and both numbers are right:

| Gate | Suite | Counts | tool-selection | rag-retrieval |
|---|---|---|---|---|
| `EvalFixtureValidationTest.minimumFixtureCountsMet` | structural | **corpus size** — every fixture file entry | 110 | 60 |
| `BaselineCaptureIT` hit@5 / MRR / recall@k | metric | **fixtures with a positive expectation** | 80 | 51 |

The difference is the deliberate **negative set**: 30 tool-selection fixtures and 9 rag-retrieval
fixtures that carry no positive expectation by design.

- tool-selection: 30 permission-negative fixtures declare `expected.none: true` with
  `expected.tool_ids: []` and a non-empty `expected.forbidden_tool_ids` (tagged `permission-negative`).
  They assert that a tool the actor lacks the permission for is *never* selected.
- rag-retrieval: 9 visibility-negative fixtures pair `expected.doc_ids: []` with a non-empty
  `expected.forbidden_doc_ids` (tagged `visibility-negative`). Same idea for document visibility.

**Nothing in the negative set is unchecked.** `BaselineCaptureIT` runs the live selector/retriever for
every fixture and evaluates the forbidden assertions for negatives exactly as it does for positives;
only the ranking metrics (which need a primary expected id to rank) skip them. Hit@5, MRR and recall@k
are genuinely computed over 80 and 51 fixtures respectively — not over 110 and 60.

To stop that difference from hiding a real defect, `BaselineCaptureIT` now reports an explicit
three-way split and hard-fails on the third bucket:

```
MCP baseline tool-selection hit@5=... mrr=... scored=80 negativeOnly=30 skipped=0 total=110 -> ...
MCP baseline rag recall@k=...          scored=51 negativeOnly=9  skipped=0 total=60  -> ...
```

- **scored** — positive expectation, contributed to the metric.
- **negativeOnly** — *self-declared* negative (the explicit flags above). Forbidden assertions ran.
- **skipped** — anything else: a missing `expected` block, a non-array `tool_ids`/`doc_ids`, a blank
  `fixture_id`/`utterance`/`query`, a missing `actor`, or an empty expectation that forbids nothing and
  therefore asserts nothing. **Asserted to be zero**, with each offender named as
  `file[fixture_id]: reason`.

The classification is driven by an explicit property of the fixture, never by a fallback `else`: a
fixture that is malformed in a way that merely *happens* to leave `tool_ids` empty lands in **skipped**,
not in **negativeOnly**. The three counts are also asserted to sum to the total loaded, so the split
cannot drift away from the corpus. The same breakdown is written to
`target/eval/baseline-tool-selection.json` and `target/eval/baseline-rag-recall.json`
(`fixtures_total`, `fixtures_scored`, `fixtures_negative_only`, `fixtures_skipped`,
`skipped_fixtures`), so a recorded run is self-describing.

## Live baseline status — RED (#1606)

The first live execution of `BaselineCaptureIT` (alpha, 2026-08-31) failed on two counts:

- **Permission-negative violations.** `ts-customerfacadetool-neg-role-technician` and
  `ts-customerfacadetool-neg-role-dispatcher` selected `CustomerFacadeTool` despite the actor holding
  only `workorder:workorder:view`. Root cause is the `V37__facade_permission_rederivation.sql` union
  combined with OR-semantics gating (#1606 finding 1). (`q13-ar-pareto` also appeared in that run and is
  being fixed separately as an incoherent fixture assertion.)
- **Both quality floors missed:** hit@5 **0.60** against a 0.68 floor, MRR **0.569** against a 0.64
  floor (#1606 finding 2). The floors were derived from an `eval_live.py` observation (hit@5 0.76 /
  MRR 0.7222), never from `BaselineCaptureIT` itself, so whether the selector degraded or the two
  harnesses were never measuring the same thing is still undetermined.

A permission-gate fix is in flight on this branch. Because it changes which tools a given actor can be
offered, it is expected to clear the two violations **and to move hit@5 / MRR** — in which direction and
by how much is not predicted here. Both metrics must be **re-measured live** and this section updated
with the observed numbers before the gate can be called green.

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

  Note what each scorer actually exercises. Only `BaselineCaptureIT` calls
  `ToolRegistryService.resolveCandidateTools`, so only it exercises the admin fast path —
  `eval_live.py` reimplements selection as raw pgvector SQL and never enters that code, meaning the
  `q13-ar-pareto` / `q13-admin-user-account-active` pair pins the #1588 fast-path fix **through
  `BaselineCaptureIT` only**. That IT is gated on `-Dmcp.eval.live=true` and needs a running model
  and pgvector, so this lock does not run in ordinary CI; it must be exercised deliberately:

  ```
  ./mvnw -pl pos-mcp-server -Dmcp.eval.live=true -Dit.test=BaselineCaptureIT verify
  ```

  The lock is a POSITIVE assertion, and deliberately so. `q13-ar-pareto` requires
  `AccountingFacadeTool` in the top-5, which the pre-#1588 selector cannot satisfy: the fast path
  returns exactly one tool and it is not that one. It carries no `forbidden_tool_ids`. The first
  live run (2026-08-31) established why — with the actor permitted to see `AdminFacadeTool`, that
  tool reaches the top-5 by ordinary semantic rank, which is correct behaviour, not the defect.
  An earlier version of this fixture granted `security:user:view` *and* forbade the tool that grant
  admits, which was incoherent; the grant has been removed.
  `q14-ar-balance-and-dso-by-month` carries the exclusion assertion instead, where it holds by
  construction: that actor holds no admin permission, so `AdminFacadeTool` cannot be returned at all.
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
