#!/usr/bin/env bash
set -euo pipefail

# Self-test for the full deploy's RESET_DATABASES path in deploy-backend.sh.
#
# RESET_DATABASES=true is the alpha schema reset (docs/runbooks/flyway-baseline-reset.md,
# "Alpha Cutover") as a deploy option: drop every database named in init-databases.sql so the
# reconcile step recreates it and Flyway rebuilds each schema from its V1 baseline. It is
# destructive by design, so the contract is checked from both sides and nowhere but on the box
# would it otherwise be observable:
#
#   1. Off by default: a deploy without the variable never issues a DROP.
#   2. When on: the backend tier is stopped before the first DROP (a running service would
#      reconnect into the drop window), every managed database is dropped WITH (FORCE), every
#      one is recreated by the reconcile step that follows, and the deploy goes on to start
#      the service tiers.
#   3. Refused, before anything on the host is touched, on --config-only (that mode leaves
#      unchanged containers running, now on dropped databases) and on any value but the
#      literal `true`/`false` (a typo must not read as either answer).
#   4. Refused when init-databases.sql or init-tenancy.sh is missing: the script neither guesses
#      what to drop nor recreates databases the pos_app grants cannot follow. The backend images
#      are pulled before the first DROP, so a pull failure cannot strand a dropped host.
#
# Run: bash scripts/tests/deploy-backend-reset-databases-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0
CASE_FAILURES=0

make_stubs() {
  mkdir -p "${WORK}/bin"

  cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
# Stub docker. Every invocation is appended to $LOG. A tiny postgres lives in $STATE:
# `DROP DATABASE` records the name, `CREATE DATABASE` forgets it, and the existence probe
# (`SELECT 1 FROM pg_database`) answers 1 unless the name is recorded as dropped.
set -uo pipefail
echo "docker $*" >> "${LOG}"

case "${1:-}" in
  login)
    cat > /dev/null 2>&1 || true
    exit 0
    ;;
  inspect) exit 0 ;;
  compose) shift ;;
  *) exit 0 ;;
esac

while [[ "${1:-}" == "-f" || "${1:-}" == "--env-file" ]]; do shift 2; done
sub="${1:-}"; shift || true

case "${sub}" in
  config)
    printf 'services:\n  postgres:\n    image: timescale/timescaledb:2.17.2-pg16\n'
    exit 0
    ;;
  ps) exit 0 ;;
  exec)
    cmd="$*"
    if [[ "${cmd}" == *"DROP DATABASE IF EXISTS "* ]]; then
      name="${cmd#*DROP DATABASE IF EXISTS }"
      name="${name%% *}"
      echo "${name}" >> "${STATE}"
      echo "DROP DATABASE"
    elif [[ "${cmd}" == *"CREATE DATABASE "* ]]; then
      name="${cmd#*CREATE DATABASE }"
      name="${name%%;*}"
      grep -vx "${name}" "${STATE}" > "${STATE}.next" || true
      mv "${STATE}.next" "${STATE}"
      echo "CREATE DATABASE"
    elif [[ "${cmd}" == *"SELECT 1 FROM pg_database"* ]]; then
      name="${cmd#*datname = \'}"
      name="${name%%\'*}"
      grep -qx "${name}" "${STATE}" || echo 1
    fi
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

make_alpha_root() {
  local root="$1" with_init_sql="$2"
  rm -rf "${root}"
  mkdir -p "${root}/backend/postgres" "${root}/backend/observability"
  echo "services: {}" > "${root}/backend/docker-compose.yml"
  echo "services: {}" > "${root}/docker-compose.prod.yml"
  if [[ "${with_init_sql}" != "no" ]]; then
    printf 'CREATE DATABASE pos_order_db;\nCREATE DATABASE pos_mcp;\n' > "${root}/backend/postgres/init-databases.sql"
  fi
  if [[ "${with_init_sql}" != "no-tenancy" ]]; then
    printf '#!/bin/sh\nexit 0\n' > "${root}/backend/postgres/init-tenancy.sh"
  fi
  printf "      password: 'durion-local-prom-scrape-password'\n" > "${root}/backend/observability/prometheus.yml"
  cat > "${root}/.env" <<'ENVFILE'
BACKEND_TAG=sha-0000000
ECR_REGISTRY=288757602241.dkr.ecr.us-east-1.amazonaws.com
SUPPLIER_AUDIT_ENC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
POS_SECURITY_METRICS_SCRAPE_PASSWORD='stub-scrape-password'
SECURITY_SEED_ADMIN_PASSWORD_HASH='$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0'
ENVFILE
}

# run_case <RESET_DATABASES value or "unset"> <on-box postgres scripts: yes|no|no-tenancy> <script args...>
#   yes: init-databases.sql and init-tenancy.sh; no: neither; no-tenancy: init-databases.sql only
# Sets OUT / RC; the stub's invocation log is $WORK/compose.log.
#
# DOCKER_MIN_FREE_GIB=0 keeps the pre-pull reclaim (#1862) out of these cases — left at its
# default it measures the CI runner's real disk through an unstubbed `df`.
run_case() {
  local reset="$1" with_init_sql="$2"
  shift 2
  local root="${WORK}/alpha"
  make_alpha_root "${root}" "${with_init_sql}"
  : > "${WORK}/compose.log"
  : > "${WORK}/dropped.txt"
  local reset_env=()
  if [[ "${reset}" != "unset" ]]; then
    reset_env=("RESET_DATABASES=${reset}")
  fi
  set +e
  OUT="$(
    env "${reset_env[@]}" \
    PATH="${WORK}/bin:${PATH}" \
    LOG="${WORK}/compose.log" \
    STATE="${WORK}/dropped.txt" \
    ALPHA_ROOT="${root}" \
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
    echo "  --- docker invocations ---"
    sed 's/^/  | /' "${WORK}/compose.log"
    echo "  --- end ---"
  fi
  CASE_FAILURES=0
}

