# RAG corpus growth + hybrid-lexical flip threshold (#1124)

Companion to `rag-hybrid-lexical-784-design.md` (the hybrid retrieval implementation, #1123) and
`rag-corpus-gap-harness-design.md` (the gap-discovery harness, #1125). This doc records two #1124
deliverables: the **corpus-growth plan** and the **data-driven criterion** for flipping
`mcp.rag.hybrid.lexical-enabled` from its default `false` to `true`.

## Why this exists

Hybrid dense + lexical (Postgres FTS) retrieval with RRF fusion landed behind
`mcp.rag.hybrid.lexical-enabled=false` (#1123). Live validation showed the lexical path is correct
end-to-end but **hybrid == dense on recall** on the small (~17-doc) corpus: dense already returns the
target at rank 1, so lexical has no headroom. Enabling it now would add an FTS query per retrieval for
no measured benefit. Two things have to happen first: grow the corpus until dense starts missing
exact-token queries, and define — in advance — the numeric bar that flips the flag.

## Corpus-growth plan

Docs live in `pos-mcp-server/src/main/resources/rag/*.md` and are declared in **three** manifests that
must stay in lockstep (a unit test enforces the two Python copies):

- `application.yml` + `application-alpha.yml` — `mcp.rag.preload.docs` (production ingest).
- `scripts/rag_seed.py` `MANIFEST` — the Python re-embed path for alpha eval.
- `scripts/gap_harness/corpus.py` `MANIFEST` — the offline corpus index for the harness.

Every doc carries `rag_scope` + `required_permissions` frontmatter and source-verified `_Verified: …_`
citations (the judge's ground truth). **Authoring rule (guardrail, #1125):** a doc may only be written
from real module source — every `_Verified:` line must cite a class/field/method that exists. A topic
with no implementation (e.g. core charges) stays a *gap*; we do not invent content to fill it.

### Target scopes and doc-count target

Current coverage skews to master/glossary + one doc per major scope. Under-covered scopes to grow
(depth + noise), each doc source-verified against the owning `pos-*` module:

| Scope | Owning module(s) | Candidate docs |
|---|---|---|
| order | pos-order | returns/refunds ✅ (this change), cart/checkout lifecycle, price-override approval |
| pricing | pos-price | promotions/eligibility, restriction rules, normalization |
| inventory | pos-inventory | purchase-order lifecycle, receiving/exceptions, transfers/adjustments |
| accounting | pos-accounting | journal-entry posting/reversal, financial reporting |
| customer | pos-customer | party/account model, vehicle fitment |
| workorder | pos-workorder | estimate→workorder promotion, approval config |

**Doc-count target: reach ~40–50 docs** (from 18 after this change), enough that dense retrieval starts
missing exact-token / rare-identifier queries and lexical has measurable headroom. Track corpus size per
increment; grow `rag-retrieval` (dense) and `rag-lexical` fixtures proportionally.

### Initial increment (this change)

`order.returns-refunds` (`returns-refunds-rag.md`) — source-verified against `pos-order`
`ReturnOrderServiceImpl` / `ReturnOrderController` / `ReturnOrderStatus` / `RefundMethod` /
`OrderPermissions`. Closes the planted `gap-returns-refunds` known-gap and demonstrates the phase-5
round-trip (gap → authored+verified doc → new fixtures). Corpus: 17 → 18 docs. Core charges remain an
intentional, documented gap (`gap-core-charge-KNOWNGAP`, no module source exists).

## The flip criterion (implemented in `scripts/gap_harness/fusion.py::flip_decision`)

> **Flip `mcp.rag.hybrid.lexical-enabled=true` when hybrid (dense+lexical RRF) recovers ≥ 30% of the
> retrieval-miss failures that dense alone does not, AND the gated `rag_retrieval.recall_at_k` is known
> and ≥ 0.85 (its #783 floor).**

Details that make it honest:

- Recovery rate is measured over **dense-missed misses only** — the misses lexical actually has a chance
  to help — not diluted by misses dense already caught.
- A **missing `recall_at_k` does not satisfy** the recall gate: without the value we cannot confirm the
  flip is not masking a recall regression, so the conservative outcome is HOLD. Always pass
  `--recall-at-k` from the same alpha run.
- Defaults (`fusion.py`): `min_recovery_rate=0.30`, `recall_floor=0.85`, `rrf_k=60` (matches
  `HybridRetrievalProperties.rrfK` and `eval_live.py`). Tunable at call time as evidence accrues.

The harness emits the decision to `flip-threshold.{json,md}` on every `run`/`replay`.

## Live runbook (must run against the alpha stack — not reproducible offline)

The measured numbers come from the live stack; they are **not** fabricated in code review. On a host with
line-of-sight to alpha (see `scripts/run-live-eval.sh`):

1. Re-embed the grown corpus: `ENV_FILE=/opt/durion/alpha/.env python3 scripts/rag_seed.py`.
2. Dense recall baseline + hybrid comparison: `scripts/eval_live.py` (the `rag_retrieval` and
   `rag_lexical_hybrid_784` blocks). Record `rag_retrieval.recall_at_k`.
3. Gap-harness recovery evidence:
   `scripts/run-gap-harness.sh --judge ollama --judge-model llama3.1:8b --judge-timeout 300 --recall-at-k <step-2 value>`
   (raise `--judge-timeout` on CPU-only judges, #1129). Read `flip-threshold.md` +
   `summary.json` (check `ungraded_fraction` is low, #1130).
4. Decision: if `recommend_flip` is true with supporting numbers, flip the flag and **re-baseline the
   #783 floors** (the corpus grew, so recall@k must be re-measured); otherwise record the measured
   rationale and keep the flag dormant.

## Re-baseline note (#783 floors)

The gated `rag_retrieval.recall_at_k` drifted 0.9574 → 0.8511 on a prior alpha redeploy (re-embedding
wobble), leaving ~0.001 of headroom over the 0.85 floor. Growing the corpus re-embeds everything, so this
effort is the natural point to re-measure and re-set the #783 nightly-gate floors off the new corpus, so
the gate is not one embedding wobble from red.
