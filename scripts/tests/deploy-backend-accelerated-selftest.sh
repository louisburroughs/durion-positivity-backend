#!/usr/bin/env bash
set -euo pipefail

# Self-test for the accelerated-clock path in deploy-backend.sh (#2065).
#
# ACCELERATED=true layers deployment/alpha/docker-compose.accelerated.yml onto the deploy,
# putting every POS JVM on the `accelerated` profile with its clock anchored a year in the
# past, so the SDK repo's suite can drive a year of shop activity in a few real hours. The
# stakes make both halves worth pinning, and neither is observable anywhere but on the box:
#
#   1. Off by default: an ordinary deploy is byte-for-byte the deploy it was before, and is
#      also the TEARDOWN — it strips the marker and the anchors from the on-box env file, so
#      the stack comes back on the wall clock and GET /system/time returns 404 again. A
#      teardown that silently did nothing would block every non-accelerated integration run,
#      whose guard aborts on a 200.
#   2. When on: the override is applied and checksum-verified exactly like the prod one, and
#      the anchors are persisted into the env file so a later config-only sync cannot drop
#      the stack back to wall time halfway through a run.
#   3. Refused, before anything on the host is touched: a missing anchor, a malformed one, a
#      gap shorter than 360 days, a scale that cannot converge, a value other than
#      true/false, --config-only, and an override file that is missing or stale. Every one of
#      these is cheaper to catch here than after a 25-service rollout.
#   4. A config-only sync INHERITS the box's state: it applies the override when the box is
#      accelerated, leaves the anchors alone, and cannot be asked to start or end a run.
#
# Run: bash scripts/tests/deploy-backend-accelerated-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0
CASE_FAILURES=0

SHA=b30123cfeedfacedeadbeef0123456789abcdef0

# A one-year gap, well clear of the 360-day floor.
REAL_START="2026-09-17T12:00:00Z"
VIRTUAL_START="2025-09-17T12:00:00Z"

make_stubs() {
  mkdir -p "${WORK}/bin"

  # Enough docker to get the script from end to end: every invocation is logged, `config`
  # answers with a postgres image so the reconciliation pipeline parses, and the database
  # probe reports every database present so nothing is created.
  cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
echo "docker $*" >> "${LOG}"

case "${1:-}" in
  login) cat > /dev/null 2>&1 || true; exit 0 ;;
  inspect) exit 0 ;;
  compose) shift ;;
  *) exit 0 ;;
esac

while [[ "${1:-}" == "-f" || "${1:-}" == "--env-file" ]]; do shift 2; done
sub="${1:-}"; shift || true

case "${sub}" in
  config) printf 'services:\n  postgres:\n    image: timescale/timescaledb:2.17.2-pg16\n'; exit 0 ;;
  exec)
    [[ "$*" == *"SELECT 1 FROM pg_database"* ]] && echo 1
    exit 0
    ;;
  *) exit 0 ;;
esac
STUB

  cat > "${WORK}/bin/aws" <<'STUB'
#!/usr/bin/env bash
echo "stub-account"
STUB

  chmod +x "${WORK}/bin/docker" "${WORK}/bin/aws"
}

# make_alpha_root <accelerated-marker: none|true> [override: present|absent]
make_alpha_root() {
  local marker="${1:-none}" override="${2:-present}"
  local root="${WORK}/alpha"
  rm -rf "${root}"
  mkdir -p "${root}/backend/postgres" "${root}/backend/observability"
  echo "services: {}" > "${root}/backend/docker-compose.yml"
  echo "services: {}" > "${root}/docker-compose.prod.yml"
  if [[ "${override}" == "present" ]]; then
    echo "services: {}" > "${root}/docker-compose.accelerated.yml"
  fi
  printf 'CREATE DATABASE pos_order_db;\n' > "${root}/backend/postgres/init-databases.sql"
  printf '#!/bin/sh\nexit 0\n' > "${root}/backend/postgres/init-tenancy.sh"
  printf "      password: 'durion-local-prom-scrape-password'\n" > "${root}/backend/observability/prometheus.yml"
  cat > "${root}/.env" <<'ENVFILE'
BACKEND_TAG=sha-0000000
ECR_REGISTRY=288757602241.dkr.ecr.us-east-1.amazonaws.com
SUPPLIER_AUDIT_ENC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
POS_SECURITY_METRICS_SCRAPE_PASSWORD='stub-scrape-password'
SECURITY_SEED_ADMIN_PASSWORD_HASH='$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0'
ENVFILE
  if [[ "${marker}" == "true" ]]; then
    # What a previous accelerated deploy leaves behind.
    cat >> "${root}/.env" <<ENVFILE
POS_ACCELERATED=true
POS_TIME_ACCELERATED_SCALE=1460
POS_TIME_ACCELERATED_ZONE=UTC
POS_TIME_ACCELERATED_CONVERGE=true
POS_TIME_ACCELERATED_REAL_START=${REAL_START}
POS_TIME_ACCELERATED_VIRTUAL_START=${VIRTUAL_START}
ENVFILE
  fi
}

