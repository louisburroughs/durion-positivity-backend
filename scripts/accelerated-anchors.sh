#!/usr/bin/env bash
set -euo pipefail

# Generates and validates the anchor pair for an accelerated alpha run (#2065).
#
# The dispatching workflow (deploy-alpha-accelerated.yml) runs this once and hands the same
# pair to every JVM. It lives here rather than inline in the workflow so the arithmetic and
# every refusal are covered by scripts/tests/accelerated-anchors-selftest.sh.
#
#   real_start     the wall-clock instant the run is launched (now)
#   virtual_start  real_start minus `days` x 86400 seconds — exactly `days` back, as
#                  86400-second days rather than calendar arithmetic, so a run dispatched on
#                  29 Feb still lands a whole number of days back
#
# Refused, with the reason on stderr and exit 1:
#   - days that is not a positive integer;
#   - scale that is not a number, is <= 1 (the gap would never close), or is >= --max-scale
#     (the SDK suite refuses a backend that fast: a ten-hour virtual window would be ~1.4 real
#     seconds at 26280);
#   - a pair whose gap closes in less than --min-converge-hours of real time. The stack is
#     rolled out under a 45-minute SSM timeout and then verified for up to five more minutes,
#     and verify-accelerated-deployment.sh fails a stack whose clock has ALREADY converged: a
#     run that converges inside that window would recreate 25 services and then fail
#     verification with no back-dated window left. `gap / (scale - 1)` must cover it.
#
# Output, one key=value per line on stdout, in the shape $GITHUB_OUTPUT takes:
#   real_start=…  virtual_start=…  days=…  scale=…  hours_to_converge=…
#
# Usage:
#   accelerated-anchors.sh --days N --scale S [--max-scale M] [--min-converge-hours H] [--now EPOCH]
#
# --now pins the wall clock (seconds since the epoch) for the self-test; the workflow never
# passes it.

DAYS=""
SCALE=""
MAX_SCALE="26280"
MIN_CONVERGE_HOURS="1"
NOW=""

usage() {
  echo "usage: $0 --days N --scale S [--max-scale M] [--min-converge-hours H] [--now EPOCH]" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --days) DAYS="${2:-}"; shift 2 ;;
    --scale) SCALE="${2:-}"; shift 2 ;;
    --max-scale) MAX_SCALE="${2:-}"; shift 2 ;;
    --min-converge-hours) MIN_CONVERGE_HOURS="${2:-}"; shift 2 ;;
    --now) NOW="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -n "${DAYS}" && -n "${SCALE}" ]] || usage

# Digits first, then decimal whatever was typed: `$(( ))` and `-le` would read a leading zero
# as octal, and '0092' is not a valid octal number.
if [[ ! "${DAYS}" =~ ^[0-9]+$ ]] || [[ "$((10#${DAYS}))" -le 0 ]]; then
  echo "days must be a positive integer (got '${DAYS}'); the clock is anchored that many days in the past." >&2
  exit 1
fi
DAYS="$((10#${DAYS}))"

if [[ ! "${SCALE}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || ! awk -v s="${SCALE}" 'BEGIN { exit !(s > 1) }'; then
  echo "scale must be a number greater than 1 (got '${SCALE}'); at 1 or below the gap never closes." >&2
  exit 1
fi
if ! awk -v s="${SCALE}" -v m="${MAX_SCALE}" 'BEGIN { exit !(s < m) }'; then
  echo "scale must be below ${MAX_SCALE} (got '${SCALE}'). The SDK suite refuses a backend that fast: a ten-hour virtual window would be ~1.4 real seconds." >&2
  exit 1
fi

GAP_SECONDS=$((DAYS * 86400))
HOURS_TO_CONVERGE="$(awk -v g="${GAP_SECONDS}" -v s="${SCALE}" 'BEGIN { printf "%.1f", g / (s - 1) / 3600 }')"

if ! awk -v g="${GAP_SECONDS}" -v s="${SCALE}" -v h="${MIN_CONVERGE_HOURS}" 'BEGIN { exit !(g / (s - 1) >= h * 3600) }'; then
  echo "days=${DAYS} at scale ${SCALE} converges on wall time after ~${HOURS_TO_CONVERGE} real hours, inside the ${MIN_CONVERGE_HOURS}h the rollout and its verification need: the stack would be recreated and then fail verification with no back-dated window left. Lower the scale or raise the days so that days * 86400 / (scale - 1) covers ${MIN_CONVERGE_HOURS}h." >&2
  exit 1
fi

if [[ -z "${NOW}" ]]; then
  NOW="$(date -u +%s)"
elif [[ ! "${NOW}" =~ ^[0-9]+$ ]]; then
  echo "--now must be seconds since the epoch (got '${NOW}')." >&2
  exit 1
fi

# Both anchors from the one epoch read, so the gap is exactly `days`.
REAL_START="$(date -u -d "@${NOW}" +%Y-%m-%dT%H:%M:%SZ)"
VIRTUAL_START="$(date -u -d "@$((NOW - GAP_SECONDS))" +%Y-%m-%dT%H:%M:%SZ)"

echo "real_start=${REAL_START}"
echo "virtual_start=${VIRTUAL_START}"
echo "days=${DAYS}"
echo "scale=${SCALE}"
echo "hours_to_converge=${HOURS_TO_CONVERGE}"
