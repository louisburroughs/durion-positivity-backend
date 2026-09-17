#!/usr/bin/env bash
set -euo pipefail

# Post-deploy verification for an accelerated alpha stack (#2065).
#
# Run on the box immediately after deploy-backend.sh finishes an ACCELERATED=true deploy —
# deploy-alpha-accelerated.yml does exactly that over SSM, and an operator can run it by hand
# at any point during a run:
#
#   EXPECTED_REAL_START=2026-09-17T12:00:00Z \
#   EXPECTED_VIRTUAL_START=2025-09-17T12:00:00Z \
#   EXPECTED_SCALE=1460 \
#   bash /opt/durion/alpha/scripts/verify-accelerated-deployment.sh
#
# Three questions, because a stack can fail this in three independent ways:
#
#   1. Did EVERY POS JVM get the profile and the SAME anchors? The gateway answering
#      correctly says nothing about the other 24 — and one service left on the wall clock
#      writes records a year ahead of everything else in the same database. Checked from the
#      container environment (`docker inspect`), which is per-JVM ground truth, against the
#      service list in docker-compose.accelerated.yml so the list cannot drift out of this
#      check.
#   2. Is the clock live and anchored where it was asked to be? GET /system/time.
#   3. Is virtual time actually moving at ~scale? Two samples, one interval apart. A clock
#      that reports the right anchors but does not advance would seed a year of records all
#      bearing the same timestamp.
#
# Exits non-zero on the first question that answers wrong, having named the service or the
# field. Nothing is mutated; safe to re-run mid-run.

ALPHA_ROOT="${ALPHA_ROOT:-/opt/durion/alpha}"
ACCELERATED_OVERRIDE="${ACCELERATED_OVERRIDE:-${ALPHA_ROOT}/docker-compose.accelerated.yml}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
# Real seconds between the two clock samples. Long enough that the rate is not dominated by
# request latency, short enough not to stretch the deploy.
RATE_SAMPLE_SECONDS="${RATE_SAMPLE_SECONDS:-10}"
# How far the measured rate may sit from the configured scale. Generous on purpose: the two
# samples are ordinary HTTP requests whose latency lands in the measurement, and this is a
# check for "advancing at roughly scale", not a benchmark.
RATE_TOLERANCE="${RATE_TOLERANCE:-0.25}"

EXPECTED_REAL_START="${EXPECTED_REAL_START:?EXPECTED_REAL_START is required}"
EXPECTED_VIRTUAL_START="${EXPECTED_VIRTUAL_START:?EXPECTED_VIRTUAL_START is required}"
EXPECTED_SCALE="${EXPECTED_SCALE:?EXPECTED_SCALE is required}"

failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# The services the override puts on the accelerated clock, read from the file itself so a
# service added there is checked here without touching this script. Top-level keys of the
# `services:` block, same parser shape as scripts/check-deploy-service-drift.sh.
accelerated_services() {
  awk '
    /^[A-Za-z_][A-Za-z0-9_-]*:/ { in_services = ($0 ~ /^services:[[:space:]]*$/); next }
    in_services && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      sub(/:[[:space:]]*$/, ""); sub(/^  /, ""); print
    }
  ' "${ACCELERATED_OVERRIDE}" | sort -u
}

# One environment value out of a running container, or empty if the container or the key is
# absent. `docker inspect` reads what the JVM was actually started with, which is the only
# thing that settles whether a service is on the accelerated clock.
container_env() {
  local service="$1" key="$2" cid
  cid="$(docker ps -q --filter "label=com.docker.compose.service=${service}" | head -n1)"
  if [[ -z "${cid}" ]]; then
    return 0
  fi
  docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" "${cid}" \
    | sed -n "s/^${key}=//p" | head -n1
}

# ── 1. Every POS JVM ─────────────────────────────────────────────────────────

if [[ ! -f "${ACCELERATED_OVERRIDE}" ]]; then
  echo "ERROR: ${ACCELERATED_OVERRIDE} not found; nothing to verify against." >&2
  exit 1
fi

mapfile -t SERVICES < <(accelerated_services)
if [[ "${#SERVICES[@]}" -eq 0 ]]; then
  echo "ERROR: parsed no services out of ${ACCELERATED_OVERRIDE}." >&2
  exit 1
fi

echo "Checking ${#SERVICES[@]} services carry the profile and the run's anchors"
for service in "${SERVICES[@]}"; do
  cid="$(docker ps -q --filter "label=com.docker.compose.service=${service}" | head -n1)"
  if [[ -z "${cid}" ]]; then
    fail "${service}: no running container"
    continue
  fi

  profiles="$(container_env "${service}" SPRING_PROFILES_INCLUDE)"
  real="$(container_env "${service}" POS_TIME_ACCELERATED_REAL_START)"
  virtual="$(container_env "${service}" POS_TIME_ACCELERATED_VIRTUAL_START)"
  scale="$(container_env "${service}" POS_TIME_ACCELERATED_SCALE)"

  service_ok=1
  [[ "${profiles}" == *accelerated* ]] || { fail "${service}: SPRING_PROFILES_INCLUDE is '${profiles}', expected to include 'accelerated'"; service_ok=0; }
  [[ "${real}" == "${EXPECTED_REAL_START}" ]] || { fail "${service}: real-start is '${real}', expected '${EXPECTED_REAL_START}'"; service_ok=0; }
  [[ "${virtual}" == "${EXPECTED_VIRTUAL_START}" ]] || { fail "${service}: virtual-start is '${virtual}', expected '${EXPECTED_VIRTUAL_START}'"; service_ok=0; }
  awk -v a="${scale}" -v b="${EXPECTED_SCALE}" 'BEGIN { exit !(a + 0 == b + 0) }' \
    || { fail "${service}: scale is '${scale}', expected '${EXPECTED_SCALE}'"; service_ok=0; }

  [[ "${service_ok}" -eq 1 ]] && echo "  ok   ${service}"
