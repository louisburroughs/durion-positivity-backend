#!/usr/bin/env bash
set -euo pipefail

# Self-test for the full deploy's postgres image reconciliation in deploy-backend.sh.
#
# The step reads the desired postgres image out of `docker compose config` through a
# sed/awk pipeline and compares it with the image the running container was created from.
# Two things have to hold, and neither is observable anywhere but on the box:
#
#   1. The pipeline must not abort the deploy. An awk that exits at the first match closes
#      the pipe under a `sed` that is still writing the rest of the postgres block; `sed`
#      dies of SIGPIPE, `set -o pipefail` reports 141 for the pipeline and `set -e` ends
#      the deploy right there — no message, nothing started, and only when the write
#      happened to land after awk was gone (run 34336034228). The stub below emits a
#      postgres block far larger than a pipe buffer so the race resolves the same way on
#      every run instead of once in a while.
#   2. The image it resolves must drive the decision: reconcile when the running container
#      is on a different image, leave postgres alone when it already matches.
#
# Run: bash scripts/tests/deploy-backend-postgres-image-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

DESIRED_IMAGE="timescale/timescaledb:2.17.2-pg16"
FAILURES=0

make_stubs() {
  mkdir -p "${WORK}/bin"

  cat > "${WORK}/bin/docker" <<'STUB'
#!/usr/bin/env bash
# Stub docker. DESIRED_IMAGE is what `compose config` advertises for postgres;
# CURRENT_IMAGE is what the running container reports (empty = no container).
# Every invocation is appended to $LOG.
set -uo pipefail
echo "docker $*" >> "${LOG}"

case "${1:-}" in
  login)
    # Drain the token, or `aws ecr get-login-password` upstream takes a SIGPIPE that
    # pipefail turns into a failed deploy.
    cat > /dev/null 2>&1 || true
    exit 0
    ;;
  inspect)
    printf '%s\n' "${CURRENT_IMAGE:-}"
    exit 0
    ;;
  compose) shift ;;
  *) exit 0 ;;
esac

while [[ "${1:-}" == "-f" || "${1:-}" == "--env-file" ]]; do shift 2; done
sub="${1:-}"; shift || true

case "${sub}" in
  config)
    # A merged compose config, postgres first. The filler keys after `image:` are what
    # makes the SIGPIPE deterministic: the pipeline's sed is still writing this block
    # long after an early-exiting awk would have matched, so it is guaranteed to write
    # into a closed pipe rather than merely likely to. Sorted-looking key names keep it
    # readable; compose emits keys alphabetically, so `image:` genuinely does sit in the
    # middle of a real block with plenty of lines behind it.
    printf 'services:\n'
    printf '  postgres:\n'
    printf '    container_name: postgres-positivity\n'
    printf '    healthcheck:\n'
    printf '      test:\n'
    printf '      - CMD-SHELL\n'
    printf '      - pg_isready -U postgres\n'
    printf '    image: %s\n' "${DESIRED_IMAGE}"
    printf '    labels:\n'
    for i in $(seq 1 8000); do
      printf '      com.positivity.filler.%s: "padding to force the pipe past its buffer"\n' "${i}"
    done
    printf '    restart: unless-stopped\n'
    printf '  pos-order:\n'
    printf '    image: registry/pos-order:sha-b30123c\n'
    exit 0
    ;;
  ps)
    if [[ "$*" == *postgres* && -n "${CURRENT_IMAGE:-}" ]]; then
      echo "container-postgres"
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
# Serves both `sts get-caller-identity` (account id) and `ecr get-login-password`.
echo "stub-account"
STUB

  chmod +x "${WORK}/bin/docker" "${WORK}/bin/aws"
}

