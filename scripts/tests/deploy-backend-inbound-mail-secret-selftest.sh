#!/usr/bin/env bash
set -euo pipefail

# Self-test for POS_INBOUND_MAIL_API_SECRET in deploy-backend.sh (CAP:550 S27): the inbound-mail edge's API secret, read by
# pos-inbound-mail and pos-accounting. A supplied secret is shape-checked and persisted (a change replaces it); with none
# supplied the copy on the box stays; with neither, one is generated on the box so the edge never starts blank. The
# value is never printed, and the shell copy is always unset so it cannot shadow the env file in compose.
#
# Run: bash scripts/tests/deploy-backend-inbound-mail-secret-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0
SECRET_A='test-only-0123456789abcdef0123456789abcdef'
SECRET_B='test-only-fedcba9876543210fedcba9876543210'

for fn in env_single_quote trim_space require_inbound_mail_secret; do
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
    unset POS_INBOUND_MAIL_API_SECRET
    for assignment in "$@"; do export "${assignment?}"; done
    # shellcheck disable=SC1091
    source "${WORK}/functions.sh"
    require_inbound_mail_secret
    if [[ -n "${POS_INBOUND_MAIL_API_SECRET+set}" ]]; then
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
  for secret in "${SECRET_A}" "${SECRET_B}"; do
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

run_case supplied-persisted 0 "" "POS_INBOUND_MAIL_API_SECRET=${SECRET_A}"
expect_env supplied-persisted "POS_INBOUND_MAIL_API_SECRET='${SECRET_A}'"

run_case supplied-replaces 0 "POS_INBOUND_MAIL_API_SECRET='${SECRET_A}'
" "POS_INBOUND_MAIL_API_SECRET=${SECRET_B}"
expect_env supplied-replaces "POS_INBOUND_MAIL_API_SECRET='${SECRET_B}'"

run_case kept-when-unsupplied 0 "POS_INBOUND_MAIL_API_SECRET='${SECRET_A}'
" "POS_INBOUND_MAIL_API_SECRET="
expect_env kept-when-unsupplied "POS_INBOUND_MAIL_API_SECRET='${SECRET_A}'"
if grep -q generated "${WORK}/out"; then
  echo "FAIL kept-when-unsupplied: generated although the box holds a secret"
  FAILURES=$((FAILURES + 1))
fi

run_case missing-generated 0 ""
if ! grep -qE "^POS_INBOUND_MAIL_API_SECRET='[0-9a-f]{64}'$" "${WORK}/env"; then
  echo "FAIL missing-generated: no generated 64-hex secret in the env file"
  FAILURES=$((FAILURES + 1))
fi
GENERATED="$(grep -E '^POS_INBOUND_MAIL_API_SECRET=' "${WORK}/env" | cut -d"'" -f2)"
if [[ -n "${GENERATED}" ]] && grep -qF "${GENERATED}" "${WORK}/out"; then
  echo "FAIL missing-generated: the generated secret was printed"
  FAILURES=$((FAILURES + 1))
fi

run_case too-short-refused 1 "" "POS_INBOUND_MAIL_API_SECRET=short"
run_case quote-refused 1 "" "POS_INBOUND_MAIL_API_SECRET=${SECRET_A}'x"

if [[ "${FAILURES}" -ne 0 ]]; then
  echo "${FAILURES} failure(s)"
  exit 1
fi
echo "All POS_INBOUND_MAIL_API_SECRET deploy cases pass."
