# Accelerated Alpha Deployment Runbook

Deploying the alpha stack on the `accelerated` Spring profile, with every POS JVM sharing one
clock anchored a year in the past, so the SDK repo's accelerated integration suite can drive
365 virtual days of shop activity in 1–6 real hours and produce a year of financial history.

- Issue: [#2065](https://github.com/louisburroughs/durion-positivity-backend/issues/2065)
- SDK side: [durion-positivity-sdk#64](https://github.com/louisburroughs/durion-positivity-sdk/issues/64),
  and `packages/sdk-integration-tests/ACCELERATED_BACKEND_DEPLOYMENT.md` in that repo for the
  operator-facing suite procedure
- Clock internals: [`docs/CLOCK_TIMESTAMP_OWNERSHIP.md`](../CLOCK_TIMESTAMP_OWNERSHIP.md),
  `pos-events/README.md`

> **This back-dates records in the shared alpha database, and blocks every ordinary
> integration run for as long as it is deployed** — their guard aborts when
> `GET /system/time` answers 200. Treat a deployment as a scheduled exercise with an owner and
> an end time, not as a background experiment.

## How the clock works

`pos-events` supplies a single converging `ScaledClock` under the `accelerated` profile:

```
virtual(t) = min(virtualStart + scale * (now - realStart), now)
```

Three consequences worth holding on to:

- **It never writes future-dated records.** Virtual time is clamped to wall time, so it trails
  the present, catches up, and then ticks at 1× forever. This is what `converge` buys, and it is
  why the run does not need to be stopped by hand at the current date. It is not optional here:
  `deploy-backend.sh` refuses `POS_TIME_ACCELERATED_CONVERGE` set to anything but `true`, because
  with it off the stack future-dates every row it writes into a database other people share, and
  no later deploy can unwrite them.
- **It stops being accelerated once it converges.** A one-year gap closes after
  `gap / (scale - 1)` real time — about 6 h at 1460, 3 h at 2920, 1 h at 8760. After that the
  stack is on ordinary wall time and there is no back-dated window left, so the SDK run has to
  finish inside that budget.
- **Every JVM must share the same two anchors.** They are generated once, in CI, at dispatch.
  A service deriving its own anchor is skewed from every other by `scale ×` its own startup
  delay: at 1460, one real second of startup skew is 24 virtual minutes.

Faster is not better. The SDK suite refuses a scale of 26,280 (≈20 min to converge): a ten-hour
virtual open window would be 1.4 real seconds, too tight for any work to progress. The deploy
workflow refuses it too.

## What deploys it

| Piece | Where |
| --- | --- |
| The override — profile + five anchors on all 25 POS JVMs | `deployment/alpha/docker-compose.accelerated.yml` |
| `ACCELERATED=true`, anchor validation, `.env` persistence, teardown | `deployment/alpha/deploy-backend.sh` |
| The dispatch | `.github/workflows/deploy-alpha-accelerated.yml` |
| Post-deploy verification, on the box | `deployment/alpha/verify-accelerated-deployment.sh` |
| Post-run timestamp audit, against the databases | `deployment/alpha/verify-accelerated-timestamps.sql` |
| CI coverage guard | `scripts/check-accelerated-compose.sh` |

`eureka-server` and `pos-reference-mock` are deliberately left on the wall clock: a service
registry and a mock external vendor write no business timestamps.

## Deploy

1. **Make sure the images exist.** This workflow builds nothing. Run `Build and Push to ECR`
   for the commit you want first, or use a tag already in ECR.

2. **Dispatch `Deploy Alpha (Accelerated Clock)`** from the Actions tab, or:

   ```bash
   gh workflow run deploy-alpha-accelerated.yml \
     -f backend_tag=sha-a1b2c3d \
     -f scale=1460 \
     -f days=365 \
     -f confirm='ACCELERATE ALPHA'
   ```

   | Input | Meaning |
   | --- | --- |
   | `backend_tag` | The ECR tag (`sha-a1b2c3d`) or the commit SHA behind it. Blank uses this ref's head commit. |
   | `scale` | Virtual seconds per real second. Must be `> 1` and `< 26280`. |
   | `days` | Virtual days the SDK run will drive. Recorded in the summary; the clock is always anchored one year back. |
   | `confirm` | Must be exactly `ACCELERATE ALPHA`. |

   The workflow generates `POS_TIME_ACCELERATED_REAL_START` (now) and `_VIRTUAL_START`
   (one year ago) **once** and hands the same pair to every service. It shares the
   `alpha-deploy` concurrency group with `Build and Push to ECR` and `Sync Alpha Config`, so no
   two of them can interleave on the box.

3. **Read the run summary.** It records the backend tag, both anchors, the scale, the requested
   days, when the clock converges, and who dispatched it. The anchors are the run's identity —
   the SDK suite's journal refuses to resume a run whose `realStart` differs from the one it
   recorded, so copy them from here rather than re-deriving them.

The deploy refuses, before anything on the box is touched:

- a missing or malformed anchor;
- a `virtual-start` less than **360 days** before `real-start` (the SDK suite refuses such a
  backend anyway, and failing at deploy time is cheaper than failing after a 25-service
  rollout);
- a scale that is not a positive number, or is `<= 1` (the gap would never close);
- `POS_TIME_ACCELERATED_CONVERGE` set to anything but `true` (see above);
- a `POS_TIME_ACCELERATED_ZONE` that `java.time.ZoneId` would reject — checked against the box's
  IANA database rather than for emptiness, since an unknown id otherwise fails the configuration
  binding at startup, on all 25 services at once;
- `ACCELERATED=true` on a `--config-only` sync;
- an on-box override file that is missing or does not match the committed one.

## Verify

The workflow runs `verify-accelerated-deployment.sh` on the box and fails if anything is wrong.
To re-check by hand at any point during a run:

```bash
# On the alpha box
EXPECTED_REAL_START=2026-09-17T12:00:00Z \
EXPECTED_VIRTUAL_START=2025-09-17T12:00:00Z \
EXPECTED_SCALE=1460 \
bash /opt/durion/alpha/scripts/verify-accelerated-deployment.sh
```

It answers three separate questions:

1. **Every POS JVM** carries the profile and the *same* five settings — both anchors, the scale,
   the zone and convergence — read from each container's environment. The gateway answering
   correctly says nothing about the other 24, and a single JVM with convergence off would
   future-date everything it writes while every other check still passed.
2. **The live clock** at `GET /system/time` reports `accelerated: true`, `converged: false`,
   the dispatched scale and the generated anchors.
3. **Virtual time is moving** at roughly `scale`, measured across two samples.

The gateway endpoint on its own:

```bash
curl -s https://<alpha-host>/system/time
# {"virtualTime":"2025-09-18T04:22:13Z","scale":1460.0,"zone":"UTC","accelerated":true,
#  "converged":false,"realStart":"2026-09-17T12:00:00Z","virtualStart":"2025-09-17T12:00:00Z"}
```

`converged: true` means the gap has closed and the accelerated window is over.

## During the run

- **Config changes still reach the box.** A merge that triggers `Sync Alpha Config` runs
  `deploy-backend.sh --config-only`, which reads `POS_ACCELERATED=true` out of the on-box
  `.env`, keeps the override and leaves the anchors untouched. It cannot start or end a run.
- **Any ordinary deploy ends the run.** `Build and Push to ECR` with `deploy_alpha=true`, and
  the automatic promotion path when `AUTO_DEPLOY_ALPHA` is on, both tear it down. If an
  accelerated run is in flight, keep `AUTO_DEPLOY_ALPHA` off or expect a green main push to
  stop it.
- **Do not re-dispatch mid-run.** A second dispatch re-anchors the stack, and the SDK suite's
  journal will refuse to resume against the new `realStart`.

## Teardown

An ordinary deploy is the teardown. Nothing else is required:

```bash
gh workflow run build-push-ecr.yml -f deploy_alpha=true
```

`deploy-backend.sh` removes `POS_ACCELERATED` and all five anchors from the on-box `.env` and
drops the override, so the stack comes back on the wall clock.

**The proof is a 404:**

```bash
curl -i -s https://<alpha-host>/system/time | head -1
# HTTP/1.1 404 Not Found
```

Until it answers 404, every non-accelerated integration run stays blocked — their guard aborts
on a 200. A 200 after a teardown deploy means the deploy did not take; check the deploy's log
for `Accelerated clock cleared`.

## Audit what the run wrote

With the stack back on the wall clock, run the timestamp audit against each service schema,
passing the run's virtual anchor as a raw ISO value:

```bash
psql -v virtual_start=2025-09-17T12:00:00Z \
  -f deployment/alpha/verify-accelerated-timestamps.sql
```

Every row it returns is a defect: a timestamp after wall time (a write that bypassed the
injected `Clock`), one before the run's anchor (legitimate for rows that predate the run —
reported for review), or an `updated_at` earlier than its own `created_at`. An empty result
means every audited timestamp came from the application clock. The accepted database-clock
reads are listed in the SQL file's header and in
[`docs/CLOCK_TIMESTAMP_OWNERSHIP.md`](../CLOCK_TIMESTAMP_OWNERSHIP.md).

## Adding a service

A new `pos-*` service added to `docker-compose.yml` must also be added to
`deployment/alpha/docker-compose.accelerated.yml`:

```yaml
  pos-new-service:
    environment: *accelerated-env
```

`scripts/check-accelerated-compose.sh` fails CI otherwise. If the service genuinely writes no
business timestamps, add it to `EXCLUDED_SERVICES` in that script with a reason instead.