# run_case <env assignments as NAME=VALUE, or "-"> <script args...>
# Sets OUT / RC; the stub's invocation log is $WORK/compose.log and the env file is
# $WORK/alpha/.env. Call make_alpha_root first.
#
# DOCKER_MIN_FREE_GIB=0 keeps the pre-pull reclaim (#1862) out of these cases — left at its
# default it measures the CI runner's real disk through an unstubbed `df`.
run_case() {
  local -a extra_env=()
  while [[ "${1:-}" != "--" ]]; do
    [[ "$1" != "-" ]] && extra_env+=("$1")
    shift
  done
  shift
  : > "${WORK}/compose.log"
  set +e
  OUT="$(
    env "${extra_env[@]}" \
    PATH="${WORK}/bin:${PATH}" \
    LOG="${WORK}/compose.log" \
    ALPHA_ROOT="${WORK}/alpha" \
    DOCKER_MIN_FREE_GIB=0 \
    SECURITY_SEED_ADMIN_PASSWORD_HASH='$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0' \
    bash "${SCRIPT}" "$@" 2>&1
  )"
  RC=$?
  set -e
}

assert() {
  local label="$1" condition="$2"
  if [[ "${condition}" == "pass" ]]; then
    echo "  ok   ${label}"
  else
    echo "  FAIL ${label}"
    FAILURES=$((FAILURES + 1))
    CASE_FAILURES=$((CASE_FAILURES + 1))
  fi
}

end_case() {
  if [[ ${CASE_FAILURES} -gt 0 ]]; then
    echo "  --- deploy-backend.sh exited ${RC}; output follows ---"
    sed 's/^/  | /' <<< "${OUT}"
    echo "  --- env file ---"
    sed 's/^/  | /' "${WORK}/alpha/.env" 2>/dev/null || true
    echo "  --- docker invocations ---"
    sed 's/^/  | /' "${WORK}/compose.log" 2>/dev/null || true
    echo "  --- end ---"
  fi
  CASE_FAILURES=0
}

# True when every `docker compose` invocation that took compose files included the
# accelerated override. Checking EVERY one matters: an override applied to the tier starts
# but not to the pull, or vice versa, is a stack whose containers disagree about the clock.
every_compose_call_is_accelerated() {
  local total accelerated
  total="$(grep -c 'docker compose -f' "${WORK}/compose.log" || true)"
  accelerated="$(grep 'docker compose -f' "${WORK}/compose.log" | grep -c 'docker-compose.accelerated.yml' || true)"
  [[ "${total}" -gt 0 && "${total}" -eq "${accelerated}" ]]
}

no_compose_call_is_accelerated() {
  ! grep -q 'docker-compose.accelerated.yml' "${WORK}/compose.log"
}

env_has() {
  grep -qx "$1" "${WORK}/alpha/.env"
}

ACCEL_ENV=(
  ACCELERATED=true
  "POS_TIME_ACCELERATED_REAL_START=${REAL_START}"
  "POS_TIME_ACCELERATED_VIRTUAL_START=${VIRTUAL_START}"
)

make_stubs