done

# ── 2. The live clock ────────────────────────────────────────────────────────

# Reads one field out of a GET /system/time response without needing jq on the box.
time_field() {
  sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^,\"}]*\)\"\{0,1\}.*/\1/p" <<< "$2"
}

echo
echo "Checking the live clock at ${GATEWAY_URL}/system/time"
if ! FIRST="$(curl -fsS --max-time 15 "${GATEWAY_URL}/system/time")"; then
  fail "GET ${GATEWAY_URL}/system/time did not answer; the gateway is not on the accelerated profile"
  echo
  echo "FAIL: ${failures} check(s) failed" >&2
  exit 1
fi
echo "  ${FIRST}"

[[ "$(time_field accelerated "${FIRST}")" == "true" ]] \
  || fail "/system/time reports accelerated=$(time_field accelerated "${FIRST}"), expected true"
[[ "$(time_field realStart "${FIRST}")" == "${EXPECTED_REAL_START}" ]] \
  || fail "/system/time reports realStart=$(time_field realStart "${FIRST}"), expected ${EXPECTED_REAL_START}"
[[ "$(time_field virtualStart "${FIRST}")" == "${EXPECTED_VIRTUAL_START}" ]] \
  || fail "/system/time reports virtualStart=$(time_field virtualStart "${FIRST}"), expected ${EXPECTED_VIRTUAL_START}"
awk -v a="$(time_field scale "${FIRST}")" -v b="${EXPECTED_SCALE}" 'BEGIN { exit !(a + 0 == b + 0) }' \
  || fail "/system/time reports scale=$(time_field scale "${FIRST}"), expected ${EXPECTED_SCALE}"

CONVERGED="$(time_field converged "${FIRST}")"
if [[ "${CONVERGED}" == "true" ]]; then
  # Not a defect by itself, but it means the gap is already closed and the stack is back on
  # ordinary wall time — there is no accelerated year left to seed.
  fail "/system/time reports converged=true right after the deploy: virtual time has already caught up, so this stack has no back-dated window left"
fi

# ── 3. Virtual time is moving ────────────────────────────────────────────────

echo
echo "Sampling the clock ${RATE_SAMPLE_SECONDS}s apart to confirm it advances at ~${EXPECTED_SCALE}x"
# A fresh pair rather than reusing the response above: the checks in between cost real time
# that the wall-clock reading would not include, which inflates the measured rate.
if ! EARLY="$(curl -fsS --max-time 15 "${GATEWAY_URL}/system/time")"; then
  fail "the rate sample's first GET ${GATEWAY_URL}/system/time did not answer"
  EARLY=""
fi
REAL_BEFORE="$(date -u +%s)"
sleep "${RATE_SAMPLE_SECONDS}"
if [[ -z "${EARLY}" ]]; then
  : # already reported
elif ! SECOND="$(curl -fsS --max-time 15 "${GATEWAY_URL}/system/time")"; then
  fail "the rate sample's second GET ${GATEWAY_URL}/system/time did not answer"
else
  REAL_AFTER="$(date -u +%s)"
  VIRTUAL_BEFORE="$(date -u -d "$(time_field virtualTime "${EARLY}")" +%s)"
  VIRTUAL_AFTER="$(date -u -d "$(time_field virtualTime "${SECOND}")" +%s)"
  REAL_DELTA=$((REAL_AFTER - REAL_BEFORE))
  VIRTUAL_DELTA=$((VIRTUAL_AFTER - VIRTUAL_BEFORE))
  if [[ "${REAL_DELTA}" -le 0 ]]; then
    fail "wall time did not advance between samples; cannot measure the rate"
  elif [[ "$(time_field converged "${SECOND}")" == "true" ]]; then
    fail "the clock converged during the sample window: the accelerated window is over"
  else
    MEASURED="$(awk -v v="${VIRTUAL_DELTA}" -v r="${REAL_DELTA}" 'BEGIN { printf "%.1f", v / r }')"
    echo "  ${VIRTUAL_DELTA} virtual seconds in ${REAL_DELTA} real seconds = ${MEASURED}x"
    awk -v m="${MEASURED}" -v s="${EXPECTED_SCALE}" -v t="${RATE_TOLERANCE}" \
      'BEGIN { exit !(m >= s * (1 - t) && m <= s * (1 + t)) }' \
      || fail "virtual time advanced at ${MEASURED}x, outside ${RATE_TOLERANCE} of the configured ${EXPECTED_SCALE}x"
  fi
fi

echo
if [[ "${failures}" -eq 0 ]]; then
  echo "PASS: ${#SERVICES[@]} services share the run's anchors and the clock is advancing at ~${EXPECTED_SCALE}x"
  exit 0
fi
echo "FAIL: ${failures} check(s) failed" >&2
exit 1