# First line number in the invocation log matching $1, or empty.
log_line() {
  # No match is a legitimate answer (an empty line), not a failure: under `set -e` and
  # `pipefail` a bare grep miss would end the self-test before the assertion could report it.
  { grep -n -m1 -- "$1" "${WORK}/compose.log" || true; } | cut -d: -f1
}

SHA=b30123cfeedfacedeadbeef0123456789abcdef0

make_stubs

echo "case 1: full deploy without RESET_DATABASES leaves the databases alone"
run_case unset yes "${SHA}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "issues no DROP DATABASE" "$(! grep -q 'DROP DATABASE' "${WORK}/compose.log" && echo pass)"
assert "does not stop the backend tier" "$(! grep -q 'compose .* stop ' "${WORK}/compose.log" && echo pass)"
assert "finds every database present" "$(! grep -q 'Creating missing database' <<< "${OUT}" && echo pass)"
assert "reached the domain tiers" "$(grep -q 'Starting domain services' <<< "${OUT}" && echo pass)"
end_case

echo "case 2: RESET_DATABASES=true drops, recreates and redeploys"
run_case true yes "${SHA}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "announces the reset" "$(grep -q 'RESET_DATABASES=true: dropping 2 backend databases' <<< "${OUT}" && echo pass)"
assert "drops pos_order_db WITH (FORCE)" "$(grep -q 'DROP DATABASE IF EXISTS pos_order_db WITH (FORCE)' "${WORK}/compose.log" && echo pass)"
assert "drops pos_mcp WITH (FORCE)" "$(grep -q 'DROP DATABASE IF EXISTS pos_mcp WITH (FORCE)' "${WORK}/compose.log" && echo pass)"
PULL_AT="$(log_line 'compose .* pull .*pos-workorder')"
STOP_AT="$(log_line 'compose .* stop ')"
FIRST_DROP_AT="$(log_line 'DROP DATABASE')"
LAST_DROP_AT="$(grep -n 'DROP DATABASE' "${WORK}/compose.log" | tail -n1 | cut -d: -f1)"
FIRST_CREATE_AT="$(log_line 'CREATE DATABASE')"
assert "stops the backend tier" "$([[ -n "${STOP_AT}" ]] && echo pass)"
assert "stops every backend service, event receiver included" "$(grep 'compose .* stop ' "${WORK}/compose.log" | grep -q 'pos-event-receiver .*pos-workorder' && echo pass)"
assert "stops before the first DROP" "$([[ -n "${STOP_AT}" && -n "${FIRST_DROP_AT}" && ${STOP_AT} -lt ${FIRST_DROP_AT} ]] && echo pass)"
assert "pulls the backend images before the first DROP" "$([[ -n "${PULL_AT}" && -n "${FIRST_DROP_AT}" && ${PULL_AT} -lt ${FIRST_DROP_AT} ]] && echo pass)"
assert "recreates pos_order_db" "$(grep -q 'Creating missing database: pos_order_db' <<< "${OUT}" && echo pass)"
assert "recreates pos_mcp" "$(grep -q 'Creating missing database: pos_mcp' <<< "${OUT}" && echo pass)"
assert "every DROP precedes the first CREATE" "$([[ -n "${LAST_DROP_AT}" && -n "${FIRST_CREATE_AT}" && ${LAST_DROP_AT} -lt ${FIRST_CREATE_AT} ]] && echo pass)"
assert "nothing left dropped" "$([[ ! -s "${WORK}/dropped.txt" ]] && echo pass)"
assert "reached the domain tiers" "$(grep -q 'Starting domain services' <<< "${OUT}" && echo pass)"
end_case

echo "case 3: RESET_DATABASES=true is refused on --config-only before anything runs"
run_case true yes --config-only
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the reason" "$(grep -q 'only valid for a full deploy' <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 4: a value other than true/false is refused before anything runs"
run_case yes yes "${SHA}"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the value" "$(grep -q "must be 'true' or 'false' (got 'yes')" <<< "${OUT}" && echo pass)"
assert "touched nothing" "$([[ ! -s "${WORK}/compose.log" ]] && echo pass)"
end_case

echo "case 5: RESET_DATABASES=true without init-databases.sql refuses to guess"
run_case true no "${SHA}"
assert "exits non-zero" "$([[ ${RC} -ne 0 ]] && echo pass)"
assert "names the reason" "$(grep -q 'refusing to guess which databases to drop' <<< "${OUT}" && echo pass)"
assert "issues no DROP DATABASE" "$(! grep -q 'DROP DATABASE' "${WORK}/compose.log" && echo pass)"
end_case

echo "case 6: RESET_DATABASES=true without init-tenancy.sh refuses before dropping anything"
run_case true no-tenancy "${SHA}"
assert "exits non-zero" "$([[ ${RC} -ne 0 ]] && echo pass)"
assert "names the reason" "$(grep -q 'no pos_app grants' <<< "${OUT}" && echo pass)"
assert "issues no DROP DATABASE" "$(! grep -q 'DROP DATABASE' "${WORK}/compose.log" && echo pass)"
assert "does not stop the backend tier" "$(! grep -q 'compose .* stop ' "${WORK}/compose.log" && echo pass)"
end_case

echo
if [[ ${FAILURES} -eq 0 ]]; then
  echo "PASS: deploy-backend.sh RESET_DATABASES behaves as specified"
else
  echo "FAIL: ${FAILURES} assertion(s) failed"
  exit 1
fi
