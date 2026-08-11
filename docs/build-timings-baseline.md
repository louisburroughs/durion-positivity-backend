# CI Build Timings — Baseline (pre-optimisation)

Snapshot taken 2026-08-06 from the GitHub Actions API, before implementing the phases of
`docs/BACKEND_BUILD_SPEC.md`. This is the phase-0 reference the §6.4 acceptance measurements
compare against.

## Wall-clock per run — Backend CI/CD (`ci.yml`), last 20 completed runs

| Run | Event | Conclusion | Wall clock |
|---|---|---|---|
| 31078920629 | schedule | success | 16m 36s |
| 30982969915 | schedule | success | 16m 25s |
| 30885604530 | schedule | success | 16m 18s |
| 30792523080 | schedule | success | 14m 56s |
| 30736747332 | schedule | success | 15m 47s |
| 30688520005 | schedule | success | 16m 08s |
| 30611317239 | schedule | success | 13m 26s |
| 30586145382 | push (main) | success | 13m 31s |
| 30585031095 | pull_request | success | 15m 01s |
| 30583669351 | pull_request | success | 9m 55s |
| 30578660422 | push (main) | success | 100m 51s |
| 30578406109 | push (main) | cancelled | (superseded) |
| 30577361121 | pull_request | success | 9m 58s |
| 30576408352 | pull_request | success | 8m 44s |
| 30576334950 | pull_request | success | 9m 11s |
| 30573443679 | push (main) | failure | 33m 45s |
| 30571615501 | push (main) | cancelled | (superseded) |
| 30571337886 | push (main) | cancelled | (superseded) |
| 30570892756 | pull_request | success | 8m 55s |
| 30570326263 | pull_request | success | 9m 53s |

Medians: **pull_request ≈ 9m 55s** (n=7, range 8m 44s – 15m 01s, typically 1–2 changed modules);
**push to main** is bimodal — ~13m for narrow changes, 30–100m when the integration-test /
docker matrices fan out. Scheduled (full-coverage Sonar) runs cluster at ~16m.

## Job-level dissection — PR run 30585031095 (changed: `pos-mcp-server`; 15m 01s total)

| Job | Duration | Fixed overhead* | Useful work |
|---|---|---|---|
| Detect Test Modules | 0m 06s | 6s | diff + jq |
| Build Reactor (install all) | 2m 29s | ~10s | install 1m 44s, test-infra warmup 9s, cache save 28s |
| Unit Tests (pos-mcp-server) | 1m 50s | **1m 12s** | tests **0m 38s** |
| Unit Tests (pos-archunit) | 3m 16s | **1m 16s** | tests 1m 48s |
| Incremental Code Quality | 3m 11s | ~1m 10s | Sonar scan 1m 56s |
| Notify Build Status | 0m 03s (started after a 5m 42s queue gap) | — | — |

\* Fixed overhead = job setup + checkout + JDK + wrapper cache + `~/.m2` reactor-cache restore
(the restore alone is ~58–68s per job) + report uploads.

Key baseline facts the spec's phases target:

- **Per-matrix-job overhead (G2):** each Unit Tests job pays ~70s of setup/restore before any
  test runs; for `pos-mcp-server` that is 2× the actual test time. Every additional changed
  module adds one such job.
- **Repeated lint scripts (G5):** `check-noarg-now.sh` + `check-flyway-hygiene.sh` ran in both
  matrix jobs (~2s each — small, but N×).
- **Queue gaps between dependent jobs:** the Notify job sat queued 5m 42s after its
  dependencies finished; every job boundary risks such a gap, so fewer jobs = fewer gaps.
- **Push-run totals:** run 30578660422 (wide change) accumulated **100.9 minutes** of run
  duration across 13 jobs; run 30586145382 (narrow change) 13.5 minutes across 13 jobs.
- **Downstream dependents (G1):** no baseline number possible — dependent modules simply do not
  run today; the synthetic `pos-shared-dtos` PR check in §6.4 starts failing-by-design here.

## Comparison protocol (post-phase)

For each landed phase, re-sample the most recent 20 completed `ci.yml` runs and recompute the
medians above, split by event type and by changed-module count (1, 2–3, shared-lib). Runner-cost
comparisons use summed job durations (billable minutes are all zero on this repo's plan, so wall
time per job is the proxy).
