# RAG Corpus Gap-Discovery Harness (design)

> **Status:** DESIGN. Sub-effort of #1124 (grow the RAG corpus systematically; define the
> threshold to enable hybrid lexical retrieval). Depends on the retrieval-quality tooling from
> #783 (`scripts/eval_live.py`) and the hybrid dense+lexical retrieval from #1123.

## 1. Problem

The RAG corpus is small (~17 docs) and hand-curated. Two open needs from #1124:

1. **Grow it systematically** — discover what the corpus is *missing* from how the assistant is
   actually used, rather than authoring fixtures in a vacuum.
2. **Decide when hybrid lexical retrieval earns its keep** — #784 shipped dense+lexical+RRF behind
   `mcp.rag.hybrid.lexical-enabled` (default off) because on the current corpus dense already
   returns every target at rank 1, so lexical adds no measured recall. We need data-driven
   criteria for flipping the flag.

This harness addresses both by running realistic questions end-to-end, grading the answers against
ground truth, and classifying every failure so it routes to the right fix.

## 2. Non-goals

- Auto-authoring RAG documents (see §6 — the harness proposes gaps; humans write and source-verify
  the docs).
- Replacing `eval_live.py`'s retrieval-quality gate (this is complementary: `eval_live.py` measures
  *retrieval* against known-expected docs; this measures *end-to-end answer quality* and discovers
  *unknown* gaps, then feeds new fixtures back into `eval_live.py`).
- UI regression testing. The eval loop drives the API; Playwright is reserved for genuine UI checks
  (§7), not corpus coverage.

## 3. The loop

```
question set ──► ask via API ──► capture(answer, retrieved_context, permissions) ──► grade ──► classify ──► report
     ▲                                                                                                        │
     └──────────────────────── seed next round from real usage + gaps found ◄─────────────────────────────────┘
```

1. **Ask** — POST each question through the real NLTI/chat endpoint as a specific actor
   (role + permission_codes), so retrieval scope + permission gating match production.
2. **Capture** — the answer text, the **retrieved context** (doc_ids + scores that reached the
   prompt), and the actor's permissions. Capturing retrieved context is mandatory; it is what lets
   §5 tell "doc missing" from "doc present but not retrieved."
3. **Grade** — a source-grounded judge (§4) labels the answer `correct | refused | misleading`.
4. **Classify** — map each non-correct answer to one of four causes (§5).
5. **Report** — emit a gap report + machine-readable results; never mutate the corpus automatically.

## 4. Grading (the hard part) — a source-grounded judge

