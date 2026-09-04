# Wave 2 gate runs — PENDING

Issue: #1601 (W2.3, Wave 2 exit gate) · analytics-capability-plan.md §4 "Wave 2 exit gate"

## Status: not run in this sandbox

This directory is a placeholder. The Wave 2 exit gate requires running the Q-gate protocol
(Q1, Q3, Q4, Q5, Q7, Q8, Q9, Q12, Q15, Q16, Q17 — analytics-capability-plan.md §2/§6; Wave 1's Q13
runs with them, making the twelve-question chat-path set) plus the under-permissioned-caller check
against a live stack:

- an alpha host or a docker-profile stack with Postgres/pgvector, Eureka, the gateway, and every
  Wave 2 backend module running with fixture data loaded, and
- the `durion-eval` harness (`eval_live.py` / `BaselineCaptureIT`, `-Dmcp.eval.live=true`) driving
  real chat turns against a running embedding model.

The question text is **not** improvised per run (#1671): it lives in
`pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json`, which also records which
twelve of the twenty are in the chat-path set and why the other eight are excluded. Drive it with
`scripts/analytics_gate_run.py`, which reads that file and writes the questions' git blob sha into
the run record; a run document that does not carry that sha cannot be reproduced and should not be
scored.

None of that infrastructure is reachable from this sandbox (no live alpha/docker stack, no
running model). **No gate-run results — pass or fail — have been produced or recorded here.**
Anything that looks like a Q-number result elsewhere in this PR is not real; treat only this
README as authoritative on gate-run status.

## What this issue's W2.3 slice actually verified in this sandbox

- `./mvnw -pl pos-mcp-server -am -DskipTests=false test` (full module suite, DB-free —
  H2/mocks only) — includes the new facade unit tests
  (`AccountingFacadeToolTest`, `InvoiceFacadeToolTest`, `WorkorderFacadeToolTest`), the
  migration-replay guards (`FacadeToolPermissionSeedTest`, `EvalFixtureSatisfiabilityTest`), and
  the new `Wave2ToolSelectionRegressionTest` selection-verification fixture (see that class's
  Javadoc for what it locks).
- `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test` — cross-module package/layering
  rules.

Neither exercises the real embedding model, pgvector ANN ranking, or an end-to-end chat turn —
they are static/mocked checks that the code and the permission seed are internally consistent,
not that the selector or the answers are correct against real data. Running the actual gate
protocol and recording real results here is separate follow-up work once a live/docker stack with
fixture data is available.

## Also not run here: under-permissioned caller check

analytics-capability-plan.md §4 requires: "caller lacking `invoice:analytics:view` must get zero
analytics tools and an honest 'not authorized' degradation on Q7." This needs the same live stack
as the Q-gate runs above and was not exercised in this sandbox. The permission-gate *logic* for
this is covered statically by `FacadeToolPermissionSeedTest` and `EvalFixtureSatisfiabilityTest`
(a caller without the code cannot reach the tool per the seeded groups), but that is not the same
as observing the actual degraded chat response end-to-end.

## Live runs recorded here

- `2026-09-02-chat-path-gate-run.md`
- `2026-09-03-chat-path-gate-rerun.md`
- `2026-09-03-window-shape-evidence.md` — the three runs across the date-window work (#1675 →
  #1677 → #1684), and the finding that moving the arithmetic into code did not move q09/q12/q15
  because shape *classification*, not arithmetic, is the failing stage.
