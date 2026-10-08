#!/usr/bin/env bash
set -euo pipefail

# Self-test for pos-supplier's two encryption keys in deploy-backend.sh (#2621).
#
# SUPPLIER_AUDIT_ENC_KEY (exchange-audit payloads, ADR-0050 §7) and SUPPLIER_VENDOR_TAXID_ENC_KEY
# (vendor tax-registration numbers, Security ruling on #2617, ruling 3) are resolved the same way:
# a supplied key is shape-checked and persisted, a different key already on the box is refused as a
# silent rotation, and a missing one fails the deploy. On top of that the two must differ, compared
# unpadded, whether supplied or already on the box, and a refused pair is never persisted.
#
# Run: bash scripts/tests/deploy-backend-supplier-keys-selftest.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/deployment/alpha/deploy-backend.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0
KEY_A='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
KEY_B='BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB='
KEY_C='CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC='

# Pull the key functions out of the script, so the test exercises the shipped code.
for fn in env_single_quote trim_space require_supplier_key require_supplier_audit_key effective_supplier_key \
    require_distinct_supplier_keys drop_shell_copy_of_supplier_key; do
  if ! grep -q "^${fn}() {" "${SCRIPT}"; then
    echo "FAIL: ${fn} not found in deploy-backend.sh" >&2
    exit 1
  fi
  awk -v fn="${fn}" '$0 ~ "^"fn"\\(\\) \\{" {p=1} p {print} p && /^}/ {p=0}' "${SCRIPT}" >> "${WORK}/functions.sh"
done

# $1 case name, $2 expected exit (0 or 1), $3 env file content, then VAR=value assignments.
run_case() {
  local name="$1" expected="$2" content="$3"
  shift 3
  printf '%s' "${content}" > "${WORK}/env"
  local rc=0
  (
    ENV_FILE="${WORK}/env"
    unset SUPPLIER_AUDIT_ENC_KEY SUPPLIER_VENDOR_TAXID_ENC_KEY
    for assignment in "$@"; do export "${assignment?}"; done
    # shellcheck disable=SC1091
    source "${WORK}/functions.sh"
    require_supplier_audit_key
  ) > "${WORK}/out" 2>&1 || rc=$?
  if [[ "${rc}" != "${expected}" ]]; then
    echo "FAIL ${name}: exit ${rc}, expected ${expected}"
    sed 's/^/  | /' "${WORK}/out"
    FAILURES=$((FAILURES + 1))
  else
    echo "ok   ${name}"
  fi
}

assert_file_lacks() {
  if grep -q "$2" "${WORK}/env"; then
    echo "FAIL $1: env file holds $2"
    FAILURES=$((FAILURES + 1))
  fi
}

run_case distinct-supplied 0 "" "SUPPLIER_AUDIT_ENC_KEY=${KEY_A}" "SUPPLIER_VENDOR_TAXID_ENC_KEY=${KEY_B}"
grep -q "^SUPPLIER_VENDOR_TAXID_ENC_KEY='${KEY_B}'" "${WORK}/env" \
  || { echo "FAIL distinct-supplied: vendor key not persisted"; FAILURES=$((FAILURES + 1)); }

run_case distinct-on-box 0 "SUPPLIER_AUDIT_ENC_KEY='${KEY_A}'
SUPPLIER_VENDOR_TAXID_ENC_KEY='${KEY_B}'
"

run_case equal-supplied 1 "" "SUPPLIER_AUDIT_ENC_KEY=${KEY_A}" "SUPPLIER_VENDOR_TAXID_ENC_KEY=${KEY_A}"
grep -q 'must not equal' "${WORK}/out" || { echo "FAIL equal-supplied: message"; FAILURES=$((FAILURES + 1)); }
assert_file_lacks equal-supplied "SUPPLIER_VENDOR_TAXID_ENC_KEY"

run_case equal-unpadded 1 "" "SUPPLIER_AUDIT_ENC_KEY=${KEY_A}" "SUPPLIER_VENDOR_TAXID_ENC_KEY=${KEY_A%=}"

run_case equal-on-box 1 "SUPPLIER_AUDIT_ENC_KEY='${KEY_A}'
SUPPLIER_VENDOR_TAXID_ENC_KEY='${KEY_A%=}'
"

run_case supplied-equals-on-box-other 1 "SUPPLIER_AUDIT_ENC_KEY='${KEY_A}'
" "SUPPLIER_VENDOR_TAXID_ENC_KEY=${KEY_A}"
assert_file_lacks supplied-equals-on-box-other "SUPPLIER_VENDOR_TAXID_ENC_KEY"

run_case vendor-missing 1 "SUPPLIER_AUDIT_ENC_KEY='${KEY_A}'
"
grep -q 'SUPPLIER_VENDOR_TAXID_ENC_KEY is empty or missing' "${WORK}/out" \
  || { echo "FAIL vendor-missing: message"; FAILURES=$((FAILURES + 1)); }

run_case vendor-rotation-refused 1 "SUPPLIER_AUDIT_ENC_KEY='${KEY_A}'
SUPPLIER_VENDOR_TAXID_ENC_KEY='${KEY_B}'
" "SUPPLIER_VENDOR_TAXID_ENC_KEY=${KEY_C}"
grep -q 'differs from the key already on this box' "${WORK}/out" \
  || { echo "FAIL vendor-rotation-refused: message"; FAILURES=$((FAILURES + 1)); }

if [[ "${FAILURES}" -eq 0 ]]; then
  echo "PASS: deploy-backend.sh resolves both pos-supplier keys and keeps them distinct"
else
  echo "FAIL: ${FAILURES} case(s)"
  exit 1
fi
