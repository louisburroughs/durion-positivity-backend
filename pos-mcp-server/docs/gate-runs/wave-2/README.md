# Wave 2 gate runs — PENDING

Issue: #1601 (W2.3, Wave 2 exit gate) · analytics-capability-plan.md §4 "Wave 2 exit gate"

## Status: not run in this sandbox

This directory is a placeholder. The Wave 2 exit gate requires running the Q-gate protocol
(Q1, Q3, Q4, Q5, Q7, Q8, Q9, Q12, Q15, Q16, Q17 — analytics-capability-plan.md §2/§6) plus the
under-permissioned-caller check against a live stack:

- an alpha host or a docker-profile stack with Postgres/pgvector, Eureka, the gateway, and every
  Wave 2 backend module running with fixture data loaded, and
- the `durion-eval` harness (`eval_live.py` / `BaselineCaptureIT`, `-Dmcp.eval.live=true`) driving
  real chat turns against a running embedding model.

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
