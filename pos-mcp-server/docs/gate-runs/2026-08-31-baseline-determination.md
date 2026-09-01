# Baseline determination — 2026-08-31

Issue: #1606 finding 2 · First live execution of `BaselineCaptureIT`
Run environment: alpha host, `durion-eval` checkout, alpha Postgres/pgvector + ollama (`bge-m3`)
Commit under test: `6a1abecac` (main, after #1603)

## Observed

| Metric | Observed | Floor | Gap |
|---|---|---|---|
| hit@5 | 0.60 | 0.68 | −0.08 |
| MRR | 0.5677 | 0.64 | −0.072 |

Plus three forbidden-tool violations (see finding 1) and `scored=80` of 110 fixtures (finding 3).

## Question

Has the selector degraded since the 0.76 / 0.7222 baseline the floors were derived from, or were
`BaselineCaptureIT` and `eval_live.py` never measuring the same thing?

## Determination: both, and the floors were never valid for this IT

**1. The two harnesses measure different pipelines.** `scripts/eval_live.py` says so itself, at
lines 24–26:

> Caveat: this scores on the raw ANN order; the Java path applies a light ToolScorer re-rank on top,
> so hit@5 here is a close proxy, not bit-identical.

`eval_live.py` issues the gated ANN query directly and scores the raw vector order.
`BaselineCaptureIT` calls `ToolRegistryService.resolveCandidateTools`, which additionally applies
the admin fast path, the `ToolScorer` re-rank (`semantic rank + priority − latency − cost`), and the
candidate limit. Same fixtures, different pipeline, different numbers.

The floor comment in `BaselineCaptureIT` reads "~11% below the live-observed alpha baseline (hit@5
0.76, MRR 0.7222), matching eval_live.py". That baseline is an `eval_live.py` observation, and it
was applied as a floor to a measurement `eval_live.py` does not produce. **The floors have never
been validated against the harness that enforces them.** A gap of this size is therefore not
evidence of degradation on its own.

