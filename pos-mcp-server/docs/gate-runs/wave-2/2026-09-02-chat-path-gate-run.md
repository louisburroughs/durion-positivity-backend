# Wave 2 chat-path gate run — 2026-09-02 — 0/12 answered

Issue: #1601 (W2.3 criterion B and C) · Plan §2.1 / §2.2 / §6
Environment: alpha, image `sha-db7e866`, Track B seed applied (#1647), SSM port-forward from the
operator machine (`alpha-itest-tunnel.sh`), callers from `durion-positivity-sdk/.env.itest`.
Endpoint: `POST /mcp-server/v1/mcp/chat` through the gateway.

## Result

**0 of 12 gate questions answered.** Every request returned HTTP 200 in 4.9–10.5 s with a
deflection, and **zero tools were invoked** — `grep -c "MCP tool http (start|completed)"` over the
whole run window returns **0**.

| Q | Expected facade | HTTP | Elapsed | Response shape | Verdict |
|---|---|---|---|---|---|
| q01 | WorkorderFacadeTool | 200 | 10.5s | screen-link deflection | FAIL |
| q03 | WorkorderFacadeTool | 200 | 6.1s | no-path deflection | FAIL |
| q04 | InvoiceFacadeTool | 200 | 6.3s | screen-link deflection | FAIL |
| q05 | AccountingFacadeTool | 200 | 5.7s | screen-link deflection | FAIL |
| q07 | InvoiceFacadeTool | 200 | 5.8s | clarifying question | FAIL |
| q08 | InvoiceFacadeTool | 200 | 5.9s | no-path deflection | FAIL |
| q09 | InvoiceFacadeTool | 200 | 8.1s | clarifying question | FAIL |
| q12 | AccountingFacadeTool | 200 | 5.7s | screen-link deflection | FAIL |
| q13 | AccountingFacadeTool | 200 | 4.9s | screen-link deflection | FAIL |
| q15 | AccountingFacadeTool | 200 | 8.3s | clarifying question | FAIL |
| q16 | AccountingFacadeTool | 200 | 5.3s | screen-link deflection | FAIL |
| q17 | AccountingFacadeTool | 200 | 5.6s | no-path deflection | FAIL |
Deflection shapes: *screen-link* — "I can't compute that directly, but you can view it here —
People: /app/people"; *no-path* — "I couldn't find a way to answer that from the available tools or
screens"; *clarifying question* — the model offers a narrower capability (e.g. "I can pull the
top-revenue customers for a **single calendar year**") and asks which window is wanted.

## What passed

**Tool selection (criterion B) — PASS.** `nlti.request.telemetry` for all twelve admin turns shows
`candidateCount=16`, `rejectedPermissionCount=0`, `discoveredOpenapi=16`, and every one of the 16
facades selected, including the exact facade each question needs. Router domain classification was
correct throughout (`workorder`, `customer`, `invoice`, `accounting`). Nothing about routing,
gating, or discovery failed.

**Under-permissioned degradation (§2.2) — PASS.** `kyle.brennan` (ROLE_TECHNICIAN) received
`candidateCount=5` — Pricing, Workorder, Inventory, Events, Catalog — with **no
AccountingFacadeTool and no InvoiceFacadeTool**, and answered without them. The V40 per-method
permission groups gate exactly as designed against a real principal.

## What failed, and where the failure is not

The model was handed the right tools and did not call them. That locates the defect precisely:

- **Not selection** — every needed facade was in the candidate set (telemetry above).
- **Not permissions** — zero rejections for the admin caller; the technician's narrower set is
  correct behaviour.
- **Not the endpoints** — the Wave 2 analytics endpoints exist, are registered, and are
  permission-mapped (#1632 close-out); the ground-truth suite queries the same data successfully.
- **Not the data** — the Track B fixture is live and verified four ways (#1647).
- **The failure is tool *invocation*.** With all sixteen facades in context, the agent chose a
  screen-link or "no path" deflection instead of a tool call, in every single case.

Response latency (4.9–10.5 s, no HTTP tool traffic) is consistent with a single model turn that
never attempted tool use.

## Hypotheses, untested

1. **Prompt bias toward deflection.** The screen-link phrasing is formulaic and appears in 6 of 12
   answers; `ScreenLinkResolver` and the TOOL_USE/DOMAIN prompt layers may make deflection the
   easier completion than a multi-step tool call.
2. **Descriptions do not advertise analytic capability.** The three Wave 2 facade methods were
   written to be honest about limits (the Wave 1 W1.1 standard); they may not read as *able* to
   answer an aggregate question.
3. **Model capability at multi-step composition.** `gpt-oss:120b` (T2_COMPLEX tier) may not attempt
   the plan-and-call sequence these questions need. The three clarifying-question responses suggest
   the model understood the ask and negotiated scope rather than acting.

These are ordered by cheapness to test, not by likelihood. A prompt/description experiment against
this same fixture is now a one-command re-run.

## Programme status after this run

- §2.1 criterion 1 (answer correctness): **0/12**.
- Criterion 2 (honest tool trace): vacuously satisfied — no calls, so no fabricated parameters.
- Criterion 3 (bounded cost): satisfied — well inside every §6 budget.
- Criterion 4 (no silent truncation): not exercised.
- #1601 criterion B (selection) and the §2.2 under-permissioned run: **PASS**.

The programme's earlier claim — "0/20 gates passed, selection verified" — is now measured rather
than assumed, and the remaining gap is a single, well-located behaviour: the agent does not invoke
the tools it is given.

## Reproducing

```bash
# operator machine, with durion-positivity-sdk checked out
./scripts/alpha-itest-tunnel.sh            # SSM port-forward 18080/18086
python3 gate_run.py /tmp/gaterun           # 12 questions + under-permissioned pass
# telemetry: docker logs --since <start> backend-pos-mcp-server-1 | grep nlti.request.telemetry
```
Credentials come from `durion-positivity-sdk/.env.itest` and are read by the runner directly; they
are never passed through SSM parameters or printed.

---

# Root cause determination — 2026-09-02 (#1653)

All three hypotheses above are **refuted**. The agent did not "choose" deflection, and the model is
not weak at tool use. The tool calls were made and then dropped on the floor.

**As of Spring AI 2.0, `ChatModel.call` does not execute tools.** It advertises the tool definitions
and returns the model's tool-call turn verbatim; the tool-execution loop moved into `ChatClient`'s
`ToolCallingAdvisor`. `SpringAiPosAssistant` (and `SpringAiStreamingPosAssistant`) called the model
directly, so nothing ever ran a tool.

Evidence, each independently checked:

| Check | Result |
|---|---|
| Does the model emit `tool_calls`? | **Yes.** Direct probe of the configured endpoint returned `tool_calls=1`, `getAgedReceivables{"asOfDate":"2026-09-01"}` |
| Were tools offered? | **Yes** — `selectedTools=16` |
| Tool HTTP calls in the container's whole log | **0** |
| `executeToolCalls` / `isInternalToolExecutionEnabled` in `OllamaChatModel` | **0 references** |
| Which type does execute | `chat/client/advisor/ToolCallingAdvisor`, reachable only via `ChatClient` |

The probe also reproduced the exact production shape: on a tool-call turn `content` is **empty**
while `thinking` is populated. That is what produced the deflections — blank content fell through
`ChatResponseText` to recovered reasoning, and `resolveResponse` then substituted the ladder's
screen link. The formulaic screen-link phrasing in 6 of 12 answers was the *symptom* of the dropped
tool call, not evidence of prompt bias.

`OLLAMA_CHAT_THINK=false` was already set and correctly applied; it is not implicated. Nor were the
facade descriptions: probes P2 ("Show me per-customer revenue for 2026-08.") and P5 ("Call the aged
receivables tool for 2026-09-01") were deflected despite being textbook usage, which is explained by
this defect and not by wording.

## Note on method

Zero tool calls was recorded as "the agent does not invoke the tools it is given" — a statement about
the *agent's behaviour*. It was actually a statement about the framework: the agent did invoke them.
The run could not distinguish the two because nothing logged whether a tool call had been returned
and dropped. `SpringAiPosAssistant` now logs `unexecutedToolCalls` alongside `offeredTools` and the
extraction source on every turn that yields no direct answer, so this class of failure is legible
from the logs rather than requiring a library-bytecode audit to find.
