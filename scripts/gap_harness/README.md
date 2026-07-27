# RAG corpus gap-discovery harness (#1125)

An agent-driven harness that asks realistic questions through the MCP chat API, grades the answers
against ground truth, classifies every failure so it routes to the right fix, and emits a
human-actionable gap report to grow the RAG corpus systematically. The same harness produces the
measured evidence for [#1124](https://github.com/louisburroughs/durion-positivity-backend/issues/1124)'s
hybrid-lexical flip-threshold.

- **Design:** [`pos-mcp-server/docs/rag-corpus-gap-harness-design.md`](../../pos-mcp-server/docs/rag-corpus-gap-harness-design.md)
- **CLI:** [`scripts/rag_gap_harness.py`](../rag_gap_harness.py) · **runner:** [`scripts/run-gap-harness.sh`](../run-gap-harness.sh)
- **Builds on:** [`scripts/eval_live.py`](../eval_live.py) (retrieval reproduction + RRF) and
  [`scripts/rag_seed.py`](../rag_seed.py) (the 17-doc corpus manifest).

## The loop

```
questions → ask via API (as an actor) → capture(answer, retrieved_context, permissions)
          → grade (source-grounded judge) → classify (four-way taxonomy) → gap report
          ↳ retrieval-miss bucket → dense-vs-hybrid recovery → flip-threshold evidence
```

Capturing the **retrieved context** is mandatory — it is what distinguishes a missing doc from a doc
that exists but was not retrieved. The chat API returns only the answer, so the harness reproduces
the retrieval that fed the prompt against Postgres/pgvector, exactly as `eval_live.py` does.

## Layout

| Module | Responsibility |
|---|---|
| `model.py` | JSON-serializable pipeline records (Question → Capture → Grade → Classification → Result) |
| `corpus.py` | Corpus inventory (manifest, kept in sync with `rag_seed.py`) + `_Verified:` fact extraction + a content coverage lookup |
| `refusal.py` | Deterministic refusal pre-filter (no judge call spent) |
| `judge.py` | Source-grounded judge prompt + defensive JSON parsing + calibration metrics |
| `taxonomy.py` | Four-way failure classifier |
| `fusion.py` | RRF fusion + dense-vs-hybrid recovery + documented flip criterion |
| `report.py` | Gap report / summary / judge-accuracy / flip-threshold rendering |
| `questions.py` | Question sourcing (synthetic + eval fixtures + real-usage rows), de-dup |
| `retrieval.py` | Live DB retrieval reproduction (the only module needing a live DB) |
| `api.py` | Chat API client + judge LLM clients (Ollama / OpenAI-compatible) + token providers |
| `harness.py` | I/O-agnostic pipeline orchestrator |

All pure decision logic is unit-tested with no live stack:
[`scripts/tests/test_gap_harness.py`](../tests/test_gap_harness.py).

## Grading — the source-grounded judge (§4)

Refusals are a trivial deterministic pre-filter. **Misleading** answers — fluent, confident,
plausible — are the real difficulty and can only be caught against a **source of truth**. The judge
is handed the SME `expected_facts` (preferred) or the doc's `_Verified: …_` module facts and told to
grade against those only, never its own priors; a `misleading` verdict must **cite the ground-truth
fact it contradicts**. Calibrate the judge against the human-labeled set and read
`judge-accuracy.md` before trusting the counts — `misleading` detection is **partial**, and the
harness never implies "0 misleading" means "all correct."

## Four-way failure taxonomy (§5)

| # | Cause | Signal | Response |
|---|---|---|---|
| 1 | `corpus_gap` | no doc on the topic exists | write a doc (the #1124 goal) |
| 2 | `retrieval_miss` | relevant doc exists but wasn't retrieved | retrieval problem → feeds the flip-threshold |
| 3 | `generation` | context retrieved but answer still wrong | prompt/model issue |
| 4 | `permission_gating` | doc exists but actor lacks `required_permissions` | correct behaviour, not a failure |

## Guardrail — propose gaps, never author docs (§6)

On a corpus gap the harness emits a **gap-report entry** (topic, failing queries, candidate source,
skeleton outline). A human authors and **source-verifies** the doc (`_Verified: …_`). A model
authoring docs to answer its own questions injects plausible-but-wrong ground truth. Auto-drafting a
skeleton is fine; auto-ingesting is not.

## Flip-threshold (§8, #1124)

For every retrieval-miss, retrieval is run through both dense and dense+lexical(RRF). Flip criterion:
*hybrid recovers ≥ X% of the retrieval-miss failures dense does not, with `rag_retrieval.recall_at_k`
at/above its floor.* Output: `flip-threshold.md` with the recommendation and the evidence behind it.

## Running

```bash
pip install --user pg8000            # retrieval reproduction only

# Live: capture + grade + classify + report + flip evidence
POS_MCP_TOKEN_ROLE_ADMIN=... POS_MCP_TOKEN_ROLE_USER=... \
  scripts/run-gap-harness.sh --judge ollama --judge-model llama3.1 --recall-at-k 0.85

# Iterate the judge / taxonomy offline on a captured dump (no stack needed):
python3 scripts/rag_gap_harness.py replay --results pos-mcp-server/target/gap-harness/results.json --judge ollama

# Judge calibration gate:
python3 scripts/rag_gap_harness.py calibrate --judge ollama

# Phase-5 round-trip: once a gap doc is authored + ingested, guard it in eval_live.py:
python3 scripts/rag_gap_harness.py emit-fixture --doc-id order.returns-refunds \
  --query "refund policy on a paid invoice" --scope order --perm order:order:view \
  --out pos-mcp-server/src/test/resources/eval/rag-retrieval/gap-order-returns.json
```

Outputs land under `--out` (default `pos-mcp-server/target/gap-harness/`): `results.json` (raw dump,
replay input), `summary.json`, `gap-report.{json,md}`, `flip-threshold.{json,md}`,
`recovery-records.json`, and `judge-accuracy.{json,md}` (from `calibrate`).

## Data

- Synthetic breadth questions: `pos-mcp-server/src/test/resources/eval/gap-harness/questions.json`
  (SME `expected_facts` verbatim from the corpus `_Verified:` citations; `exact-code`, `gated`, and
  `known-gap` tags).
- Judge calibration set: `pos-mcp-server/src/test/resources/eval/gap-harness/calibration.json`.