echo "case 1: an ordinary full deploy is unchanged and applies no override"
make_alpha_root none
run_case - -- "${SHA}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "applies no accelerated override" "$(no_compose_call_is_accelerated && echo pass)"
assert "writes no anchors to the env file" "$(! grep -q 'POS_TIME_ACCELERATED' "${WORK}/alpha/.env" && echo pass)"
assert "says nothing about the clock" "$(! grep -qi 'accelerated' <<< "${OUT}" && echo pass)"
assert "reached the domain tiers" "$(grep -q 'Starting domain services' <<< "${OUT}" && echo pass)"
end_case

echo "case 2: ACCELERATED=true applies the override and persists the run's anchors"
make_alpha_root none
run_case "${ACCEL_ENV[@]}" POS_TIME_ACCELERATED_SCALE=2920 -- "${SHA}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "applies the override to every compose call" "$(every_compose_call_is_accelerated && echo pass)"
assert "applies it after the prod override" "$(grep -m1 'docker compose -f' "${WORK}/compose.log" | grep -q 'docker-compose.prod.yml .*docker-compose.accelerated.yml' && echo pass)"
assert "marks the box accelerated" "$(env_has 'POS_ACCELERATED=true' && echo pass)"
assert "persists real-start" "$(env_has "POS_TIME_ACCELERATED_REAL_START=${REAL_START}" && echo pass)"
assert "persists virtual-start" "$(env_has "POS_TIME_ACCELERATED_VIRTUAL_START=${VIRTUAL_START}" && echo pass)"
assert "persists the dispatched scale" "$(env_has 'POS_TIME_ACCELERATED_SCALE=2920' && echo pass)"
assert "persists the zone and convergence defaults" "$(env_has 'POS_TIME_ACCELERATED_ZONE=UTC' && env_has 'POS_TIME_ACCELERATED_CONVERGE=true' && echo pass)"
assert "logs the run's identity" "$(grep -q "real-start:    ${REAL_START}" <<< "${OUT}" && grep -q "virtual-start: ${VIRTUAL_START}" <<< "${OUT}" && echo pass)"
assert "logs the gap in days" "$(grep -q '(365 days earlier)' <<< "${OUT}" && echo pass)"
assert "logs when it converges" "$(grep -q 'converges on wall time after' <<< "${OUT}" && echo pass)"
assert "reached the domain tiers" "$(grep -q 'Starting domain services' <<< "${OUT}" && echo pass)"
end_case

echo "case 3: an ordinary full deploy tears an accelerated stack down"
make_alpha_root true
run_case - -- "${SHA}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "applies no accelerated override" "$(no_compose_call_is_accelerated && echo pass)"
assert "clears the marker" "$(! grep -q '^POS_ACCELERATED=' "${WORK}/alpha/.env" && echo pass)"
assert "clears every anchor" "$(! grep -q 'POS_TIME_ACCELERATED' "${WORK}/alpha/.env" && echo pass)"
assert "says the wall clock is restored" "$(grep -q 'GET /system/time returns 404' <<< "${OUT}" && echo pass)"
assert "leaves the rest of the env file alone" "$(grep -q '^BACKEND_TAG=' "${WORK}/alpha/.env" && grep -q '^ECR_REGISTRY=' "${WORK}/alpha/.env" && echo pass)"
end_case

echo "case 4: a config-only sync inherits an accelerated box without re-anchoring it"
make_alpha_root true
run_case - -- --config-only
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "applies the override to every compose call" "$(every_compose_call_is_accelerated && echo pass)"
assert "keeps the marker" "$(env_has 'POS_ACCELERATED=true' && echo pass)"
assert "keeps the anchors byte-for-byte" "$(env_has "POS_TIME_ACCELERATED_REAL_START=${REAL_START}" && env_has "POS_TIME_ACCELERATED_VIRTUAL_START=${VIRTUAL_START}" && echo pass)"
assert "says it is keeping the clock" "$(grep -q 'keeping the clock anchored' <<< "${OUT}" && echo pass)"
end_case

echo "case 5: a config-only sync on an ordinary box stays ordinary"
make_alpha_root none
run_case - -- --config-only
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "applies no accelerated override" "$(no_compose_call_is_accelerated && echo pass)"
end_case

