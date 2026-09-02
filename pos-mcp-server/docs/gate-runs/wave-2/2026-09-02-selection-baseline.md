# Wave 2 selection baseline — 2026-09-02 — GREEN

Issue: #1601 (W2.3 criterion B, selection verification) · First fully-green live run of
`BaselineCaptureIT` in the programme's history.

## Run

- Tree under test: `db7e866d8` (main; SHA-guarded checkout) — **identical to the deployed alpha
  image** `sha-db7e866`.
- Environment: alpha host, `durion-eval` checkout, alpha Postgres/pgvector (post-V42 registry:
  950 discovered ops, all domains present per the #1632 close-out), ollama `bge-m3`.
- Command: `BaselineCaptureIT` with `-Dmcp.eval.live=true`, in-repo deps rebuilt first.

## Results

| Metric | Observed | Floor | Status |
|---|---|---|---|
| hit@5 | **0.9405** | 0.68 | PASS |
| MRR | **0.8770** | 0.64 | PASS |
| forbidden violations | **0** | 0 | PASS |
| RAG recall@k | 0.951 | 0.76 | PASS |
| fixture split | scored 84 / negative-only 32 / skipped 0 of 116 | skipped must be 0 | PASS |

Both IT methods pass; BUILD SUCCESS; MVN_EXIT=0.

## What changed since the 0.55 / 0.527 measurement (2026-08-31)

The earlier determination (`../2026-08-31-baseline-determination.md`) showed the selector already
achieved 100% of what the corpus allowed — the ceiling was 0.55 because 36 of 80 positives
asserted tools their own actors were not permitted. Between that run and this one:

1. The eval corpus was repaired (actors aligned with real role grants; two former positives are
   now negatives — scored 84, negative-only 32 of 116).
2. V41 applied the #1612 reference-read grant decisions; V42 promoted the E1/E5/E8 analytics
   facades with `*:analytics:view` permissions.
3. The six Wave 2 gate fixtures whose facades now answer them were promoted into the scored
   suite (q01, q07, q08, q09, q15, q17 — PR #1640 content).
4. #1633 repaired the discovery registry (950 ops; all Wave 2 discovery endpoints present with
   permission mappings — see #1632 close-out).

With the ceiling restored to ~1.0, the selector scores 79 of 84. The floors are meaningful for
the first time: a future breach is evidence about the selector, not the corpus.

## What this does and does not verify

**Verified**: tool selection for the facade-reachable Wave 2 gate questions (Q1, Q5, Q7, Q8, Q9,
Q13, Q15, Q17 phrasings among the scored fixtures), permission-negative exclusions, and the
fixture-accounting split, against the live registry and embedding model.

**Not verified — still open on #1601**:
- Q3, Q4, Q12, Q16: discovery-only endpoints. `BaselineCaptureIT` scores `resolveCandidateTools`,
  which filters `source <> 'openapi'` — these need live chat-path runs
  (`nlti.request.telemetry` selected-tool capture).
- §2.1 criteria 1/3/4 (answer correctness, cost budgets, truncation honesty): require the Track B
  seed dataset, which does not exist yet.
- The under-permissioned degradation run.
- Q13/Q5 answer records: invalidated by #1604's aging re-base
  (`../2026-09-01-ar-aging-basis-change.md`) and to be re-derived with the Track B ground truth.
