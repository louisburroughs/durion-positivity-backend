# 2026-09-03 — three gate runs on the date-window work, and what they showed

Record of the live evidence behind #1675, #1684 and #1688. #1675's premise — that moving the
window arithmetic into code would fix the multi-period spans — shipped in #1677 and did **not**
move the three questions it targeted. That result is the reason #1684 and #1688 exist, so it is
recorded here rather than left in a chat log.

## Runs

| Run | Alpha image | `QUESTIONS.json` blob | Score |
|---|---|---|---|
| 1 | `sha-14717ce` (contains #1672, prompt-only rules) | `eca6f7f3037f6da73bac96c8c2a51b250dbe31b2` | 4/12 |
| 2 | `sha-25282ad` (contains #1670) | `eca6f7f3037f6da73bac96c8c2a51b250dbe31b2` | 4/12 |
| 3 | `sha-0651b0c` (contains #1677 — `resolveDateWindow` shipped) | `d4cbc6c99acc621d3db03d53e19d1fc04d52950f` | 5/12 |

All three via `scripts/analytics_gate_run.py`, scored against the versioned `window` field on each
question and `ground-truth/EXPECTED.md`.

## The finding: code-computed dates did not move the failing questions

Run 3 is the one that matters. It is the first run with `DateWindowResolver` and
`resolveDateWindow` deployed — the whole of #1675's proposal, arithmetic moved out of the prompt
and into pure `java.time`.

| Q | wording | shape (spec) | run 1 | run 3 |
|---|---|---|---|---|
| q09 | "in the last twelve months" | calendar span | rolling | **rolling** |
| q12 | "in the last six months" | calendar span | rolling | **rolling** |
| q15 | mixed → calendar precedence | calendar span, both sides | rolling | **rolling** |

Unchanged. The single question that improved between runs 2 and 3 was q05, via #1676's composition
fix, which has nothing to do with windows.

## Why that is informative rather than disappointing

The arithmetic was never the failing stage. `DateWindowResolver`'s unit tests were green for every
one of these shapes on the day it merged; a `CALENDAR_SPAN` of twelve months has always resolved to
twelve whole months. What the model gets wrong is **classifying** the shape from the wording — and
#1675 left that where it was, in the prompt, because a resolver cannot classify without a model.

#1675's own plan comment predicted exactly this and named it as the remaining risk. Run 3 confirmed
it. Two consequences, both now tracked:

- **#1684** — the tool boundary could not show the error. `resolveDateWindow` has taken a required
  shape enum since #1675, but every reporting facade also accepted a bare `period` label, and a
  window resolved under the wrong shape is byte-indistinguishable from a correct one once it is a
  pair of dates in a downstream call. #1700 logged the shape; this PR removes the `period` bypass
  so a shape is recorded on *every* dated call, and adds `resolveNamedPeriod` so naming a period
  outright still has a route.
- **#1682** — none of the above is measurable at the rate it needs to be. Three runs, each needing
  an alpha deploy and a human reading markdown, produced one usable bit of information between
  them.

## Second finding: q16 regressed, and the regression is diagnostic

q16 ("vendor bills due in the next 14 days") answered correctly and unprompted on runs 1 and 2 —
run 2 produced `2026-09-04 → 2026-09-17` with the correct $3,800.00 total. On run 3 it stopped
answering and asked which range to use, for a window its own spec marks `stated_in_question: true`.

Filed as #1681. It is diagnostic because the model asked about precisely the thing the
`DATE_WINDOW` layer instructs it to default, which is what #1688's rule now states in one line:
**ask when the metric is undefined, answer when only the range is unstated.**

## Unscorable, and why

- **q07** — declined on the grounds that revenue reports accept only whole calendar months or
  years. That was true when the answer was produced and was fixed by #1677's range form; every
  reporting facade now takes an arbitrary `startDate`/`endDate`. Needs a single-question re-run
  before anything is concluded from it.
- **q17** — ground truth departs from the question as asked; noted in `QUESTIONS.json`.

## What this does not establish

None of these runs measured the same build twice, so none of the deltas are separable from
run-to-run variance. The executor ran at `temperature 0.2` until #1683 dropped it to 0, and
`num_ctx` was unset for all three runs. Treat every single-question movement here as a lead, not a
result — which is the argument for #1682 rather than a fourth manual run.
