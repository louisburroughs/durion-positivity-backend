#!/usr/bin/env bash
set -euo pipefail

# Self-test for scripts/accelerated-anchors.sh, the anchor generator the accelerated alpha
# deploy workflow runs at dispatch (#2065).
#
# Pins the arithmetic — virtual-start is real-start minus exactly `days` x 86400 seconds, from
# one epoch read — and every refusal: a bad `days` or `scale`, a scale at or above the SDK
# suite's ceiling, and a pair that would converge on wall time before the rollout and its
# verification could finish (the verifier fails a stack whose clock has already converged).
#
# Run: bash scripts/tests/accelerated-anchors-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/scripts/accelerated-anchors.sh"

# 2026-09-17T12:00:00Z
NOW=1789646400

FAILURES=0
CASE_FAILURES=0

assert() {
  local name="$1" result="${2:-}"
  if [[ "${result}" == "pass" ]]; then
    echo "  ok   ${name}"
  else
    echo "  FAIL ${name}"
    CASE_FAILURES=$((CASE_FAILURES + 1))
  fi
}

end_case() {
  FAILURES=$((FAILURES + CASE_FAILURES))
  CASE_FAILURES=0
}

# run <args...>: runs the script with a pinned clock; OUT/ERR/RC capture the result.
run() {
  set +e
  OUT="$(bash "${SCRIPT}" --now "${NOW}" "$@" 2> "${ERR_FILE}")"
  RC=$?
  set -e
  ERR="$(cat "${ERR_FILE}")"
}

ERR_FILE="$(mktemp)"
trap 'rm -rf "${ERR_FILE}"' EXIT

field() {
  sed -n "s/^$1=//p" <<< "${OUT}"
}

echo "case 1: the default dispatch (365 days at 1460) anchors exactly a year back"
run --days 365 --scale 1460
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "real-start is now" "$([[ "$(field real_start)" == "2026-09-17T12:00:00Z" ]] && echo pass)"
assert "virtual-start is 365 x 86400 s earlier" "$([[ "$(field virtual_start)" == "2025-09-17T12:00:00Z" ]] && echo pass)"
assert "reports the days" "$([[ "$(field days)" == "365" ]] && echo pass)"
assert "echoes the scale" "$([[ "$(field scale)" == "1460" ]] && echo pass)"
assert "converges in ~6 hours" "$([[ "$(field hours_to_converge)" == "6.0" ]] && echo pass)"
assert "emits only the five keys" "$([[ "$(wc -l <<< "${OUT}")" -eq 5 ]] && echo pass)"
end_case

echo "case 2: a shorter run anchors exactly that many days back"
run --days 92 --scale 1460
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "virtual-start is 92 days earlier" "$([[ "$(field virtual_start)" == "2026-06-17T12:00:00Z" ]] && echo pass)"
assert "converges in ~1.5 hours" "$([[ "$(field hours_to_converge)" == "1.5" ]] && echo pass)"
end_case

echo "case 3: the gap is 86400-second days, not calendar months (leap day in the window)"
# 2028-03-01T00:00:00Z minus 1 day crosses 29 Feb 2028.
run --days 1 --scale 2 --now 1835481600
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "lands on the leap day" "$([[ "$(field virtual_start)" == "2028-02-29T00:00:00Z" ]] && echo pass)"
end_case

echo "case 4: a leading zero is read as decimal, not octal"
run --days 0092 --scale 1460
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "normalises the days" "$([[ "$(field days)" == "92" ]] && echo pass)"
assert "anchors 92 days back" "$([[ "$(field virtual_start)" == "2026-06-17T12:00:00Z" ]] && echo pass)"
end_case

echo "case 5: days must be a positive integer"
for bad in 0 abc 1.5 -3 ""; do
  run --days "${bad}" --scale 1460
  assert "refuses days='${bad}'" "$([[ ${RC} -ne 0 ]] && echo pass)"