Detecting a **refusal** is a trivial string/intent match ("I can't answer", "I don't have
information…"). Detecting a **misleading** answer is the entire difficulty: it is fluent, confident,
and plausible — indistinguishable from a correct answer without a source of truth. Therefore:

- **The judge MUST be grounded in an authoritative reference**, not asked to grade from its own
  priors (an ungrounded LLM judge shares the answer-giver's blind spot and is near-useless for the
  misleading case). Grounding sources, in preference order:
  1. A curated **expected-answer / expected-fact** per question (SME-written), or
  2. The **module source of truth** the answer is about — the existing glossary docs already model
     this: every entry ends with `_Verified: pos-catalog ProductEntity (sku unique) …_`. The judge
     checks the answer against those verified facts.
- **Output schema:** `{verdict: correct|refused|misleading, rationale, cited_ground_truth}`. A
  `misleading` verdict must cite the specific ground-truth fact it contradicts, so it is auditable
  and not a vibe.
- **Calibration:** hold out a human-labeled set; measure judge agreement (precision/recall on
  `misleading`) before trusting it. Report judge accuracy alongside harness results — a harness is
  only as trustworthy as its grader.
- Refusal detection stays a cheap deterministic pre-filter; the grounded judge runs on the rest.

Honesty clause: `misleading` detection will be **partial**. That is acceptable if stated — the
harness must not imply "0 misleading" means "all answers correct."

## 5. Failure taxonomy (route each failure to the right fix)

A failed answer has one of four causes, each with a different response. Misclassifying wastes effort
(e.g. writing a new doc for content that already exists but wasn't retrieved):

| # | Cause | Signal (needs retrieved-context capture) | Response |
|---|---|---|---|
| 1 | **Corpus gap** | No doc on the topic exists in the corpus | Write a new doc *(the #1124 goal)* |
| 2 | **Retrieval miss** | A relevant doc exists but was **not** in the retrieved context | Retrieval problem — feeds the hybrid-flag decision (§8), not a content fix |
| 3 | **Generation** | Relevant context **was** retrieved but the answer is still wrong/refused | Prompt / model issue |
| 4 | **Permission gating** | A relevant doc exists but the actor lacks its `required_permissions` | Correct behavior — not a failure |

Distinguishing #1 from #2 requires the retrieved-context dump (§3.2) plus a corpus lookup (does any
doc — retrieved or not — cover the topic?). This taxonomy is the core of the harness; without it the
corpus bloats with duplicates and retrieval bugs get mis-filed as content gaps.

## 6. Corpus suggestions — propose, never author

When classification yields a **corpus gap (taxonomy #1)**, the harness emits a **gap report entry**, not a
document:

```
{ topic, failing_queries:[…], suggested_scope, candidate_sources:["pos-catalog/…"], draft_outline? }
```

A human authors the doc and **source-verifies** it (the `_Verified: …_` convention). Rationale: RAG
docs are ground truth; a model that authors a doc to answer its own question injects
plausible-but-wrong content that survives casual review and poisons retrieval. Auto-drafting a
*skeleton/outline* for a human to fill is fine; auto-ingesting is not.

## 7. Question sourcing & interface

- **Seed from real usage first.** `mcp_tool_invocation_log` and the NLTI telemetry
  (`NltiTelemetryEmitter`) hold real queries — a better gap signal than model-invented questions,
  and grounded in what people actually ask. Blend with a synthetic set for breadth (per-scope
  coverage, exact-code/identifier queries, negative/permission cases).
- **API over Playwright.** Drive the chat/NLTI API for the eval loop — faster, less flaky, and it
  still exercises retrieval + generation end-to-end. Reserve Playwright for actual UI-regression
  needs (rendering, streaming UX), not corpus coverage.

## 8. Tie-in: this harness also produces the hybrid-flag decision

The **retrieval-miss bucket (§5.2)** is the direct signal for #1124's flip-threshold question. For
every failing query, run its retrieval through **both** dense and dense+lexical(RRF) — the logic
already exists in `eval_live.py`'s `rag_lexical_hybrid_784` block — and record which fusion recovers
the relevant doc. Aggregate:

- If a meaningful class of failures (e.g. exact codes / rare identifiers) is recovered by **lexical
  but not dense**, that is the measured evidence to flip `lexical-enabled=true`, with a hit-rate,
  not a guess.
- Define the flip criterion concretely, e.g. *"lexical recovers ≥ X% of retrieval-miss failures that
  dense does not, with no regression to `rag_retrieval.recall_at_k` ≥ floor."*

Instrumenting dense-vs-hybrid recovery from the start makes one harness deliver both #1124 goals.

## 9. Outputs

1. **Gap report** — human-actionable list of proposed docs (§6).
2. **Fixtures** — confirmed gaps, once docs are authored, become new `eval_live.py` fixtures
   (`rag-retrieval` and/or `rag-lexical`), so the gate guards them going forward.
3. **Flip-threshold evidence** — dense-vs-hybrid recovery aggregates (§8) → the flag decision.
4. **Judge-accuracy report** — so results are trusted proportionally to grader quality (§4).

## 10. Phasing

1. **Harness skeleton** — question runner over the API, retrieved-context capture, deterministic
   refusal detection, raw result dump. (No judge yet — just refusals + context.)
2. **Grounded judge** — expected-fact/source-grounded grading + calibration set + judge-accuracy
   report.
3. **Taxonomy + gap report** — the four-way classifier and human-facing gap output.
4. **Flip-threshold instrumentation** — dense-vs-hybrid recovery per retrieval-miss; aggregate.
5. **Feedback loop** — authored docs → new fixtures → `eval_live.py`; re-baseline #783 floors.

Each phase is independently useful; phase 1 alone surfaces refusals against real usage.

## 11. Risks & open questions

- **Judge trust** — an unreliable judge makes the whole harness untrustworthy; §4 calibration is a
  gate, not a nicety.
- **Non-determinism** — LLM answers vary run to run; fix temperature/seed where possible and treat
  a single run as a sample, not a verdict.
- **Cost** — every question is ≥1 generation + ≥1 judge call; scope the question set and cache.
- **Corpus re-embedding drift** — the gated `rag_retrieval.recall_at_k` already drifted
  0.9574 → 0.8511 on a redeploy (now near the 0.85 floor). This harness's re-baselining (phase 5)
  should re-calibrate the #783 floors so the nightly gate isn't one wobble from red.
- **Open:** which endpoint(s) to drive (streaming vs non-streaming NLTI), and whether the judge runs
  in-harness (Python) or as a dedicated service.

## 12. References

- Parent: #1124 (corpus growth + flip threshold)
- Hybrid retrieval: #1123; broad Gate-5 design `pos-mcp-server/docs/gate5-rag-hybrid-design.md` and
  the #784 lexical-hybrid implementation design `pos-mcp-server/docs/rag-hybrid-lexical-784-design.md`
- Retrieval-quality gate: #783 (`scripts/eval_live.py`, `rag_lexical_hybrid_784`,
  `scripts/eval-cron.sh`)
- Corpus: `pos-mcp-server/src/main/resources/rag/*.md`; fixtures
  `pos-mcp-server/src/test/resources/eval/{rag-retrieval,rag-lexical}/`
