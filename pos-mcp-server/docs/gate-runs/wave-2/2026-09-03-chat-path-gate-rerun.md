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
| q09 | **UNSCORABLE** | Answer uses calendar-year ("this year", the question's literal wording); EXPECTED measures twelve months. Ground truth and question disagree — see the #1671 correction below. |
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

**4. The questions themselves were not versioned** (#1671, recorded 2026-09-03 after this run).
See the correction below; it is a harness defect, not a model or tool defect, and it is why the q09
verdict above cannot be read at face value.

## Correction — the q09 verdict hides two defects (#1671)

The twelve utterances used here were not in git. They were read off an ad-hoc `/tmp/gate_questions.json`
and recovered afterwards from a session transcript, so nothing in the repo recorded what was asked. Two
separate problems are therefore folded into the single q09 UNSCORABLE:

1. **The question asked was not the versioned question.** The live utterance carried "this year"; the
   `q09-top-customers-revenue-balance-days-to-pay` fixture — the only q09 text in the repo at the time,
   and a *tool-selection* fixture, not an answer-gate question — carried no window at all. They cannot
   both be q09, and nothing in this run record said which one was asked.
2. **The versioned text could not be scored either.** Even asked verbatim it specified no window, while
   `EXPECTED.md` Q9 and `q09-top-customers-revenue-balance-days-to-pay.sql` both measure the twelve
   calendar months ending 2026-08-31. The #1661/#1670 window rules cannot resolve that: there is no
   phrase in the question to read a shape from.

Neither was visible without opening the fixture file and comparing it to this run by hand.

**Fixed by #1671.** The questions are versioned in
`pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json`, in bijection with the `## QN`
sections of `EXPECTED.md`; q09 now states its window ("...top 20 customers by revenue in the last twelve
months..."), which resolves to the ground truth's range under the calendar rule. The runner is
`scripts/analytics_gate_run.py`, which reads that file and records its git blob sha in the run record,
so a later reader can recover the exact text behind a score. **Scores in this document predate that
change and are not reproducible from a committed question set.** The next run re-establishes the
baseline against the versioned text; q09's verdict here should be read as "not scorable as recorded",
not as a data or model failure.

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

This run used an ad-hoc runner reading `/tmp/gate_questions.json`, which is the defect #1671 records
above; it is **not** reproducible. Later runs use the committed runner and the versioned questions:

```bash
./scripts/alpha-itest-tunnel.sh              # SSM port-forward 18080/18086
python3 scripts/analytics_gate_run.py --out /tmp/gaterun3 \
    --env-file ../durion-positivity-sdk/.env.itest
```

The twelve are the Q1/3/4/5/7/8/9/12/13/15/16/17 entries of
`pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json`, which records that set and why
the other eight are excluded. Credentials come from `durion-positivity-sdk/.env.itest`, read by the
runner directly; they are never passed through SSM parameters or printed.
