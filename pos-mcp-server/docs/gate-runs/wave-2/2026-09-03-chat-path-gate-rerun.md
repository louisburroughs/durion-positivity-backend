# Wave 2 chat-path gate re-run — 2026-09-03 — 4/12 correct, 0/12 → tools now execute

Issue: #1601 (W2.3 criterion B and C) · #1653 criteria 2 and 3 · Plan §2.1 / §2.2 / §6
Environment: alpha, image `sha-af7f508` (the #1654 merge), Track B seed (#1647), SSM port-forward,
callers from `durion-positivity-sdk/.env.itest`. Endpoint: `POST /mcp-server/v1/mcp/chat`.

## The defect is fixed

| | 2026-09-02 (`sha-db7e866`) | 2026-09-03 (`sha-af7f508`) |
|---|---|---|
| Tool HTTP calls in the run window | **0** | **8** |
| Turns with no direct answer | 12 of 12 | **2** of 12 |
| Answers containing live data | 0 | 9 |
| Answers correct vs ground truth | 0 | **4** |

The #1653 root cause is closed. `grep -c "MCP tool http"` over the run window returns 8, and the
production stack confirms the whole new chain executes:

```
ToolCallingAdvisor.adviseCall
  → BoundedToolCallingManager.executeToolCalls
    → CallerBoundToolCallback.call
      → ToolInvocationRecorder$RecordingToolCallback.call
        → ReflectiveToolCallback.call
```

Both remaining no-answer turns log `source=BLANK, unexecutedToolCalls=0` — never `THINKING`. The
ladder is firing only on genuinely empty turns, so **#1654 finding 9 did not materialise**: holding
back the `resolveResponse` change was correct, and there is still no evidence for making it.
`CAP_HIT=0` — the tool round-trip cap was never reached.

## Scored against `ground-truth/EXPECTED.md`

| Q | Verdict | Detail |
|---|---|---|
| q01 | **FAIL** | Per-technician values all exact, but ranked Alex Kim ($950) above Nadia Torres ($1,500) for a "most labor revenue" question. Ordering, not data. |
| q03 | **PASS** | Sam 2, Alex 1, Nadia 0 — exact, and correctly drops Nadia's 25-day decoy. |
| q04 | **FAIL** | `searchInvoices` threw; degraded to a screen link (see below). |
| q05 | **PASS** | Exactly the three open WOs, right customers/statuses/dates; drops the C3 decoy **and** 133 co-tenant open WOs. |
| q07 | DEFLECT | States the platform has no single-call per-customer revenue report. |
| q08 | DEFLECT | "No path" deflection. |
| q09 | **UNSCORABLE** | Answer uses calendar-year ("this year", the question's literal wording); EXPECTED uses rolling 12-month. Ground truth and question disagree — reconcile before scoring. |
| q12 | **FAIL** | Unpaid (45 / 22088.29) and 61–90 (4 / 800) exact; ≤30 and 31–60 short by 3 and 1. The model used a rolling window (2026-03-04→09-03) where EXPECTED uses calendar 2026-03-01..08-31. |
| q13 | **PASS** | Exact: total 20588.29, ranks 1–6 at 4500/2500/1200/900/600/300, Pareto-80 = ranks 1–29 at 80.5 %. |
| q15 | **PARTIAL** | Paid totals exact (12000 / 6720 / 2400) and top vendor right, but bill counts 8/7/7 vs 6/6/6 and averages 1825.00/1074.29/400.00 vs 2000.00/1120.00/400.00. |
| q16 | **PASS** | All four bills and the daily cash need exact (800/2000/600/400). |
| q17 | **FAIL** | Answers "none above 10 %". Truth: V1 Evergreen +12.00 % (1000.00 → 1120.00). |

**4 of 12 fully correct** (q03, q05, q13, q16), 1 unscorable, 7 wrong or deflected.

## The three defects this run exposes

**1. Vendor average/count uses a different bill set than the paid amount** (q15, q17 — one cause).
For V1 the answer reports paid 6720.00 over **7** bills averaging 1074.29. 6720/7 = 960, so the
average is not derived from the reported spend: 1074.29 × 7 = 7520.00 = 6720.00 + the 800.00
`TRACKB-BILL-V1-DUE0904` bill, which is due 2026-09-04 and **outside** the paid window. The paid
total correctly excludes it; the count and average do not. This single inconsistency is what makes
q17 answer "none" instead of naming V1 at +12 %.

**2. `searchInvoices` throws, and the cause is not logged** (q04). The turn took 71.1 s and ended in
`IllegalStateException: Tool method failed: searchInvoices`. The new catch in
`SpringAiPosAssistant.callModel` degraded it to a screen link instead of the 500 it would have been
before #1654 — the finding-3 fix working as intended — but `ReflectiveToolCallback` logs no
`Caused by`, so the underlying failure is invisible.

**3. Window semantics are unpinned** (q09, q12). "Last six months" and "this year" are answered as
rolling-from-today; EXPECTED assumes calendar boundaries. Both readings are defensible, which is the
problem: the gate cannot score a question whose window is not specified. Fix the questions or the
ground truth, not the model.

## Criterion status

- **#1653 criterion 2 — MET.** q13 is answered end-to-end from live data, exactly matching ground
  truth, with the tool invocation visible in the logs.
- **#1653 criterion 3 — MET** by this record.
- **§2.1 criterion 1 (answer correctness): 4/12.** Up from 0/12, not yet a pass.
- **Criterion 2 (honest tool trace):** no fabricated parameters observed; q17's error is a wrong
  conclusion from correctly-fetched data, not an invented one.
- **Criterion 3 (bounded cost):** 6.6–13.5 s except q04 at 71.1 s (the failing tool).
- **#1601 criterion B (selection): PASS**, unchanged — `offeredTools=71`.

## Reproducing

```bash
./scripts/alpha-itest-tunnel.sh              # SSM port-forward 18080/18086
python3 gate_run.py /tmp/gaterun2            # reads /tmp/gate_questions.json
```

The twelve utterances are the verbatim Q1/3/4/5/7/8/9/12/13/15/16/17 rows of the original
twenty-question table. Credentials come from `durion-positivity-sdk/.env.itest`, read by the runner
directly; they are never passed through SSM parameters or printed.