**2. The permission gate also widened after the baseline was taken.** The floors were re-baselined
2026-07-29 (#1124). `V37__facade_permission_rederivation.sql` landed 2026-08-26 (`f2282dd40`,
#1519 Wave 4) — four weeks later — and re-derived each facade's permission rows as the union of
every `@Tool` method and every composition leg. Combined with OR-semantics gating, that admits more
tools per caller (this is finding 1's defect). A wider gated set means more candidates competing for
the top-5 window, which can displace the expected tool and depress both hit@5 and MRR.

So there is a real mechanism for degradation, it postdates the baseline, and it is the same defect
finding 1 corrects.

## Consequence: do not re-baseline yet

Re-baselining now would fix the floors to a number produced by a permission gate we have already
decided is wrong. The order that follows from the above is:

1. Land the per-method AND-group gate fix (finding 1, `V40`). This changes the gated candidate set,
   so it necessarily moves hit@5 and MRR.
2. Re-run `BaselineCaptureIT` on alpha and record the observed numbers **from this harness**.
3. Set the floors from that observation, citing the run, and correct the floor comment so it no
   longer attributes an `eval_live.py` number to this IT.

Only after step 3 is a floor breach evidence of regression rather than of a mismatched yardstick.

## What this does not claim

This determination does not establish that the selector is healthy. It establishes that the current
gap is unexplained by the comparison that produced it, and that one known mechanism (V37's widening)
plausibly accounts for part of it. Whether any residual gap remains after the gate fix is an open
question that step 2 answers.

## Reproducing the run

From the alpha host (`i-06d434c7593e70f5c`), in the `durion-eval` checkout:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-tem
export PATH="$JAVA_HOME/bin:$PATH"
# .env values are unquoted and some contain '$' — read them literally, never `source`.
ENVF=/opt/durion/alpha/.env
export POS_MCP_DB_USER="$(grep -m1 '^SPRING_DATASOURCE_USERNAME=' $ENVF | cut -d= -f2-)"
export POS_MCP_DB_PASSWORD="$(grep -m1 '^SPRING_DATASOURCE_PASSWORD=' $ENVF | cut -d= -f2-)"
export POS_MCP_DB_HOST=localhost POS_MCP_DB_PORT=5432 POS_MCP_DB_NAME=pos_mcp
export OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434
./mvnw -pl pos-mcp-server test -Dtest=BaselineCaptureIT -Dmcp.eval.live=true \
    -Dspring.profiles.active=alpha
```

Verify the tree under test before believing a result: assert the full HEAD sha and echo the fixture
being asserted into the log. A prior attempt silently evaluated `main` instead of the intended
branch — the host clone is single-branch, so `git fetch origin` does not retrieve other branches,
and a script body placed on the left of `||` has `set -e` suppressed, so a failed checkout did not
stop the run. It produced plausible, wrong numbers.

---

# Post-V40 measurement — 2026-08-31 (determination CLOSED)

Run: alpha, `durion-eval` at `88db20c73` (delta from the deployed `ee2712874` is pos-people only —
zero `pos-mcp-server` files). V40 applied cleanly to Postgres 16.6 at container boot, schema 39 → 40
in 183 ms.

| Metric | Before V40 | After V40 | Floor |
|---|---|---|---|
| hit@5 | 0.60 | **0.55** | 0.68 |
| MRR | 0.5677 | **0.5271** | 0.64 |
| forbidden violations | 2 | **0** | must be 0 |
| RAG recall@k | — | 0.951 (51/9/0 of 60) | 0.76 |

**The permission fix works.** Both `ts-customerfacadetool-neg-*` violations cleared — the #1606
finding-1 defect is closed against real Postgres, not just H2.

## Why the metrics fell, and why that is correct

The drop is exactly four fixtures, all `ts-shopmanagerfacadetool-pos-1..4`. Their actors hold
`shop:location:view` + `shop:schedule:view`. Post-V40 `ShopManagerFacadeTool`'s groups are
`getShopStatus{location:read}`, `getShopQueue{workorder:wip:view}`, `searchShops{location:read}` —
the facade calls **pos-location** endpoints, and neither actor code satisfies any group. Those
callers cannot complete a single method of that tool. They were previously admitted only because
V37's union carried `shop:schedule:view` in from the *optional* schedule leg — the exact
over-exposure V40 removes. Both permission codes are real (`pos-shop-manager/permissions.yaml`), so
the fixtures are not fictional; their actors are simply not authorised for the tool they assert.

## The finding that closes this determination

| | achievable | observed |
|---|---|---|
| Before V40 (OR gate) | 48 / 80 = 0.60 | 0.60 |
| After V40 (AND groups) | 44 / 80 = 0.55 | 0.55 |

**The selector achieves 100% of achievable hits in both runs.** A positive fixture can only hit if
its actor's permissions admit the expected tool; **36 of 80 (45%) scored positives fail that test by
construction** — they assert that a tool should be selected for a caller the gate does not permit it
to. hit@5 is therefore measuring *fixture-corpus coherence*, not selector quality, and has been
doing so all along.

This supersedes the "did the selector degrade?" framing. It did not. Nor is the earlier
pipeline-mismatch explanation the whole story: even a perfect selector cannot exceed 0.55 on this
corpus, so **the 0.68 / 0.64 floors were never attainable** — not before V37, not after V40.

## Recommended actions (decide before re-baselining)

1. **Fix the corpus, not the floors, first.** Audit all 36 unsatisfiable positives. Each is either a
   mis-specified actor (the ShopManager four look like this — a "shop manager" persona given
   shop-domain codes for a facade that depends on the location domain) or a genuine gap where the
   facade's grouping is wrong. Both are worth knowing; neither is visible in an aggregate.
2. **Only then set the floors**, from a corpus where the ceiling is 1.0, citing the run.
3. Meanwhile the `mcp.eval.last-hit5` / `last-mrr` tripwire should be moved to **0.55 / 0.5271**, so
   a further regression is still caught while the corpus work proceeds.
4. Consider asserting the ceiling itself: a positive fixture whose actor cannot satisfy any group of
   its expected tool is self-contradictory and should fail fixture validation, not silently cap the
   metric.

## Note on method

The 0.60 → 0.55 movement was only legible because the run tracked the previously observed values
alongside the floors (added on review as #1609 finding 5). Against the floors alone both runs fail
identically, and the four-fixture change — and everything above — would have been invisible.
