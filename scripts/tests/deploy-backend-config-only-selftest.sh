#!/usr/bin/env bash
set -euo pipefail

# Self-test for deploy-backend.sh --config-only's pre-flight image check (#1646).
#
# The check decides what a config-only sync does when an image cannot be resolved at the
# BACKEND_TAG the alpha box is pinned to, and that decision is only observable on the box
# itself — so this drives the real script against a stubbed `docker`/`aws` and a throwaway
# ALPHA_ROOT, and asserts on the compose commands it issues.
#
# Cases:
#   1. every image resolves                    -> all tiers applied, exit 0
#   2. a service missing with no container     -> skipped, every other tier applied, exit 0
#   3. a service missing WITH a container      -> hard failure, nothing applied
#
# Run: bash scripts/tests/deploy-backend-config-only-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0

make_stubs() {
  mkdir -p "${WORK}/bin"

  cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
# Stub docker. MISSING_IMAGES / EXISTING_CONTAINERS (space-separated service names) drive
# the two facts the pre-flight check reads; every compose invocation is appended to $LOG.
set -uo pipefail
echo "docker $*" >> "${LOG}"

case "${1:-}" in
  login)
    # Real `docker login --password-stdin` reads the token; a stub that exits without
    # draining it SIGPIPEs the `aws ecr get-login-password` upstream, which under the
    # script's `set -o pipefail` aborts the deploy.
    cat > /dev/null 2>&1 || true
    exit 0
    ;;
  system) exit 0 ;;
  inspect) exit 0 ;;
  compose) shift ;;
  *) exit 0 ;;
esac

while [[ "${1:-}" == "-f" || "${1:-}" == "--env-file" ]]; do shift 2; done
sub="${1:-}"; shift || true

case "${sub}" in
  pull)
    for arg in "$@"; do
      [[ "${arg}" == -* ]] && continue
      for missing in ${MISSING_IMAGES:-}; do
        if [[ "${arg}" == "${missing}" ]]; then
          echo "manifest for ${arg} not found: manifest unknown" >&2
          exit 1
        fi
      done
    done
    exit 0
    ;;
  ps)
    svc=""
    for arg in "$@"; do
      [[ "${arg}" == -* ]] || svc="${arg}"
    done
    if [[ -n "${svc}" ]]; then
      for existing in ${EXISTING_CONTAINERS:-}; do
        if [[ "${svc}" == "${existing}" ]]; then
          echo "container-${svc}"
          exit 0
        fi
      done
    fi
    exit 0
    ;;
  up)
    printf 'UP:'
    for arg in "$@"; do
      [[ "${arg}" == -* || "${arg}" =~ ^[0-9]+$ ]] && continue
      printf ' %s' "${arg}"
    done
    printf '\n'
    exit 0
    ;;
  exec)
    if [[ "$*" == *"SELECT 1 FROM pg_database"* ]]; then echo 1; fi
    exit 0
    ;;
  *) exit 0 ;;
esac
STUB

  cat > "${WORK}/bin/aws" <<'STUB'
#!/usr/bin/env bash
echo "stub-ecr-token"
STUB

  chmod +x "${WORK}/bin/docker" "${WORK}/bin/aws"
}

make_alpha_root() {
  local root="$1"
  rm -rf "${root}"
  mkdir -p "${root}/backend/postgres"
  # The compose files are never parsed by the stub; they only have to exist.
  echo "services: {}" > "${root}/backend/docker-compose.yml"
  echo "services: {}" > "${root}/docker-compose.prod.yml"
  printf 'CREATE DATABASE pos_order_db;\n' > "${root}/backend/postgres/init-databases.sql"
  mkdir -p "${root}/backend/observability"
  printf "      password: 'durion-local-prom-scrape-password'\n" > "${root}/backend/observability/prometheus.yml"
  cat > "${root}/.env" <<'ENVFILE'
BACKEND_TAG=sha-b30123c
ECR_REGISTRY=288757602241.dkr.ecr.us-east-1.amazonaws.com
SECURITY_SEED_ADMIN_PASSWORD_HASH='$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012'
SUPPLIER_AUDIT_ENC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
POS_SECURITY_METRICS_SCRAPE_PASSWORD='stub-scrape-password'
ENVFILE
}

# run_case <name> <missing images> <existing containers>; sets OUT / RC.
run_case() {
  local root="${WORK}/alpha"
  make_alpha_root "${root}"
  : > "${WORK}/compose.log"
  set +e
  OUT="$(
    PATH="${WORK}/bin:${PATH}" \
    LOG="${WORK}/compose.log" \
    MISSING_IMAGES="$2" \
    EXISTING_CONTAINERS="$3" \
    ALPHA_ROOT="${root}" \
    bash "${SCRIPT}" --config-only 2>&1
  )"
  RC=$?
  set -e
}

CASE_FAILURES=0

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

# A failing assertion is unreadable without what the script actually printed — a stub that
# did not run at all and a genuine behaviour change look identical from the assertions.
end_case() {
  if [[ ${CASE_FAILURES} -gt 0 ]]; then
    echo "  --- deploy-backend.sh --config-only exited ${RC}; output follows ---"
    sed 's/^/  | /' <<< "${OUT}"
    echo "  --- end of output ---"
  fi
  CASE_FAILURES=0
}

started_services() {
  grep '^UP:' <<< "${OUT}" | tr ' ' '\n' | grep -v '^UP:$' | sort -u
}

echo "case 1: every image resolves"
make_stubs
run_case "all-present" "" "pos-order pos-catalog"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "starts pos-reference-mock" "$(started_services | grep -qx 'pos-reference-mock' && echo pass)"
assert "starts pos-catalog" "$(started_services | grep -qx 'pos-catalog' && echo pass)"
assert "starts eureka-server" "$(started_services | grep -qx 'eureka-server' && echo pass)"
assert "no skip warning" "$(! grep -q 'WARNING: skipping' <<< "${OUT}" && echo pass)"
end_case

echo "case 2: a new service has no image and no container"
run_case "new-service" "pos-reference-mock" "pos-order pos-catalog eureka-server"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "warns about the skip" "$(grep -q 'WARNING: skipping' <<< "${OUT}" && echo pass)"
assert "names the skipped service" "$(grep -qE '^ +pos-reference-mock$' <<< "${OUT}" && echo pass)"
assert "does NOT start pos-reference-mock" "$(! started_services | grep -qx 'pos-reference-mock' && echo pass)"
assert "still starts pos-catalog" "$(started_services | grep -qx 'pos-catalog' && echo pass)"
assert "still starts eureka-server" "$(started_services | grep -qx 'eureka-server' && echo pass)"
assert "still starts the last domain batch" "$(started_services | grep -qx 'pos-workorder' && echo pass)"
end_case

echo "case 3: a service on the box has no image"
run_case "broken-tag" "pos-order" "pos-order pos-catalog"
assert "exits 1" "$([[ ${RC} -eq 1 ]] && echo pass)"
assert "names the undeployable service" "$(grep -qE '^ +pos-order$' <<< "${OUT}" && echo pass)"
assert "says nothing changed" "$(grep -q 'Nothing has been changed on this host' <<< "${OUT}" && echo pass)"
assert "started no service" "$(! grep -q '^UP:' <<< "${OUT}" && echo pass)"
end_case

echo
if [[ ${FAILURES} -eq 0 ]]; then
  echo "PASS: deploy-backend.sh --config-only image pre-flight behaves as specified"
else
  echo "FAIL: ${FAILURES} assertion(s) failed"
  exit 1
fi