make_alpha_root() {
  local root="$1"
  rm -rf "${root}"
  mkdir -p "${root}/backend/postgres" "${root}/backend/observability"
  # Never parsed by the stub; they only have to exist and match the digests below.
  echo "services: {}" > "${root}/backend/docker-compose.yml"
  echo "services: {}" > "${root}/docker-compose.prod.yml"
  printf 'CREATE DATABASE pos_order_db;\n' > "${root}/backend/postgres/init-databases.sql"
  printf "      password: 'durion-local-prom-scrape-password'\n" > "${root}/backend/observability/prometheus.yml"
  cat > "${root}/.env" <<'ENVFILE'
BACKEND_TAG=sha-0000000
ECR_REGISTRY=288757602241.dkr.ecr.us-east-1.amazonaws.com
SUPPLIER_AUDIT_ENC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
POS_SECURITY_METRICS_SCRAPE_PASSWORD='stub-scrape-password'
ENVFILE
}

# run_case <current postgres image>; sets OUT / RC.
#
# DOCKER_MIN_FREE_GIB=0 keeps the pre-pull reclaim (#1862) out of these cases — left at its
# default it measures the CI runner's real disk through an unstubbed `df`.
run_case() {
  local root="${WORK}/alpha"
  make_alpha_root "${root}"
  : > "${WORK}/compose.log"
  set +e
  OUT="$(
    PATH="${WORK}/bin:${PATH}" \
    LOG="${WORK}/compose.log" \
    DESIRED_IMAGE="${DESIRED_IMAGE}" \
    CURRENT_IMAGE="$1" \
    ALPHA_ROOT="${root}" \
    DOCKER_MIN_FREE_GIB=0 \
    SECURITY_SEED_ADMIN_PASSWORD_HASH='$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0' \
    bash "${SCRIPT}" b30123cfeedfacedeadbeef0123456789abcdef0 2>&1
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

end_case() {
  if [[ ${CASE_FAILURES} -gt 0 ]]; then
    echo "  --- deploy-backend.sh exited ${RC}; output follows ---"
    sed 's/^/  | /' <<< "${OUT}"
    echo "  --- end of output ---"
  fi
  CASE_FAILURES=0
}

make_stubs

echo "case 1: no postgres container on the box"
run_case ""
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
# 141 is SIGPIPE from the config|sed|awk pipeline. Called out on its own because it is the
# specific regression this test exists for, and it looks like any other failure otherwise.
assert "did not die of SIGPIPE (141)" "$([[ ${RC} -ne 141 ]] && echo pass)"
assert "resolved the desired image" "$(grep -qF "desired='${DESIRED_IMAGE}'" <<< "${OUT}" && echo pass)"
assert "reconciles postgres" "$(grep -q 'Reconciling postgres image' <<< "${OUT}" && echo pass)"
assert "reached the domain tiers" "$(grep -q 'Starting domain services' <<< "${OUT}" && echo pass)"
end_case

echo "case 2: postgres is running the desired image already"
run_case "${DESIRED_IMAGE}"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "did not die of SIGPIPE (141)" "$([[ ${RC} -ne 141 ]] && echo pass)"
assert "does not reconcile postgres" "$(! grep -q 'Reconciling postgres image' <<< "${OUT}" && echo pass)"
assert "reached the domain tiers" "$(grep -q 'Starting domain services' <<< "${OUT}" && echo pass)"
end_case

echo "case 3: postgres is running a stale image"
run_case "timescale/timescaledb:2.14.0-pg16"
assert "exits 0" "$([[ ${RC} -eq 0 ]] && echo pass)"
assert "names the current image" "$(grep -qF "current='timescale/timescaledb:2.14.0-pg16'" <<< "${OUT}" && echo pass)"
assert "reconciles postgres" "$(grep -q 'Reconciling postgres image' <<< "${OUT}" && echo pass)"
end_case

echo
if [[ ${FAILURES} -eq 0 ]]; then
  echo "PASS: deploy-backend.sh resolves the postgres image without aborting the deploy"
else
  echo "FAIL: ${FAILURES} assertion(s) failed"
  exit 1
fi