done
run --days 0 --scale 1460
assert "names the input" "$(grep -q "days must be a positive integer (got '0')" <<< "${ERR}" && echo pass)"
assert "prints no anchors" "$([[ -z "${OUT}" ]] && echo pass)"
end_case

echo "case 6: a scale that cannot close the gap is refused"
for bad in 1 0.5 0 abc; do
  run --days 365 --scale "${bad}"
  assert "refuses scale='${bad}'" "$([[ ${RC} -eq 1 ]] && echo pass)"
done
run --days 365 --scale 1
assert "says why" "$(grep -q 'at 1 or below the gap never closes' <<< "${ERR}" && echo pass)"
end_case

echo "case 7: a scale at or above the SDK suite's ceiling is refused"
run --days 365 --scale 26280
assert "refuses the ceiling itself" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the ceiling" "$(grep -q 'scale must be below 26280' <<< "${ERR}" && echo pass)"
run --days 365 --scale 30000
assert "refuses above it" "$([[ ${RC} -eq 1 ]] && echo pass)"
run --days 365 --scale 100 --max-scale 50
assert "honours --max-scale" "$([[ ${RC} -eq 1 ]] && grep -q 'scale must be below 50' <<< "${ERR}" && echo pass)"
end_case

echo "case 8: a pair that converges before the rollout and its verification finish is refused"
# 1 day at 26279: 86400 / 26278 = 3.3 real seconds. Allowed by every other check, and the
# verifier would then fail the freshly recreated stack for having already converged.
run --days 1 --scale 26279
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the budget" "$(grep -q 'inside the 1h the rollout and its verification need' <<< "${ERR}" && echo pass)"
assert "says what to change" "$(grep -q 'Lower the scale or raise the days' <<< "${ERR}" && echo pass)"
assert "prints no anchors" "$([[ -z "${OUT}" ]] && echo pass)"
# 365 days at 26279 was accepted before the budget existed and closes in ~20 minutes.
run --days 365 --scale 26279
assert "refuses a year at the ceiling too (~0.3h)" "$([[ ${RC} -eq 1 ]] && echo pass)"
end_case

echo "case 9: a pair that converges on the budget or later is accepted"
# 365 days at 8760: 365 * 86400 / 8759 = 3600.4 s, just over the hour.
run --days 365 --scale 8760
assert "accepts the documented ~1h preset" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "reports ~1.0 hours" "$([[ "$(field hours_to_converge)" == "1.0" ]] && echo pass)"
# 1 day at 20: 86400 / 19 = 4547 s.
run --days 1 --scale 20
assert "accepts a one-day run at a slow scale" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "anchors one day back" "$([[ "$(field virtual_start)" == "2026-09-16T12:00:00Z" ]] && echo pass)"
run --days 1 --scale 26279 --min-converge-hours 0
assert "honours --min-converge-hours" "$([[ ${RC} -eq 0 ]] && echo pass)"
end_case

echo "case 10: usage errors"
set +e
bash "${SCRIPT}" --days 365 > /dev/null 2>&1; RC=$?
set -e
assert "missing --scale is a usage error" "$([[ ${RC} -eq 2 ]] && echo pass)"
set +e
bash "${SCRIPT}" --days 365 --scale 1460 --bogus 1 > /dev/null 2>&1; RC=$?
set -e
assert "an unknown flag is a usage error" "$([[ ${RC} -eq 2 ]] && echo pass)"
run --days 365 --scale 1460 --now not-an-epoch
assert "a malformed --now is refused" "$([[ ${RC} -eq 1 ]] && grep -q -- '--now must be seconds since the epoch' <<< "${ERR}" && echo pass)"
end_case

echo
if [[ "${FAILURES}" -eq 0 ]]; then
  echo "PASS: accelerated-anchors.sh generates and validates the run's anchors as specified"
else
  echo "FAIL: ${FAILURES} assertion(s) failed" >&2
  exit 1
fi
