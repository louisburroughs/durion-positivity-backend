#!/usr/bin/env bash
set -euo pipefail

# Self-test for POS_SECURITY_STEP_UP_SECRET in deploy-backend.sh (#2584): the internal step-up check's own secret,
# read by pos-security-service and pos-order only. A supplied secret is shape-checked and persisted (a change
# replaces it: it seals nothing); with none supplied the copy on the box stays; with neither, one is generated on
# the box. A value equal to POS_SECURITY_API_SECRET is refused. The value is never printed, and the shell copy is
# always unset so it cannot shadow the env file in compose.
#
# Run: bash scripts/tests/deploy-backend-step-up-secret-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0
SECRET_A='test-only-0123456789abcdef0123456789abcdef'
SECRET_B='test-only-fedcba9876543210fedcba9876543210'
MESH='test-only-mesh-00112233445566778899aabbccdd'

for fn in env_single_quote trim_space require_step_up_secret; do
  if ! grep -q "^${fn}() {" "${SCRIPT}"; then
    echo "FAIL: ${fn} not found in deploy-backend.sh" >&2
    exit 1
  fi
  awk -v fn="${fn}" '$0 ~ "^"fn"\\(\\) \\{" {p=1} p {print} p && /^}/ {p=0}' "${SCRIPT}" >> "${WORK}/functions.sh"
done

# $1 case name, $2 expected exit, $3 env file content, then VAR=value assignments.
run_case() {
  local name="$1" expected="$2" content="$3"
  shift 3
  printf '%s' "${content}" > "${WORK}/env"
  local rc=0
  (
    ENV_FILE="${WORK}/env"
    unset POS_SECURITY_STEP_UP_SECRET
    for assignment in "$@"; do export "${assignment?}"; done
    # shellcheck disable=SC1091
    source "${WORK}/functions.sh"
    require_step_up_secret
    if [[ -n "${POS_SECURITY_STEP_UP_SECRET+set}" ]]; then
      echo "shell copy still set" >&2
      exit 3
    fi
  ) > "${WORK}/out" 2>&1 || rc=$?
  if [[ "${rc}" != "${expected}" ]]; then
    echo "FAIL ${name}: exit ${rc}, expected ${expected}"
    FAILURES=$((FAILURES + 1))
  else
    echo "ok   ${name}"
  fi
  for secret in "${SECRET_A}" "${SECRET_B}" "${MESH}"; do
    if grep -qF "${secret}" "${WORK}/out"; then
      echo "FAIL ${name}: a secret was printed"
      FAILURES=$((FAILURES + 1))
    fi
  done
}

expect_env() {
  if ! grep -qx "$2" "${WORK}/env"; then
    echo "FAIL $1: env file lacks $2"
    FAILURES=$((FAILURES + 1))
  fi
}

run_case supplied-persisted 0 "" "POS_SECURITY_STEP_UP_SECRET=${SECRET_A}"
expect_env supplied-persisted "POS_SECURITY_STEP_UP_SECRET='${SECRET_A}'"

run_case supplied-replaces 0 "POS_SECURITY_STEP_UP_SECRET='${SECRET_A}'
" "POS_SECURITY_STEP_UP_SECRET=${SECRET_B}"
expect_env supplied-replaces "POS_SECURITY_STEP_UP_SECRET='${SECRET_B}'"

run_case kept-when-unsupplied 0 "POS_SECURITY_STEP_UP_SECRET='${SECRET_A}'
" "POS_SECURITY_STEP_UP_SECRET="
expect_env kept-when-unsupplied "POS_SECURITY_STEP_UP_SECRET='${SECRET_A}'"
if grep -q generated "${WORK}/out"; then
  echo "FAIL kept-when-unsupplied: generated although the box holds a secret"
  FAILURES=$((FAILURES + 1))
fi

run_case missing-generated 0 "POS_SECURITY_API_SECRET='${MESH}'
"
if ! grep -qE "^POS_SECURITY_STEP_UP_SECRET='[0-9a-f]{64}'$" "${WORK}/env"; then
  echo "FAIL missing-generated: no generated 64-hex secret in the env file"
  FAILURES=$((FAILURES + 1))
fi
GENERATED="$(grep -E '^POS_SECURITY_STEP_UP_SECRET=' "${WORK}/env" | cut -d"'" -f2)"
if [[ -n "${GENERATED}" ]] && grep -qF "${GENERATED}" "${WORK}/out"; then
  echo "FAIL missing-generated: the generated secret was printed"
  FAILURES=$((FAILURES + 1))
fi

run_case mesh-supplied-refused 1 "POS_SECURITY_API_SECRET='${MESH}'
" "POS_SECURITY_STEP_UP_SECRET=${MESH}"
if grep -q "^POS_SECURITY_STEP_UP_SECRET=" "${WORK}/env"; then
  echo "FAIL mesh-supplied-refused: the refused value reached the env file"
  FAILURES=$((FAILURES + 1))
fi
run_case mesh-on-box-refused 1 "POS_SECURITY_API_SECRET='${MESH}'
POS_SECURITY_STEP_UP_SECRET='${MESH}'
"

run_case too-short-refused 1 "" "POS_SECURITY_STEP_UP_SECRET=short"
run_case quote-refused 1 "" "POS_SECURITY_STEP_UP_SECRET=${SECRET_A}'x"

if [[ "${FAILURES}" -ne 0 ]]; then
  echo "${FAILURES} failure(s)"
  exit 1
fi
echo "All POS_SECURITY_STEP_UP_SECRET deploy cases pass."