echo "case 6: ACCELERATED=true is refused on --config-only before anything runs"
make_alpha_root none
run_case "${ACCEL_ENV[@]}" -- --config-only
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the reason" "$(grep -q 'only valid for a full deploy' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 7: a value other than true/false is refused before anything runs"
make_alpha_root none
run_case ACCELERATED=yes -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the value" "$(grep -q "ACCELERATED must be 'true' or 'false' (got 'yes')" <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 8: a missing anchor is refused, with the command to generate the pair"
make_alpha_root none
run_case ACCELERATED=true "POS_TIME_ACCELERATED_REAL_START=${REAL_START}" -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names both anchors" "$(grep -q 'requires POS_TIME_ACCELERATED_REAL_START and' <<< "${OUT}" && echo pass)"
assert "shows how to generate them" "$(grep -q "date -u -d '1 year ago'" <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
assert "did not retag the deploy" "$(grep -q '^BACKEND_TAG=sha-0000000$' "${WORK}/alpha/.env" && echo pass)"
end_case

echo "case 9: a gap shorter than 360 days is refused before the rollout"
make_alpha_root none
run_case ACCELERATED=true "POS_TIME_ACCELERATED_REAL_START=${REAL_START}" \
  POS_TIME_ACCELERATED_VIRTUAL_START=2026-06-17T12:00:00Z -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the floor" "$(grep -q 'at least 360 days before' <<< "${OUT}" && echo pass)"
assert "reports the actual gap" "$(grep -q '92 day(s) apart' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 10: a virtual start AFTER the real one is refused (a negative gap)"
make_alpha_root none
run_case ACCELERATED=true "POS_TIME_ACCELERATED_REAL_START=${VIRTUAL_START}" \
  "POS_TIME_ACCELERATED_VIRTUAL_START=${REAL_START}" -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the floor" "$(grep -q 'at least 360 days before' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 11: a malformed anchor is refused by name"
make_alpha_root none
run_case ACCELERATED=true POS_TIME_ACCELERATED_REAL_START=2026-09-17 \
  "POS_TIME_ACCELERATED_VIRTUAL_START=${VIRTUAL_START}" -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the variable and the value" "$(grep -q "POS_TIME_ACCELERATED_REAL_START must be an ISO-8601 UTC instant like 2026-09-17T12:00:00Z (got '2026-09-17')" <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 12: a scale that cannot close the gap is refused"
make_alpha_root none
run_case "${ACCEL_ENV[@]}" POS_TIME_ACCELERATED_SCALE=1 -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names convergence as the reason" "$(grep -q 'greater than 1 when convergence is enabled' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 13: a non-numeric scale is refused"
make_alpha_root none
run_case "${ACCEL_ENV[@]}" POS_TIME_ACCELERATED_SCALE=fast -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the value" "$(grep -q "must be a positive number (got 'fast')" <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 14: an override file that is not on the box is refused"
make_alpha_root none absent
run_case "${ACCEL_ENV[@]}" -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the missing file" "$(grep -q 'docker-compose.accelerated.yml is not on this box' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 15: a stale on-box override is refused by the checksum guard"
make_alpha_root none
run_case "${ACCEL_ENV[@]}" \
  ACCELERATED_OVERRIDE_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
  -- "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the file" "$(grep -q 'on-box accelerated override' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 16: a matching checksum passes the guard"
make_alpha_root none
run_case "${ACCEL_ENV[@]}" \
  "ACCELERATED_OVERRIDE_SHA256=$(sha256sum "${WORK}/alpha/docker-compose.accelerated.yml" | awk '{print $1}')" \
  -- "${SHA}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "reports the match" "$(grep -q 'accelerated override matches committed sha256' <<< "${OUT}" && echo pass)"
assert "applies the override to every compose call" "$(every_compose_call_is_accelerated && echo pass)"
end_case

echo "case 17: a config-only sync on a box marked accelerated but missing an anchor aborts"
make_alpha_root true
sed -i '/^POS_TIME_ACCELERATED_VIRTUAL_START=/d' "${WORK}/alpha/.env"
run_case - -- --config-only
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the missing anchor" "$(grep -q 'POS_TIME_ACCELERATED_VIRTUAL_START is missing from it' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo
if [[ ${FAILURES} -eq 0 ]]; then
  echo "PASS: deploy-backend.sh's accelerated path behaves as specified"
else
  echo "FAIL: ${FAILURES} assertion(s) failed"
  exit 1
fi
